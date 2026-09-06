package io.github.tonyxmelon.aisudoku.recognize

import io.github.tonyxmelon.aisudoku.vision.GrayImage
import io.github.tonyxmelon.aisudoku.vision.OpenCvNatives
import org.opencv.core.CvType
import org.opencv.core.Mat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Gathering the pieces of a broken digit, and refusing the pieces of an erased one.
 *
 * The rule this guards was measured on the corpus - 63 digits wrong on unseen pages to 45
 * - but the corpus photographs are not committed, so on CI those measurements skip and the
 * rule would travel unguarded. These cells are drawn here, out of rectangles, so the rule
 * is checked wherever the tests run.
 *
 * What it has to get right is one distinction. A threshold that breaks a stroke off a
 * digit leaves a piece written in the same pen; a rubbed-out pencil mark under an answer
 * leaves one that is fainter. Gathering by nearness alone gathers both, which was measured
 * and was worse - 74 wrong against 63 - because the erasure page is full of ghosts sitting
 * right against the answers written over them.
 */
class WholeGlyphTest {

    private val paper = 230.toByte()
    private val pen = 60.toByte()

    /** Faint enough to be ink, and far too faint to be the same pen: contrast 60 of 170. */
    private val ghost = 170.toByte()

    private val side = 60

    private fun cell(vararg marks: Mark): GrayImage {
        val pixels = ByteArray(side * side) { paper }
        for (mark in marks) {
            for (y in mark.top until mark.top + mark.height) {
                for (x in mark.left until mark.left + mark.width) {
                    pixels[y * side + x] = mark.shade
                }
            }
        }
        return GrayImage(side, side, pixels)
    }

    private class Mark(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val shade: Byte,
    )

    /** The body of the digit: the largest piece, and the one every measurement comes from. */
    private fun stem() = Mark(left = 22, top = 15, width = 5, height = 31, shade = pen)

    /**
     * A stroke sitting just above the stem, close enough to be part of the same glyph.
     *
     * One pixel of paper between the two, so they are separate pieces of ink rather than
     * one - which is exactly what a threshold does to a lightly written 7 or 5.
     */
    private fun crossbar(shade: Byte) =
        Mark(left = 16, top = 11, width = 15, height = 3, shade = shade)

    private fun inspect(image: GrayImage): CellInk {
        OpenCvNatives.ensureLoaded { nu.pattern.OpenCV.loadShared() }
        return CellAnalyzer.inspect(listOf(image)).single()
            ?: error("no ink found in a cell that was drawn with ink in it")
    }

    @Test
    fun `a stroke broken off the digit is gathered back into the picture`() {
        val alone = inspect(cell(stem()))
        val broken = inspect(cell(stem(), crossbar(pen)))

        val inkAlone = alone.normalised.count { it > 0.5f }
        val inkBroken = broken.normalised.count { it > 0.5f }
        assertTrue(
            inkBroken > inkAlone,
            "the crossbar should reach the classifier: $inkBroken pixels against $inkAlone",
        )
    }

    @Test
    fun `the ghost of an erased mark is left out of the picture`() {
        val alone = inspect(cell(stem()))
        val haunted = inspect(cell(stem(), crossbar(ghost)))

        // Identical, not merely similar: a piece that fails the same-ink test contributes
        // nothing at all to what is drawn, so the bitmap is the one the stem makes alone.
        assertContentEquals(
            alone.normalised.toTypedArray(),
            haunted.normalised.toTypedArray(),
            "a faint mark beside the digit must not change the picture the classifier sees",
        )
    }

    @Test
    fun `a faint mark is still found, so it is refused rather than missed`() {
        // Without this the test above would pass for the wrong reason: a ghost nobody
        // detected is not a ghost anybody declined to gather. So it is asked of the
        // blob finder directly, which sees every piece of ink whatever is later made of it.
        OpenCvNatives.ensureLoaded { nu.pattern.OpenCV.loadShared() }
        val image = cell(stem(), crossbar(ghost))
        val mat = Mat(image.height, image.width, CvType.CV_8UC1)
            .also { it.put(0, 0, image.pixels) }
        val blobs = CellAnalyzer.findBlobs(mat, image)

        assertEquals(2, blobs.size, "both the stem and the faint mark are ink")
        val faint = blobs.minBy { it.area }
        val stem = blobs.maxBy { it.area }
        assertTrue(
            faint.contrast < stem.contrast * 0.80,
            "the mark must be too faint to be the same pen: ${faint.contrast} of ${stem.contrast}",
        )
    }

    @Test
    fun `the measurements come from the largest piece, never from the gathering`() {
        val alone = inspect(cell(stem()))
        val broken = inspect(cell(stem(), crossbar(pen)))

        // Only the picture is gathered. Measuring the union instead was tried and cost the
        // newsprint pages 57 wrong to 107, because the print-and-handwriting bands are
        // built on exactly these numbers.
        assertEquals(alone.blob.height, broken.blob.height, "height must be the stem's")
        assertEquals(alone.blob.top, broken.blob.top, "the top must be the stem's")
        assertEquals(alone.blob.heightRatio, broken.blob.heightRatio, absoluteTolerance = 1e-9)
    }
}
