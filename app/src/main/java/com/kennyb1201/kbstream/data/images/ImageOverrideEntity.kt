package com.kennyb1201.kbstream.data.images

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One image the viewer chose for a title, in place of the one TMDB offers.
 *
 * The Media row's backdrop and poster pages are galleries of the artwork TMDB
 * holds for a title, and until this existed the only thing a viewer could do
 * with a still they liked was look at it: the hero, the streams screen, the
 * Home hero and the landscape cards all drew whatever the detail payload
 * happened to carry. A long press on a card now writes the choice here and the
 * surfaces read it back.
 *
 * [key] is the identity, and it is composed rather than synthetic so that a
 * lookup is one indexed hit on the primary key: `"${tmdbId}:${type}:${screen}"`
 * - the title, which KIND of art (a backdrop or a poster) and which surface it
 * was chosen for. The three columns beside it carry the same values spelled
 * out, so a row is readable (and clearable) without parsing the key.
 *
 * [imagePath] is TMDB's own `file_path` ("/abc123.jpg"), NOT a URL: every
 * surface builds its URL from the path with the size THAT surface wants
 * ([com.kennyb1201.kbstream.data.tmdb.tmdbImage]), which is what lets one
 * choice serve a 780-wide card and a fullscreen view of the same still.
 */
@Entity(tableName = "image_overrides")
data class ImageOverrideEntity(
    /** `"${tmdbId}:${type}:${screen}"` - see [ImageOverrides.key]. */
    @PrimaryKey val key: String,
    /** The TMDB id of the title the art belongs to. */
    val tmdbId: Int,
    /** [ImageOverrides.TYPE_BACKDROP] or [ImageOverrides.TYPE_POSTER]. */
    val type: String,
    /** Which surface the choice was made for - see [ImageOverrides]. */
    val screen: String,
    /** TMDB's `file_path` of the chosen image. */
    val imagePath: String
)
