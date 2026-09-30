package com.kennyb1201.kbstream.data

import com.kennyb1201.kbstream.data.addon.AddonManifest
import com.kennyb1201.kbstream.data.addon.InstalledAddon
import com.kennyb1201.kbstream.data.addon.Meta
import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.data.addon.StreamResponse
import com.kennyb1201.kbstream.data.badges.StreamBadgeGroup
import com.kennyb1201.kbstream.data.badges.StreamBadgePack
import com.kennyb1201.kbstream.data.kb.KBCollectionProfile
import com.kennyb1201.kbstream.data.kb.KBHomeOrder
import com.kennyb1201.kbstream.data.simkl.SimklTokenResponse
import com.kennyb1201.kbstream.data.tmdb.TmdbDetail
import com.kennyb1201.kbstream.data.tmdb.TmdbHeroArtworkRepository
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The audit finding this pins: every model in this app is annotated
 * `@JsonClass(generateAdapter = true)`, but nothing was generating anything —
 * app/build.gradle.kts had no `moshi-kotlin-codegen` processor, so all 129
 * annotations were inert and every parse fell through to kotlin-reflect. That
 * was both slower on the hot paths (TMDB, addon manifests and streams, Simkl,
 * MDBList) and the reason app/proguard-rules.pro had to keep the whole
 * `data.**` tree member-for-member: reflection needs the members.
 *
 * A compile cannot tell you that the processor ran and Moshi then found what it
 * wrote, because Moshi resolves a model's adapter by BUILDING ITS NAME at
 * runtime (`<Model>JsonAdapter`, with `$` in a nested name replaced by `_`) and
 * calling `Class.forName`. That indirection is the thing app/proguard-rules.pro
 * now exists to protect, and it is what is asserted here — on the JVM, in the
 * same `testDebugUnitTest` gate as everything else, so a release-only break
 * shows up as a failing test rather than as a slower (or crashing) APK.
 *
 * Three separate claims, because they fail for different reasons:
 *
 *  - a Moshi with NO reflective factory at all can still adapt an annotated
 *    model, which is only possible if a generated adapter exists and was found;
 *  - the name Moshi computes resolves to a real adapter class, which is the
 *    invariant the R8 rules have to preserve;
 *  - an unannotated type is answered by the reflective fallback and by nothing
 *    else, so the fallback this app relies on is still reachable.
 */
class MoshiCodegenTest {

    /**
     * Deliberately has no `KotlinJsonAdapterFactory`: without a generated
     * adapter, adapting an annotated Kotlin data class here throws
     * "Cannot serialize Kotlin type ...". That makes it the strictest possible
     * statement that codegen is wired up.
     */
    private val codegenOnly = Moshi.Builder().build()

    /** Built the way every Moshi instance in the app is built. */
    private val appLike = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    @Test
    fun `a Moshi with no reflective factory still adapts an annotated model`() {
        val adapter = codegenOnly.adapter(StreamBadgeGroup::class.java)
        val json = adapter.toJson(
            StreamBadgeGroup(id = "hdr", name = "HDR", color = "#ffcc00")
        )
        assertEquals(
            """{"id":"hdr","name":"HDR","color":"#ffcc00"}""",
            json
        )
        assertEquals(
            StreamBadgeGroup("hdr", "HDR", "#ffcc00"),
            adapter.fromJson(json)
        )
    }

    /**
     * The lookup name itself. [Types.generatedJsonAdapterName] is the public
     * half of what `KotlinJsonAdapterFactory` and media3-free JSON code call;
     * if a model is ever renamed, moved, or made `private` (Moshi refuses to
     * generate for a private type), this is where it surfaces.
     */
    @Test
    fun `every model has a generated adapter under the name Moshi builds for it`() {
        val models = listOf(
            Meta::class.java,
            MetaPreview::class.java,
            Stream::class.java,
            StreamResponse::class.java,
            AddonManifest::class.java,
            InstalledAddon::class.java,
            TmdbDetail::class.java,
            SimklTokenResponse::class.java,
            KBHomeOrder::class.java,
            KBCollectionProfile::class.java,
            StreamBadgePack::class.java,
            StreamBadgeGroup::class.java
        )
        models.forEach { model ->
            // Throws ClassNotFoundException, naming the class it wanted, when
            // the processor did not run or its output drifted.
            val adapterClass = Class.forName(Types.generatedJsonAdapterName(model))
            assertTrue(
                "${adapterClass.name} is not a JsonAdapter",
                JsonAdapter::class.java.isAssignableFrom(adapterClass)
            )
        }
    }

    /**
     * The nested case, and the reason the assertion is on a class name rather
     * than on what `moshi.adapter(...)` returns: for a nested model Moshi looks
     * for the flattened `Outer_InnerJsonAdapter`, and the resolved adapter is
     * the generated one wrapped by `nullSafe()` — so the object Moshi hands
     * back is not the generated class itself. [TmdbHeroArtworkRepository] is
     * also the one place where a wire model had to be widened from `private` to
     * `internal` to satisfy the generator.
     */
    @Test
    fun `a nested model resolves to its flattened generated adapter`() {
        val nested = TmdbHeroArtworkRepository.TmdbImagesResponse::class.java
        val adapterClass = Class.forName(Types.generatedJsonAdapterName(nested))
        assertEquals(
            "TmdbHeroArtworkRepository_TmdbImagesResponseJsonAdapter",
            adapterClass.simpleName
        )

        val parsed = appLike
            .adapter(nested)
            .fromJson("""{"backdrops":[{"file_path":"/a.jpg","width":1920}]}""")
        assertEquals("/a.jpg", parsed?.backdrops?.first()?.filePath)
    }

    @Test
    fun `a type with no annotation is only answered by the reflective fallback`() {
        val withoutFallback = runCatching {
            codegenOnly.adapter(PlainUnannotated::class.java)
        }.exceptionOrNull()
        assertTrue(
            "an unannotated Kotlin type should hit Moshi's reflection guard, " +
                "got $withoutFallback",
            withoutFallback is IllegalArgumentException &&
                withoutFallback.message.orEmpty().contains("Cannot serialize Kotlin type")
        )

        val adapter = appLike.adapter(PlainUnannotated::class.java)
        assertEquals(
            """{"value":"round-trip"}""",
            adapter.toJson(PlainUnannotated("round-trip"))
        )
    }
}

/**
 * Deliberately NOT annotated: the stand-in for the only case
 * `KotlinJsonAdapterFactory` still covers. It has to stay outside the generator's
 * reach for the test above to mean anything.
 */
class PlainUnannotated(val value: String)
