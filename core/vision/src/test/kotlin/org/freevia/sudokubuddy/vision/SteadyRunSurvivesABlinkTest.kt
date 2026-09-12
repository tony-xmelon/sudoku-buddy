package org.freevia.sudokubuddy.vision

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Holding the camera still, when one frame in a handful comes back empty.
 *
 * Reported from the phone: the outline blinking between a grid and nothing, and automatic
 * capture never firing however still the camera was held. A run of good frames was thrown
 * away entirely by any one frame in which the locator found nothing - and it does not take
 * a moving camera to produce one of those, only a breath, a reflection, or the autofocus
 * hunting for a moment.
 *
 * A couple of blank frames are now forgiven. What is not forgiven is a camera that has
 * actually left the page, which misses far more than a couple in a row.
 */
class SteadyRunSurvivesABlinkTest {

    @BeforeTest
    fun setUp() {
        CorpusFixtures.requireCorpus()
        OpenCvNatives.ensureLoaded { nu.pattern.OpenCV.loadShared() }
    }

    /** A frame the advisor has nothing to complain about: the page, with room around it. */
    private fun framed(photo: GrayImage): GrayImage {
        val inset = Degrade.shrink(photo, 560.0 / photo.height)
        val width = (inset.width * 1.35).toInt()
        val height = (inset.height * 1.35).toInt()
        val pixels = ByteArray(width * height) { 200.toByte() }
        val left = (width - inset.width) / 2
        val top = (height - inset.height) / 2
        for (y in 0 until inset.height) {
            System.arraycopy(
                inset.pixels, y * inset.width,
                pixels, (top + y) * width + left, inset.width,
            )
        }
        return GrayImage(width, height, pixels)
    }

    /** Nothing to see: an even grey, which the locator reports no grid in. */
    private fun blank(like: GrayImage) =
        GrayImage(like.width, like.height, ByteArray(like.width * like.height) { 200.toByte() })

    /** The first corpus page the advisor is willing to capture at all, and its frame. */
    private fun acceptableFrame(): GrayImage? {
        for (file in CorpusFixtures.photos) {
            val frame = framed(CorpusFixtures.load(file))
            val advisor = FramingAdvisor()
            var ready = false
            repeat(12) { if (advisor.advise(frame).readyToCapture) ready = true }
            if (ready) return frame
        }
        return null
    }

    @Test
    fun `a blank frame or two does not undo a steady run`() {
        val frame = acceptableFrame()
        assertTrue(frame != null, "no corpus page framed cleanly enough to be captured at all")
        frame!!

        // How many good frames it takes from cold, so the test asks for exactly that many
        // rather than assuming the advisor's own number.
        val warm = FramingAdvisor()
        var needed = 0
        while (needed < 20 && !warm.advise(frame).readyToCapture) needed++
        needed++

        val advisor = FramingAdvisor()
        repeat(needed - 1) { advisor.advise(frame) }
        advisor.advise(blank(frame))
        advisor.advise(blank(frame))
        assertTrue(
            advisor.advise(frame).readyToCapture,
            "two blank frames in the middle of a steady run stopped the shutter firing",
        )
    }

    @Test
    fun `a camera that has really left the page starts again`() {
        val frame = acceptableFrame()
        assertTrue(frame != null, "no corpus page framed cleanly enough to be captured at all")
        frame!!

        val advisor = FramingAdvisor()
        repeat(12) { advisor.advise(frame) }
        repeat(5) { advisor.advise(blank(frame)) }
        assertTrue(
            !advisor.advise(frame).readyToCapture,
            "the run should have been given up after the page was lost for five frames",
        )
    }
}
