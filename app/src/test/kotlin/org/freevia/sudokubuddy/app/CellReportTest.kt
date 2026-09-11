package org.freevia.sudokubuddy.app

import org.freevia.sudokubuddy.recognize.CellReading
import org.freevia.sudokubuddy.recognize.Ink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the app tells the user about a square it read.
 *
 * The point of showing the classifier's own numbers is that a confident misread and a
 * coin flip want different responses - one is worth a photograph to the developer, the
 * other is just a hard cell to correct. That distinction is only useful if the numbers
 * shown are the ones the classifier actually produced.
 */
class CellReportTest {

    private fun reading(ink: Ink, vararg probabilities: Float) =
        CellReading(
            index = 0,
            ink = ink,
            probabilities = if (probabilities.isEmpty()) null else probabilities,
            heightRatio = 0.5,
            darkness = 80.0,
        )

    @Test
    fun `the digit reported is the most likely one`() {
        val p = FloatArray(9).also { it[4] = 0.7f; it[2] = 0.2f }
        val report = CellReport.of(reading(Ink.PRINTED, *p))

        assertEquals(5, report.digit, "index 4 is the digit 5")
        assertEquals(0.7f, report.confidence)
        assertEquals(3, report.runnerUp, "index 2 is the digit 3")
        assertEquals(0.2f, report.runnerUpConfidence)
    }

    @Test
    fun `a cell with no ink reports no digit and claims no doubt`() {
        val report = CellReport.of(reading(Ink.NONE))
        assertNull(report.digit)
        assertNull(report.runnerUp)
        assertTrue(report.describe().contains("empty"))
    }

    @Test
    fun `pencil marks are named as marks rather than as a digit`() {
        val report = CellReport.of(reading(Ink.MARK))
        assertTrue(report.describe().contains("candidate marks"))
    }

    @Test
    fun `print and handwriting are described differently`() {
        val p = FloatArray(9).also { it[0] = 0.9f; it[1] = 0.05f }
        assertTrue(CellReport.of(reading(Ink.PRINTED, *p)).describe().contains("printed"))
        assertTrue(CellReport.of(reading(Ink.ANSWER, *p)).describe().contains("handwritten"))
    }

    @Test
    fun `a second guess is only offered when there was a real contest`() {
        val contested = FloatArray(9).also { it[0] = 0.55f; it[1] = 0.40f }
        assertTrue(CellReport.of(reading(Ink.ANSWER, *contested)).secondGuess()!!.contains("2"))

        // Below two percent there was no contest, and naming a runner-up would invent one.
        val certain = FloatArray(9).also { it[0] = 0.995f; it[1] = 0.004f }
        assertNull(CellReport.of(reading(Ink.ANSWER, *certain)).secondGuess())
    }

    @Test
    fun `confidence is shown as a whole percentage`() {
        val p = FloatArray(9).also { it[7] = 0.826f; it[1] = 0.10f }
        assertTrue(CellReport.of(reading(Ink.PRINTED, *p)).describe().contains("83%"))
    }

    @Test
    fun `a square the classifier was only guessing at is put as a question`() {
        // The fault this exists for: a printed digit reported at 33% sure. That is not a
        // reading, it is the best of nine bad options, and stating it as one and then
        // undermining it with the number in the same sentence is the worst of both.
        val p = FloatArray(9).also { it[4] = 0.33f; it[2] = 0.30f }
        val report = CellReport.of(reading(Ink.PRINTED, *p))

        assertTrue(report.onlyAGuess, "a third sure is a guess")
        val text = report.describe()
        assertTrue(text.contains("Not read with any confidence"), text)
        assertTrue(text.contains("set this square yourself"), text)
        assertTrue(!text.contains("Read as a printed"), "it must not state it as read: $text")
    }

    @Test
    fun `a square read confidently is still stated plainly`() {
        val p = FloatArray(9).also { it[4] = 0.98f }
        val report = CellReport.of(reading(Ink.PRINTED, *p))

        assertTrue(!report.onlyAGuess)
        assertEquals("Read as a printed 5, 98% sure.", report.describe())
    }

    @Test
    fun `the line between a reading and a guess sits where it is documented`() {
        fun sureness(value: Float) =
            CellReport.of(reading(Ink.PRINTED, *FloatArray(9).also { it[0] = value })).onlyAGuess

        assertTrue(sureness(CellReport.SURE_ENOUGH_TO_SAY - 0.01f), "below is a guess")
        assertTrue(!sureness(CellReport.SURE_ENOUGH_TO_SAY), "at the line it is a reading")
    }
}
