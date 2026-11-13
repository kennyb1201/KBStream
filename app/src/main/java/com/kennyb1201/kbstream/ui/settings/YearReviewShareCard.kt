package com.kennyb1201.kbstream.ui.settings

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.FileProvider
import com.kennyb1201.kbstream.data.history.YearReview
import java.io.File
import java.io.FileOutputStream

/**
 * The shareable summary card (spec 2026-11-13, F2).
 *
 * Drawn from the numbers the screen already computed - no new read, no server,
 * no account. The bitmap carries the year, the approximate hours, the three
 * counters and at most three title names; it deliberately carries NO profile
 * name and no per-title history beyond those three, so nothing leaves the device
 * that the viewer did not choose to share, and the system share sheet is what
 * actually sends it.
 *
 * Rendered with [Canvas] rather than by capturing the Compose tree: a capture
 * would need the screen to be laid out at share time, and the card has to be a
 * fixed, predictable image (1080x1350 - the shape a story or a chat preview
 * wants).
 */
internal object YearReviewShareCard {

    private const val WIDTH = 1080
    private const val HEIGHT = 1350

    private const val BACKGROUND = 0xFF0B0B10.toInt()
    private const val TEXT_HIGH = 0xFFF3EFE4.toInt()
    private const val TEXT_LOW = 0xFF9C9AA6.toInt()

    /** The card as a bitmap: dark page, the accent the app is wearing, three titles at most. */
    fun bitmap(review: YearReview, hoursLabel: String, accentArgb: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BACKGROUND)

        val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentArgb
            strokeWidth = 12f
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(90f, 150f, 250f, 150f, accent)

        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_LOW
            textSize = 34f
            letterSpacing = 0.24f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_HIGH
            textSize = 190f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val heading = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentArgb
            textSize = 64f
            letterSpacing = 0.18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_HIGH
            textSize = 48f
        }
        val low = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_LOW
            textSize = 34f
        }

        canvas.drawText("KBSTREAM", 270f, 162f, label)
        canvas.drawText(review.year.toString(), 90f, 380f, big)
        canvas.drawText("IN REVIEW", 100f, 470f, heading)

        // The hours, with the approximation mark the screen uses: the number is
        // finished runtime plus saved positions, not a stopwatch (see YearInReview).
        canvas.drawText("≈ $hoursLabel", 100f, 660f, Paint(big).apply { textSize = 120f })

        var y = 800f
        canvas.drawText(review.episodesLine, 100f, y, body)
        y += 76f
        canvas.drawText(review.moviesLine, 100f, y, body)
        y += 76f
        canvas.drawText(review.showsLine, 100f, y, body)

        y += 110f
        canvas.drawText("TOP SHOWS", 100f, y, label)
        y += 60f
        review.topShows.take(3).forEach { show ->
            canvas.drawText(show.name.take(28), 100f, y, body)
            canvas.drawText("${show.episodes} ep", 940f, y, low)
            y += 66f
        }

        canvas.drawText(
            "Approximate: one row per title, no per-session time kept.",
            90f,
            HEIGHT - 90f,
            low
        )

        return bitmap
    }

    private val YearReview.episodesLine: String get() = "$episodesFinished episodes finished"
    private val YearReview.moviesLine: String get() = "$moviesFinished movies finished"
    private val YearReview.showsLine: String get() = "$showsFinished shows finished"

    /**
     * Writes the card to the shared cache directory and hands it to the system
     * share sheet.
     *
     * Returns false when the image could not be written - the screen then says
     * nothing happened rather than pretending it shared. The intent is a plain
     * ACTION_SEND with a one-shot read grant on that file, so the receiving app
     * gets the card and nothing else from the app's storage.
     */
    fun share(
        context: Context,
        review: YearReview,
        hoursLabel: String,
        accentArgb: Int
    ): Boolean {
        val written = runCatching {
            val directory = File(context.cacheDir, "share").apply { mkdirs() }
            val file = File(directory, "year-review-${review.year}.png")
            FileOutputStream(file).use { out ->
                bitmap(review, hoursLabel, accentArgb).compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            file
        }.getOrNull() ?: return false

        val uri = runCatching {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.updateprovider",
                written
            )
        }.getOrNull() ?: return false

        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Share your ${review.year} in review").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(chooser)
            true
        }.getOrDefault(false)
    }
}
