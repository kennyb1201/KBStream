package com.kennyb1201.kbstream.data.iptv

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Xumo FAST guide, translated to XMLTV in memory.
 *
 * Xumo publishes no XMLTV file: its schedule is a JSON API. This adapter
 * fetches that JSON and returns an XMLTV document as a STREAM, which the
 * existing [XmltvImporter] then parses exactly as it parses a downloaded guide.
 * That is the whole design: no second importer, no second staged swap, no second
 * empty-feed guard, no second matcher pass. Prune, profile pinning, the watchdog
 * and the per-source stats all come from the importer that already has them.
 *
 * Everything below was verified against the live API on 2026-10-25; where the
 * shipped community generator and the live response disagree, the live response
 * is what this file implements, and the divergence is called out at each site.
 */
internal object XumoGuide {

    /**
     * The adapter's source key.
     *
     * URL-shaped on purpose. The adapter is NOT a line in the Extra EPG field
     * (there is nothing to paste or edit), but the key still travels through
     * every place that assumes a guide source is a URL: [Redaction.url] strips
     * userinfo from anything with a `scheme://`, `GuideSourceStamps` stores
     * `<epochMs>|<key>` lines, and the diagnostics and Playlist Setup rows print
     * it. A bare `"xumo"` would survive all of those too, but `xumo://guide`
     * additionally makes a log line unambiguous about which source it came from,
     * and cannot collide with a real provider URL. Behaviour is identical either
     * way; see the landing report for the audit.
     */
    const val SOURCE_KEY = "xumo://guide"

    /**
     * The lineup the Android TV app reads. Live-verified: HTTP 200,
     * `totalChannels: 455`, 453 of which carry programs.
     *
     * This is the id the Channels-community `xumo.py` generator uses. Its newer
     * sibling (`generate_xumo.py`) reads lineup `10006` off the `valencia-app-mds`
     * host instead; both were verified to answer, and both list the same 455
     * channels including American Ninja Warrior, so the choice is not
     * load-bearing - but only ONE lineup may be fetched, or every channel would
     * be emitted twice.
     */
    const val LINEUP_ID = "10032"

    private const val MDS_HOST = "https://android-tv-mds.xumo.com"

    /** Channel display names. The EPG response carries no titles at all. */
    const val CHANNEL_LIST_URL =
        "$MDS_HOST/v2/channels/list/$LINEUP_ID.json?f=genreId&sort=hybrid&geoId=unknown"

    /**
     * The path's trailing number is NOT a day page - it is a SIX-HOUR BLOCK.
     *
     * The community generator's URL shape is identical but its meaning was
     * misread: block `0` returns 00:00-06:00, `1` 06:00-12:00, `2` 12:00-18:00,
     * `3` 18:00-24:00, and `4` and above answer HTTP 400. A full day is
     * therefore four block requests per offset page (~44 calls), not one.
     */
    const val BLOCKS_PER_DAY = 4

    fun epgUrl(day: String, block: Int, offset: Int): String =
        "$MDS_HOST/v2/epg/$LINEUP_ID/$day/$block.json" +
            "?limit=$PAGE_SIZE&offset=$offset&f=asset.title&f=asset.descriptions"

    /** Page size the API documents, and the step used to walk it. */
    const val PAGE_SIZE = 50

    /**
     * A hard stop on pagination.
     *
     * The walk ends when a page comes back empty, which is the API's own
     * contract; this ceiling only exists so a server that answers every offset
     * with the same page cannot spin an import forever. 455 channels is ten
     * pages, so this is roughly four times the largest real walk seen.
     */
    const val MAX_PAGES_PER_BLOCK = 40

    /**
     * How many extra days the coverage loop may fetch.
     *
     * §7's rule is coverage, not a day count: the loop below re-checks whether
     * the fetched program ends past the window and extends by one day when they
     * do not. Two days is what the current 20 h window needs in practice; the
     * cap stops a provider whose schedule simply ends early from turning that
     * into an unbounded download.
     */
    const val MAX_EXTRA_DAYS = 2

    /** Channel-tile art, the same URL shape the community generator emits. */
    fun iconUrl(channelId: Long): String =
        "https://image.xumo.com/v1/channels/channel/xumo-$channelId/600x337.png?type=channelTile"

    /** The XMLTV channel id for one Xumo channel id. */
    fun xmltvChannelId(channelId: Long): String = "xumo-$channelId"
}

/**
 * Fetches and translates the Xumo guide.
 *
 * [fetchText] is injected rather than taking an `OkHttpClient` so the whole
 * translation is unit-testable against fixtures with no network and no HTTP
 * stack - the same reason [XmltvDates] was lifted out of the importer.
 */
internal class XumoEpgTranslator(
    private val fetchText: suspend (url: String) -> String
) {

    /**
     * The guide for [windowStartMs]..[windowEndMs], as an XMLTV stream.
     *
     * Throws on anything that would make this a partial guide: a failed channel
     * list, a failed page, a failed block. §6 is explicit that a partial walk is
     * a FAILED SOURCE, not a partial import, so nothing here degrades quietly.
     * The caller ([IptvRepository]) turns the throw into the ordinary per-source
     * failure: staging discarded, live rows kept, freshness stamp not advanced.
     */
    suspend fun fetch(windowStartMs: Long, windowEndMs: Long): InputStream =
        ByteArrayInputStream(translate(windowStartMs, windowEndMs).toByteArray(Charsets.UTF_8))

    internal suspend fun translate(windowStartMs: Long, windowEndMs: Long): String {
        // Names first: without them the schedule below is unmatchable (the EPG
        // response has ids and no titles), so a channel-list failure must fail
        // the source rather than produce a nameless feed.
        val titles = parseChannelList(fetchText(XumoGuide.CHANNEL_LIST_URL))

        val days = daysFor(windowStartMs, windowEndMs).toMutableList()
        val schedule = LinkedHashMap<Long, MutableList<XumoScheduleEntry>>()
        val assets = HashMap<String, XumoAssetEntry>()

        var index = 0
        var extensions = 0
        while (index < days.size) {
            mergeDay(days[index], schedule, assets)
            index++
            // §7: coverage, not a count. If what was fetched ends before the
            // window does, one more day is fetched and re-checked. A clock skew
            // or a date bucket that disagrees with the generator's UTC
            // assumption shows up here as a window the fetched days do not
            // reach, and it is extended rather than silently dropped.
            if (index == days.size &&
                extendsWindow(schedule, windowEndMs) &&
                extensions < XumoGuide.MAX_EXTRA_DAYS
            ) {
                val next = shiftDay(days.last(), +1) ?: break
                days.add(next)
                extensions++
            }
        }

        return translateToXmltv(titles, schedule, assets)
    }

    /** Walks every block and every offset page of one day and merges it. */
    private suspend fun mergeDay(
        day: String,
        schedule: MutableMap<Long, MutableList<XumoScheduleEntry>>,
        assets: MutableMap<String, XumoAssetEntry>
    ) {
        for (block in 0 until XumoGuide.BLOCKS_PER_DAY) {
            var offset = 0
            var pages = 0
            while (true) {
                val chunk = parseEpgChunk(fetchText(XumoGuide.epgUrl(day, block, offset)))
                if (chunk.channels.isEmpty()) break
                chunk.channels.forEach { (channelId, entries) ->
                    schedule.getOrPut(channelId) { ArrayList() }.addAll(entries)
                }
                assets.putAll(chunk.assets)
                pages++
                offset += XumoGuide.PAGE_SIZE
                // A page shorter than the limit is the last page; the empty-page
                // check above still runs first, because the API answers a past
                // the end offset with an empty array rather than a 404.
                if (chunk.channels.size < XumoGuide.PAGE_SIZE) break
                if (pages >= XumoGuide.MAX_PAGES_PER_BLOCK) break
            }
        }
    }

    /**
     * Whether the fetched programs already reach [windowEndMs].
     *
     * Deliberately lenient: the check is "does ANY program end at or after the
     * window", because a schedule's last hours are routinely sparse on some
     * channels - a strict per-channel rule would extend the fetch forever.
     */
    private fun extendsWindow(
        schedule: Map<Long, List<XumoScheduleEntry>>,
        windowEndMs: Long
    ): Boolean {
        var latest = Long.MIN_VALUE
        for (entries in schedule.values) {
            for (entry in entries) {
                val end = parseXumoInstant(entry.end) ?: continue
                if (end > latest) latest = end
            }
        }
        return latest == Long.MIN_VALUE || latest < windowEndMs
    }

    /** The next calendar day, or null when [day] is not a `yyyyMMdd` value. */
    private fun shiftDay(day: String, delta: Long): String? = runCatching {
        DAY_FORMAT.format(LocalDate.parse(day, DAY_FORMAT).plusDays(delta))
    }.getOrNull()

    private companion object {
        val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    }
}

/**
 * UTC day buckets a window spans, inclusive.
 *
 * UTC on purpose: the platform's own date buckets are UTC (the community
 * generator uses `datetime.now(timezone.utc)`), and every program carries its
 * own offset, so the bucket only decides WHICH days are fetched. §7's rule is
 * coverage, and the caller extends this list when the fetched programs do not
 * reach the window.
 */
internal fun daysFor(windowStartMs: Long, windowEndMs: Long): List<String> {
    val format = DateTimeFormatter.ofPattern("yyyyMMdd")
    var date = Instant.ofEpochMilli(windowStartMs).atZone(ZoneOffset.UTC).toLocalDate()
    val last = Instant.ofEpochMilli(maxOf(windowStartMs, windowEndMs))
        .atZone(ZoneOffset.UTC)
        .toLocalDate()
    val days = ArrayList<String>(2)
    while (!date.isAfter(last)) {
        days.add(format.format(date))
        date = date.plusDays(1)
        // Twelve days is far past any window this app imports; the guard is
        // against a caller passing an inverted or absurd range, not a policy.
        if (days.size > 12) break
    }
    return days
}

/** One channel's schedule inside an EPG page. */
internal data class XumoScheduleEntry(
    val assetId: String,
    val start: String,
    val end: String
)

/** The asset table an EPG page carries, keyed by asset id. */
internal data class XumoAssetEntry(
    val title: String?,
    val episodeTitle: String?,
    val descriptions: Map<String, String>
)

/** One EPG page, already separated into its two halves. */
internal data class XumoEpgChunk(
    val channels: Map<Long, List<XumoScheduleEntry>>,
    val assets: Map<String, XumoAssetEntry>
)

/**
 * `channel.item[].title` by `guid.value`.
 *
 * This endpoint - not the schedule response - is the only title source. It is
 * also where the DRM placeholder pages are dropped: `Series Page - DRM` and
 * `Movies Page - DRM` are not channels and have no schedule, so emitting them
 * would only add two dead guide channels to prune.
 */
internal fun parseChannelList(json: String): Map<Long, String> {
    val root = JSONObject(json)
    val items = root.optJSONObject("channel")?.optJSONArray("item") ?: JSONArray()
    val titles = LinkedHashMap<Long, String>(items.length())
    for (index in 0 until items.length()) {
        val item = items.optJSONObject(index) ?: continue
        val title = item.optString("title").trim()
        if (title.isEmpty()) continue
        val id = item.optJSONObject("guid")?.optString("value")?.toLongOrNull() ?: continue
        titles[id] = title
    }
    return titles
}

/** Splits one EPG page into its schedule and its asset table. */
internal fun parseEpgChunk(json: String): XumoEpgChunk {
    val root = JSONObject(json)
    val channelsJson = root.optJSONArray("channels") ?: JSONArray()
    val channels = LinkedHashMap<Long, List<XumoScheduleEntry>>(channelsJson.length())
    for (index in 0 until channelsJson.length()) {
        val channel = channelsJson.optJSONObject(index) ?: continue
        val channelId = channel.optLong("channelId")
        if (channelId <= 0L) continue
        val scheduleJson = channel.optJSONArray("schedule") ?: JSONArray()
        val entries = ArrayList<XumoScheduleEntry>(scheduleJson.length())
        for (entryIndex in 0 until scheduleJson.length()) {
            val entry = scheduleJson.optJSONObject(entryIndex) ?: continue
            val assetId = entry.optString("assetId").trim()
            val start = entry.optString("start").trim()
            val end = entry.optString("end").trim()
            if (assetId.isEmpty() || start.isEmpty() || end.isEmpty()) continue
            entries.add(XumoScheduleEntry(assetId = assetId, start = start, end = end))
        }
        channels[channelId] = entries
    }

    val assetsJson = root.optJSONObject("assets") ?: JSONObject()
    val assets = HashMap<String, XumoAssetEntry>(assetsJson.length())
    assetsJson.keys().forEach { key ->
        val asset = assetsJson.optJSONObject(key) ?: return@forEach
        val descriptions = HashMap<String, String>(4)
        asset.optJSONObject("descriptions")?.let { descriptionsJson ->
            descriptionsJson.keys().forEach { name ->
                val value = descriptionsJson.optString(name).trim()
                if (value.isNotEmpty()) descriptions[name] = value
            }
        }
        assets[key] = XumoAssetEntry(
            title = asset.optString("title").trim().ifEmpty { null },
            // Live responses omit this far more often than they carry it, so
            // its absence is the normal case, not an error.
            episodeTitle = asset.optString("episodeTitle").trim().ifEmpty { null },
            descriptions = descriptions
        )
    }
    return XumoEpgChunk(channels = channels, assets = assets)
}

/**
 * The XMLTV document for one translated day-set.
 *
 * Ordering is by channel id so the same input always produces the same bytes -
 * which is what lets a fixture test assert on the output without sorting it
 * first.
 */
internal fun translateToXmltv(
    titles: Map<Long, String>,
    schedule: Map<Long, List<XumoScheduleEntry>>,
    assets: Map<String, XumoAssetEntry>
): String {
    val out = StringBuilder(1 shl 20)
    out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    out.append("<tv generator-info-name=\"KBStream Xumo adapter\">\n")

    val channels = schedule.keys.filter { titles.containsKey(it) }.sorted()
    channels.forEach { channelId ->
        out.append("<channel id=\"").append(xmlEscape(XumoGuide.xmltvChannelId(channelId))).append("\">")
        out.append("<display-name>").append(xmlEscape(titles.getValue(channelId))).append("</display-name>")
        // Kept because XmltvImporter DOES preserve channel icons (readChannel
        // stores <icon src>), so this is a supported field rather than an
        // invented one. Programme icons are not emitted: readProgram ignores
        // them, and an element the importer drops is pure bytes.
        out.append("<icon src=\"").append(xmlEscape(XumoGuide.iconUrl(channelId))).append("\"/>")
        out.append("</channel>\n")
    }

    channels.forEach { channelId ->
        val channelKey = XumoGuide.xmltvChannelId(channelId)
        schedule.getValue(channelId).forEach { entry ->
            val start = parseXumoInstant(entry.start) ?: return@forEach
            val end = parseXumoInstant(entry.end) ?: return@forEach
            if (end <= start) return@forEach
            val asset = assets[entry.assetId]

            out.append("<programme start=\"").append(xmltvTimestamp(start))
                .append("\" stop=\"").append(xmltvTimestamp(end))
                .append("\" channel=\"").append(xmlEscape(channelKey)).append("\">")

            val title = asset?.title.orEmpty()
            out.append("<title>").append(xmlEscape(title)).append("</title>")

            // Sub-title only when it says something the title does not: the
            // generator emits it whenever present, but a show whose episode
            // title repeats its title would render as the same line twice.
            val episodeTitle = asset?.episodeTitle
            if (!episodeTitle.isNullOrBlank() && !episodeTitle.equals(title, ignoreCase = true)) {
                out.append("<sub-title>").append(xmlEscape(episodeTitle)).append("</sub-title>")
            }

            xumoDescription(asset?.descriptions)?.let { description ->
                out.append("<desc>").append(xmlEscape(description)).append("</desc>")
            }

            // The generator's own rule: an `EP…` asset is a program id, anything
            // else is a series id. NOTE the current importer does not read
            // <episode-num> at all, so this is forward-looking metadata rather
            // than something the guide displays today; it is emitted because the
            // spec pins the mapping, and dropping it would make the XML not
            // match the platform's own identity rules.
            val (system, value) = episodeNum(entry.assetId)
            out.append("<episode-num system=\"").append(system).append("\">")
                .append(xmlEscape(value)).append("</episode-num>")

            out.append("</programme>\n")
        }
    }

    out.append("</tv>\n")
    return out.toString()
}

/**
 * The description fallback chain, largest first.
 *
 * `large` is nearly always absent in live responses - the sampled assets carry
 * `medium` and nothing else - so the chain is what makes descriptions appear at
 * all. Empty is a legitimate answer and must not become the string "null".
 */
internal fun xumoDescription(descriptions: Map<String, String>?): String? {
    if (descriptions == null) return null
    return XUMO_DESCRIPTION_KEYS
        .firstNotNullOfOrNull { key -> descriptions[key]?.takeIf { it.isNotBlank() } }
}

private val XUMO_DESCRIPTION_KEYS = listOf("large", "medium", "small", "tiny")

/** `EP…` → `dd_progid`, everything else → `dd_seriesid`. */
internal fun episodeNum(assetId: String): Pair<String, String> =
    if (assetId.startsWith("EP")) "dd_progid" to assetId else "dd_seriesid" to assetId

/**
 * Parses the API's own timestamp spelling.
 *
 * The live values look like `2026-10-25T00:00:00+0000` - an ISO date-time with a
 * COMPACT numeric offset. That is deliberately not delegated to
 * [parseXmltvDateMillis]: that parser is tuned for guide FILES (compact
 * `yyyyMMddHHmmss`, and ISO with `Z` or a colon-bearing offset), and it returns
 * null for this spelling. The first live run of this adapter proved it - 455
 * channels and zero programmes, because every start parsed to null and every
 * entry was skipped. A silent whole-feed drop is exactly the failure mode this
 * function exists to make impossible, so the offset form is handled explicitly
 * rather than assumed.
 */
internal fun parseXumoInstant(value: String?): Long? {
    val raw = value?.trim().orEmpty()
    if (raw.isEmpty()) return null
    for (format in XUMO_TIMESTAMP_FORMATS) {
        try {
            return java.time.OffsetDateTime.parse(raw, format).toInstant().toEpochMilli()
        } catch (_: Exception) {
            // Try the next spelling; only exhaustion is an answer.
        }
    }
    return null
}

/**
 * Accepted spellings, most specific first: the live `+0000`, the RFC-style
 * `Z`, and the colon form. [java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME]
 * covers fractional seconds if the API ever adds them.
 */
private val XUMO_TIMESTAMP_FORMATS: List<DateTimeFormatter> = listOf(
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ"),
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"),
    DateTimeFormatter.ISO_OFFSET_DATE_TIME
)

/**
 * An epoch instant as XMLTV spells it: `yyyyMMddHHmmss +0000`.
 *
 * The API's own values end in a compact `+0000`, and the importer's parser
 * accepts that spelling too - but this normalizes every value to UTC so a
 * response that ever ships a real offset cannot land a program in the wrong
 * hour by being passed through verbatim.
 */
internal fun xmltvTimestamp(epochMs: Long): String =
    XMLTV_TIMESTAMP.format(Instant.ofEpochMilli(epochMs))

private val XMLTV_TIMESTAMP: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(ZoneOffset.UTC)

/**
 * XML text escaping for the values Xumo supplies.
 *
 * The importer tolerates unescaped `&` through [XmltvEntitySanitizingReader],
 * but tolerating it is not the same as emitting it: a title containing `<` would
 * otherwise end the element early and truncate the programme.
 */
internal fun xmlEscape(value: String): String {
    if (value.none { it == '&' || it == '<' || it == '>' || it == '"' || it == '\'' }) return value
    val out = StringBuilder(value.length + 16)
    value.forEach { character ->
        when (character) {
            '&' -> out.append("&amp;")
            '<' -> out.append("&lt;")
            '>' -> out.append("&gt;")
            '"' -> out.append("&quot;")
            '\'' -> out.append("&apos;")
            else -> out.append(character)
        }
    }
    return out.toString()
}
