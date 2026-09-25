package com.kennyb1201.kbstream.data.db

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A profile-scoped database swap is a race, not a failure, and getting the two
 * confused is what this file pins down.
 *
 * Too narrow — treating a swap as a real failure — is the reported bug: the
 * guide import gives up and the screen holds an error banner with the guide
 * still empty ("IllegalStateException: The connection pool has been closed").
 * Too broad — retrying a real failure — hides network errors and malformed
 * guides behind three attempts and a delay, and in the worst case retries a
 * cancelled read back to life.
 */
class DatabaseSwapRetryTest {

    /** Matches by simple name in production; here too, so the branch is covered. */
    private class SQLiteDatabaseLockedException(message: String?) : RuntimeException(message)

    @Test
    fun `the room closed-pool message reads as a swap`() {
        assertTrue(
            isTransientDatabaseError(
                IllegalStateException("The connection pool has been closed.")
            )
        )
    }

    @Test
    fun `an already-closed handle and a busy database read as a swap`() {
        assertTrue(
            isTransientDatabaseError(
                IllegalStateException("attempt to re-open an already-closed object")
            )
        )
        assertTrue(isTransientDatabaseError(IllegalStateException("database is locked")))
        assertTrue(isTransientDatabaseError(RuntimeException("code 5 SQLITE_BUSY")))
    }

    @Test
    fun `a locked database counts by type even when the message says nothing`() {
        assertTrue(isTransientDatabaseError(SQLiteDatabaseLockedException(null)))
    }

    @Test
    fun `a swap symptom wrapped in a cause is still recognised`() {
        val wrapped = RuntimeException(
            "batch write failed",
            IllegalStateException("The connection pool has been closed.")
        )
        assertTrue(isTransientDatabaseError(wrapped))
    }

    @Test
    fun `a real failure is not a swap`() {
        assertFalse(isTransientDatabaseError(IOException("connection reset by peer")))
        assertFalse(isTransientDatabaseError(IllegalArgumentException("malformed guide")))
        assertFalse(isTransientDatabaseError(IllegalStateException("profile changed during guide import")))
    }

    @Test
    fun `the cause walk stops at a fixed depth`() {
        // Beyond the cap the walk gives up rather than following a deep or
        // cyclic chain forever; the symptom no longer counts as transient.
        var deep: Throwable = IllegalStateException("The connection pool has been closed.")
        repeat(10) { deep = RuntimeException("layer ${it + 1}", deep) }
        assertFalse(isTransientDatabaseError(deep))
    }

    @Test
    fun `a swap-interrupted operation is re-run and succeeds`() = runBlocking {
        var attempts = 0
        val retries = mutableListOf<Int>()

        val result = withDatabaseSwapRetry(
            attempts = 3,
            delayMs = 1L,
            onRetry = { attempt, _ -> retries += attempt }
        ) {
            attempts++
            if (attempts < 3) throw IllegalStateException("The connection pool has been closed.")
            "imported"
        }

        assertEquals("imported", result)
        assertEquals(3, attempts)
        assertEquals(listOf(1, 2), retries)
    }

    @Test
    fun `the last attempt rethrows instead of looping`() = runBlocking {
        var attempts = 0
        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                withDatabaseSwapRetry(attempts = 3, delayMs = 1L) {
                    attempts++
                    throw IllegalStateException("The connection pool has been closed.")
                }
            }
        }
        assertEquals(3, attempts)
        assertTrue(thrown.message.orEmpty().contains("connection pool"))
    }

    @Test
    fun `a real failure is not retried at all`() = runBlocking {
        var attempts = 0
        assertThrows(IOException::class.java) {
            runBlocking {
                withDatabaseSwapRetry(attempts = 3, delayMs = 1L) {
                    attempts++
                    throw IOException("connection reset by peer")
                }
            }
        }
        assertEquals(1, attempts)
    }

    @Test
    fun `cancellation is never retried`() = runBlocking {
        var attempts = 0
        assertThrows(CancellationException::class.java) {
            runBlocking {
                withDatabaseSwapRetry(attempts = 3, delayMs = 1L) {
                    attempts++
                    throw CancellationException("collector went away")
                }
            }
        }
        assertEquals(1, attempts)
    }

    @Test
    fun `a flow that lost its database is re-collected`() = runBlocking {
        var collections = 0
        val lines = flow {
            collections++
            if (collections < 2) throw IllegalStateException("The connection pool has been closed.")
            emit(listOf("NOW", "NEXT"))
        }.retryOnDatabaseSwap(attempts = 3, delayMs = 1L).toList()

        assertEquals(2, collections)
        assertEquals(listOf(listOf("NOW", "NEXT")), lines)
    }

    @Test
    fun `a non-swap flow failure propagates instead of being retried`() = runBlocking {
        var collections = 0
        val failing = flow<List<String>> {
            collections++
            throw IOException("guide host unreachable")
        }.retryOnDatabaseSwap(attempts = 3, delayMs = 1L)

        assertThrows(IOException::class.java) { runBlocking { failing.toList() } }
        assertEquals(1, collections)
    }

    @Test
    fun `a flow that never settles stops after the attempt budget`() = runBlocking {
        var collections = 0
        val failing = flow<List<String>> {
            collections++
            throw IllegalStateException("database is locked")
        }.retryOnDatabaseSwap(attempts = 3, delayMs = 1L)

        assertThrows(IllegalStateException::class.java) { runBlocking { failing.toList() } }
        assertEquals(3, collections)
    }
}
