package com.kennyb1201.kbstream.data.tmdb

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The header entity-logo cache's WIRING, in `TmdbRepository`.
 *
 * `EntityLogoCacheTest` pins the keying and the TTLs as pure rules. What a JVM
 * test cannot do is stand up the repository (Room + Retrofit + an Android
 * context), so the layer discipline is pinned here by source contract:
 *
 *  - the cache is read before anything is fetched, and the fetch never runs
 *    under the mutex (the hero cache's recorded mistake: holding it across a
 *    fetch serialized every lookup in the process behind a prefetch queue);
 *  - concurrent callers for one key share one deferred, completed before its
 *    marker is dropped;
 *  - disk writes go through `cacheJson`, the single write path that enforces the
 *    shared 64 MB budget;
 *  - a FAILED attempt caches nothing, while a proven logo-less answer does - the
 *    distinction that stops an outage being pinned for a day;
 *  - and the five fallback steps are still the five, in order.
 */
class EntityLogoCacheWiringContractTest {

    private val repository by lazy { repositorySource() }

    /** `getEntityLogoUrls`: the cache wrapper, up to the extraction of the steps. */
    private fun wrapper(): String = section(
        repository,
        "    suspend fun getEntityLogoUrls(",
        "/**\n     * One entity-logo resolution: the five fallback steps, unchanged."
    )

    /** The extracted five-step body. */
    private fun resolution(): String = section(
        repository,
        "    private suspend fun resolveEntityLogoUrls(",
        "/**\n     * Whether any step of ONE entity-logo resolution failed."
    )

    @Test
    fun `the api key still short-circuits ahead of the cache read`() {
        val wrapper = wrapper()
        val guard = wrapper.indexOf("if (apiKey.isBlank()) return emptyList()")
        val cacheRead = wrapper.indexOf("entityLogoMemory[key]")
        assertTrue("the guard must exist", guard >= 0)
        assertTrue("the cache must be read after it", cacheRead > guard)
    }

    @Test
    fun `a hit answers from the memo, then the disk row, before any fetch`() {
        val wrapper = wrapper()
        assertTrue(
            "the session memo is consulted first",
            wrapper.contains("entityLogoMemory[key]?.let { (fetchedAt, urls) ->") &&
                wrapper.contains("if (entityLogoMemoryEntryIsFresh(now - fetchedAt)) return urls")
        )
        assertTrue(
            "then the shared disk table, under the same key",
            wrapper.contains("tmdbJsonCacheDao.getByKey(key)") &&
                wrapper.contains("entityLogoDiskEntryIsFresh(now - diskCached.updatedAt, parsed)")
        )
        assertTrue(
            "and only then does a fetch start",
            wrapper.indexOf("getByKey(key)") < wrapper.indexOf("val attempt = EntityLogoAttempt()")
        )
    }

    @Test
    fun `one fetch per key, and the fetch runs outside the mutex`() {
        val wrapper = wrapper()
        assertTrue(
            "a concurrent caller joins the pending fetch",
            wrapper.contains("entityLogoInFlight[key]?.let { pending ->") &&
                wrapper.contains("sharedFetch?.let { return it.await() }")
        )
        assertTrue(
            "the fetch itself is registered, then performed after the lock is released",
            wrapper.indexOf("entityLogoInFlight[key] = registered") <
                wrapper.indexOf("val attempt = EntityLogoAttempt()")
        )
        val lockBody = section(wrapper, "entityLogoMutex.withLock {", "sharedFetch?.let { return")
        assertFalse(
            "the network resolution must never run under the mutex",
            lockBody.contains("resolveEntityLogoUrls(")
        )
        assertTrue(
            "the deferred is completed before its in-flight marker is dropped",
            wrapper.contains("deferred.complete(resolved)") &&
                wrapper.indexOf("deferred.complete(resolved)") <
                wrapper.indexOf("entityLogoInFlight.remove(key)")
        )
    }

    @Test
    fun `a failed attempt caches nothing, a proven logo-less answer does`() {
        val wrapper = wrapper()
        assertTrue(
            "the attempt tracker is what tells the two apart",
            wrapper.contains("val cacheable = !attempt.failed")
        )
        assertTrue(
            "the memo write is behind it",
            wrapper.contains("if (cacheable) {\n                    entityLogoMemory[key] = fetchedAt to resolved")
        )
        assertTrue(
            "and so is the disk write",
            wrapper.contains("if (cacheable) {\n            runCatchingCancellable {\n                cacheJson(")
        )
        assertTrue(
            "an escaping throw marks the attempt failed rather than caching the miss",
            wrapper.contains("catch (error: Throwable) {\n            attempt.failed = true")
        )

        val resolution = resolution()
        listOf(
            "entityLogoUrls(entityId, company = spaces.first().isCompany, attempt = attempt)",
            "entityLogoUrls(originalsCompanyId, company = true, attempt = attempt)",
            "watchProviderLogoUrl(id, attempt)",
            "companyLogosByName(label, setOfNotNull(entityId, originalsCompanyId), attempt)"
        ).forEach { step ->
            assertTrue("$step must pass the tracker", resolution.contains(step))
        }
        val entityLogos = section(
            repository,
            "    private suspend fun entityLogoUrls(",
            "/**\n     * Promotes a wide wordmark above a square-ish icon."
        )
        assertTrue(
            "a failed images call is a failure, not a logo-less brand",
            entityLogos.contains(".onFailure { attempt.failed = true }")
        )
    }

    @Test
    fun `the disk write goes through the shared budgeted path, under the versioned key`() {
        val wrapper = wrapper()
        assertTrue(
            "cacheJson is the single write path (it enforces the 64 MB budget)",
            wrapper.contains("cacheJson(\n                    key,") ||
                wrapper.contains("cacheJson(")
        )
        assertFalse(
            "no direct DAO upsert may appear in this path",
            wrapper.contains("tmdbJsonCacheDao.upsert")
        )
        assertTrue(
            "the memory memo is pruned after a write, at the shared cap",
            wrapper.contains("pruneEntityLogoMemory(fetchedAt)") &&
                repository.contains("if (entityLogoMemory.size <= ENTITY_LOGO_MEMORY_MAX_ENTRIES) return")
        )
    }

    @Test
    fun `the five fallback steps are untouched and still in order`() {
        val resolution = resolution()
        val ownEntity = resolution.indexOf("entityLogoUrls(entityId, company = spaces.first().isCompany")
        val originals = resolution.indexOf("entityLogoUrls(originalsCompanyId, company = true")
        val twin = resolution.indexOf("entityLogoUrls(\n                    entityId,\n                    company = twinSpace.isCompany")
        val provider = resolution.indexOf("watchProviderLogoUrl(id, attempt)")
        val nameSearch = resolution.indexOf("companyLogosByName(label")

        assertTrue("own entity", ownEntity >= 0)
        assertTrue("originals company", originals > ownEntity)
        assertTrue("twin space", twin > originals)
        assertTrue("watch provider", provider > twin)
        assertTrue("company-name search", nameSearch > provider)
        assertTrue(
            "and the twin still needs a verified name match, which is the rule that " +
                "stops an unrelated studio's mark landing on a brand",
            resolution.contains("if (entityNamesMatch(brand(), twinName)) addAll(twin)")
        )
    }

    // ── sources ─────────────────────────────────────────────────────────────

    private fun section(src: String, from: String, to: String): String {
        val start = src.indexOf(from)
        assertTrue("missing landmark: ${from.take(60)}", start >= 0)
        val rest = src.substring(start + from.length)
        val end = rest.indexOf(to)
        assertTrue("missing landmark after ${from.take(40)}", end >= 0)
        return rest.substring(0, end)
    }

    private fun repositorySource(): String {
        val file = File(findSourceRoot(), REPOSITORY)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun findSourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private companion object {
        const val REPOSITORY =
            "com/kennyb1201/kbstream/data/tmdb/TmdbRepository.kt"
    }
}
