package com.kennyb1201.kbstream.ui.player

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections

/**
 * A loopback HTTP/1.1 server, one connection at a time.
 *
 * The JDK's own com.sun.net.httpserver is not on Android's unit-test compile
 * classpath, and all that is needed here is "answer a request, record what it
 * was" - no routing, no keep-alive. Every response carries Connection: close
 * and a Content-Length so HttpURLConnection never waits on a socket that stayed
 * open.
 */
private class LoopbackServer(
    private val respond: (method: String, path: String, headers: Map<String, String>, body: String) -> Reply
) {
    data class Reply(val status: Int, val body: String)

    private val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    val baseUrl: String get() = "http://127.0.0.1:${socket.localPort}"

    private val worker = Thread {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (t: Throwable) {
                return@Thread
            }
            try {
                val input = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
                val requestLine = input.readLine()
                if (!requestLine.isNullOrBlank()) {
                    val parts = requestLine.split(' ')
                    val method = parts.getOrElse(0) { "" }
                    val path = parts.getOrElse(1) { "" }
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        val colon = line.indexOf(':')
                        if (colon > 0) {
                            headers[line.substring(0, colon).trim().lowercase()] =
                                line.substring(colon + 1).trim()
                        }
                    }
                    val body = readBody(input, headers["content-length"]?.toIntOrNull() ?: 0)
                    val reply = respond(method, path, headers, body)
                    val payload = reply.body.toByteArray(Charsets.UTF_8)
                    val head = "HTTP/1.1 ${reply.status} KBTest\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${payload.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    client.getOutputStream().apply {
                        write(head.toByteArray(Charsets.UTF_8))
                        write(payload)
                        flush()
                    }
                }
            } catch (t: Throwable) {
                // A client that hung up mid-request is not this server's
                // problem: the next test starts from a fresh socket anyway.
            } finally {
                runCatching { client.close() }
            }
        }
    }.apply {
        isDaemon = true
        start()
    }

    fun close() {
        runCatching { socket.close() }
    }

    private fun readBody(reader: BufferedReader, length: Int): String {
        if (length <= 0) return ""
        val chars = CharArray(length)
        var read = 0
        while (read < length) {
            val n = reader.read(chars, read, length - read)
            if (n < 0) break
            read += n
        }
        return String(chars, 0, read)
    }
}

/**
 * The download leg of the online subtitle picker, driven against a real HTTP
 * server on loopback.
 *
 * The bug these pin: `/download` is a POST endpoint (the API answers a GET with
 * "405 Method Not Allowed"), so every row in the picker failed while the search
 * above it worked — search is the endpoint whose contract is a GET. Nothing
 * short of a real request can tell those two apart, which is why this test
 * brings up a socket instead of stubbing the call.
 */
class SubtitleSearchHelperTest {

    private data class Recorded(
        val method: String,
        val path: String,
        val body: String,
        val headers: Map<String, String>
    )

    private lateinit var server: LoopbackServer
    private lateinit var base: String
    private val recorded = Collections.synchronizedList(mutableListOf<Recorded>())

    /** Status and body the fake OpenSubtitles answers `/download` with. */
    private var metaStatus = 200
    private var metaBody = ""

    /** ... and the subtitle file itself, for whatever link it handed out. */
    private var fileStatus = 200
    private var fileBody = ""

    @Before
    fun startServer() {
        recorded.clear()
        server = LoopbackServer { method, path, headers, body ->
            recorded.add(Recorded(method, path, body, headers))
            val (status, payload) = if (path == "/download") {
                metaStatus to metaBody
            } else {
                fileStatus to fileBody
            }
            LoopbackServer.Reply(status, payload)
        }
        base = server.baseUrl
    }

    @After
    fun stopServer() {
        server.close()
    }

    private fun linkResponse() =
        """{"link":"$base/sub.srt","file_name":"Some Movie.srt","requests":1}"""

    @Test
    fun `download asks the download endpoint with a POST and a file id body`() {
        metaBody = linkResponse()
        fileBody = "1\n00:00:01,000 --> 00:00:03,000\nHello\n"

        val result = SubtitleSearchHelper.download(4242L, "API-KEY", base)

        assertTrue("expected the subtitle text, got $result", result is SubtitleDownload.Ready)
        assertEquals(
            "1\n00:00:01,000 --> 00:00:03,000\nHello\n",
            (result as SubtitleDownload.Ready).body
        )

        val meta = recorded[0]
        assertEquals("POST", meta.method)
        assertEquals("/download", meta.path)
        assertEquals("""{"file_id":4242}""", meta.body)
        assertEquals("API-KEY", meta.headers["api-key"])
        assertEquals("application/json", meta.headers["content-type"])
        assertFalse(
            "the API rejects a blank or default user agent",
            meta.headers["user-agent"].isNullOrBlank()
        )

        // The one-time link is followed, and the subtitle comes from there.
        assertEquals(2, recorded.size)
        assertEquals("GET", recorded[1].method)
        assertEquals("/sub.srt", recorded[1].path)
    }

    @Test
    fun `a quota message is what the viewer is told`() {
        metaStatus = 406
        metaBody = """{"message":"You have downloaded your allowed 5 subtitles for the last 24 hours."}"""

        val result = SubtitleSearchHelper.download(7L, "API-KEY", base)

        val reason = (result as SubtitleDownload.Failed).reason
        assertTrue(
            "reason should carry the API's own words, was: $reason",
            reason.contains("allowed 5 subtitles")
        )
        assertEquals("the file is never fetched once /download refuses", 1, recorded.size)
    }

    @Test
    fun `a rejected key is reported as a rejected key`() {
        metaStatus = 401

        val result = SubtitleSearchHelper.download(7L, "bad-key", base)

        assertTrue((result as SubtitleDownload.Failed).reason.contains("rejected the API key"))
    }

    @Test
    fun `a response with no link fails instead of attaching an empty subtitle`() {
        metaBody = """{"requests":1,"remaining":99}"""

        val result = SubtitleSearchHelper.download(7L, "API-KEY", base)

        assertTrue((result as SubtitleDownload.Failed).reason.contains("no download link"))
        assertEquals(1, recorded.size)
    }

    @Test
    fun `an error page served as the subtitle is refused`() {
        metaBody = linkResponse()
        fileBody = "<!DOCTYPE html><html><body>Not found</body></html>"

        val result = SubtitleSearchHelper.download(7L, "API-KEY", base)

        assertTrue((result as SubtitleDownload.Failed).reason.contains("did not return a subtitle"))
    }

    @Test
    fun `an unreachable server is a failure, not a crash`() {
        // Nothing listens on port 1 of loopback, so the connect is refused
        // instead of waiting the socket timeout out.
        val result = SubtitleSearchHelper.download(7L, "API-KEY", "http://127.0.0.1:1")

        assertTrue((result as SubtitleDownload.Failed).reason.contains("could not reach OpenSubtitles"))
    }

    @Test
    fun `a subtitle payload must at least look like one`() {
        assertTrue(SubtitleSearchHelper.looksLikeSubtitle("1\n00:00:01,000 --> 00:00:03,000\nhi\n"))
        assertTrue(SubtitleSearchHelper.looksLikeSubtitle("WEBVTT\n\n00:00.000 --> 00:02.000\nhi\n"))
        assertTrue(SubtitleSearchHelper.looksLikeSubtitle("[Script Info]\nTitle: x\n"))
        assertFalse(SubtitleSearchHelper.looksLikeSubtitle(""))
        assertFalse(SubtitleSearchHelper.looksLikeSubtitle("   \n"))
        assertFalse(SubtitleSearchHelper.looksLikeSubtitle("<!DOCTYPE html>\n<html></html>"))
        assertFalse(SubtitleSearchHelper.looksLikeSubtitle("<html><body>502</body></html>"))
    }

    @Test
    fun `search rows are parsed from the api payload`() {
        val body = """
            {"total_pages":1,"data":[
              {"attributes":{"language":"en","download_count":912,
                "release":"Some.Movie.2024.1080p",
                "files":[{"file_id":101,"file_name":"Some.Movie.2024.srt"}]}},
              {"attributes":{"language":"es","download_count":12,"release":"",
                "files":[{"file_id":102,"file_name":""}]}}
            ]}
        """.trimIndent()

        val rows = SubtitleSearchHelper.parseSearchResults(body)

        assertEquals(2, rows.size)
        assertEquals(101L, rows[0].fileId)
        assertEquals("Some.Movie.2024.srt", rows[0].fileName)
        assertEquals("en", rows[0].language)
        assertEquals(912, rows[0].downloads)
        assertEquals(102L, rows[1].fileId)
        assertEquals("es", rows[1].language)
    }

    @Test
    fun `a row without a usable file id is dropped`() {
        val body = """{"data":[{"attributes":{"files":[{"file_name":"x.srt"}]}}]}"""

        assertTrue(SubtitleSearchHelper.parseSearchResults(body).isEmpty())
    }

    @Test
    fun `the result list is capped`() {
        val rows = (1..30).joinToString(",") {
            """{"attributes":{"language":"en","files":[{"file_id":$it,"file_name":"$it.srt"}]}}"""
        }

        assertEquals(12, SubtitleSearchHelper.parseSearchResults("""{"data":[$rows]}""").size)
    }

    @Test
    fun `a malformed payload yields no rows rather than throwing`() {
        assertTrue(SubtitleSearchHelper.parseSearchResults("not json").isEmpty())
        assertTrue(SubtitleSearchHelper.parseSearchResults("").isEmpty())
    }
}
