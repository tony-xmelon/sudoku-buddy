package org.freevia.sudokubuddy.recognize

import org.freevia.sudokubuddy.vision.CellExtractor
import org.freevia.sudokubuddy.vision.CellGeometry
import org.freevia.sudokubuddy.vision.GrayImage
import org.freevia.sudokubuddy.vision.OpenCvNatives
import java.awt.Font
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reader asked about pages the corpus does not contain.
 *
 * Every figure the corpus produces needs photographs that are not committed, so on CI they
 * skip and only the rules nobody has questioned make the trip. These are drawn, so they run
 * everywhere and their ground truth is exact rather than transcribed.
 *
 * They are deliberately easy in the ways a photograph is hard - no crease, no fold, no lens
 * - and are held to the standard that earns: every printed digit sorted as print and read
 * correctly, whatever the font, the figure style, the weight, the lamp or the hand over it.
 * A photograph is a different question and the corpus is still the only thing that asks it.
 */
class SyntheticGridTest {

    private val givens =
        "53..7...." + "6..195..." + ".98....6." + "8...6...3" + "4..8.3..1" +
            "7...2...6" + ".6....28." + "...419..5" + "....8..79"

    private val solution =
        "534678912" + "672195348" + "198342567" + "859761423" + "426853791" +
            "713924856" + "961537284" + "287419635" + "345286179"

    /** What a finished page has written on it: the solution, less the printed digits. */
    private val written = String(CharArray(81) { if (givens[it] == '.') solution[it] else '.' })

    /** Half solved, which is how a page in a newspaper is usually found. */
    private val halfWritten =
        String(CharArray(81) { if (givens[it] == '.' && it % 2 == 0) solution[it] else '.' })

    private val families = listOf(Font.SANS_SERIF, Font.SERIF, Font.MONOSPACED)

    private fun read(image: GrayImage): Map<Int, CellReading> {
        OpenCvNatives.ensureLoaded { nu.pattern.OpenCV.loadShared() }
        val cells = CellExtractor.extract(image, CellGeometry.evenNinths(SyntheticGrid.SIDE))
        val result = when (val outcome = GridReader().read(cells)) {
            is ReadResult.Accepted -> outcome.readings
            is ReadResult.NeedsConfirmation -> outcome.readings
            is ReadResult.Unreadable -> error("the reader refused a drawn grid: ${outcome.reason}")
        }
        return result.associateBy { it.index }
    }

    private fun checkPrinted(readings: Map<Int, CellReading>, label: String) {
        val wrongKind = (0 until 81).filter {
            givens[it] != '.' && readings[it]?.ink != Ink.PRINTED
        }
        assertTrue(
            wrongKind.isEmpty(),
            "$label: printed digits sorted as ${wrongKind.map { readings[it]?.ink }} at $wrongKind",
        )
        val misread = (0 until 81).filter {
            givens[it] != '.' && readings[it]?.digit != givens[it] - '0'
        }
        assertTrue(
            misread.isEmpty(),
            "$label: printed digits misread at $misread " +
                "(${misread.map { "${givens[it]} read ${readings[it]?.digit}" }})",
        )
    }

    @Test
    fun `an unsolved page reads whatever its figures, font and weight`() {
        for (figures in SyntheticGrid.Figures.entries) {
            for (family in families) {
                for (bold in listOf(false, true)) {
                    val label = "$figures $family bold=$bold"
                    val readings = read(
                        SyntheticGrid.rectified(
                            SyntheticGrid.Page(
                                givens, figures = figures, family = family, bold = bold,
                            )
                        )
                    )
                    checkPrinted(readings, label)
                    assertEquals(
                        30, readings.values.count { it.ink == Ink.PRINTED },
                        "$label: expected thirty printed digits",
                    )
                }
            }
        }
    }

    @Test
    fun `old-style figures are print, not a second hand`() {
        // The fault this whole family of tests exists for: 1 and 2 at x-height are three
        // quarters the height of the tall figures, which is well outside the printed band.
        val readings = read(
            SyntheticGrid.rectified(
                SyntheticGrid.Page(givens, figures = SyntheticGrid.Figures.OLD_STYLE)
            )
        )
        val short = (0 until 81).filter { givens[it] == '1' || givens[it] == '2' }
        assertTrue(short.isNotEmpty(), "the puzzle must contain the short figures")
        for (i in short) {
            assertEquals(Ink.PRINTED, readings[i]?.ink, "cell $i holds a printed ${givens[i]}")
        }
    }

    @Test
    fun `a finished page keeps the pen and the press apart`() {
        for (hand in SyntheticGrid.Hand.entries) {
            val readings = read(
                SyntheticGrid.rectified(
                    SyntheticGrid.Page(givens, answers = written, hand = hand, seed = 7)
                )
            )
            checkPrinted(readings, "solved by a $hand hand")
            val wrong = (0 until 81).filter {
                written[it] != '.' && readings[it]?.ink != Ink.ANSWER
            }
            assertTrue(
                wrong.size <= 2,
                "$hand: answers sorted as ${wrong.map { readings[it]?.ink }} at $wrong",
            )
        }
    }

    @Test
    fun `a page half filled in is still a page of two kinds`() {
        val readings = read(
            SyntheticGrid.rectified(
                SyntheticGrid.Page(
                    givens, answers = halfWritten, hand = SyntheticGrid.Hand.LOOSE, seed = 11,
                )
            )
        )
        checkPrinted(readings, "half solved")
    }

    @Test
    fun `pencilled candidates are not answers`() {
        val readings = read(
            SyntheticGrid.rectified(SyntheticGrid.Page(givens, marks = 0.6, seed = 5))
        )
        checkPrinted(readings, "with candidate marks")
        val mistaken = (0 until 81).filter {
            givens[it] == '.' && readings[it]?.ink == Ink.PRINTED
        }
        assertTrue(mistaken.isEmpty(), "pencil marks taken for print at $mistaken")
    }

    @Test
    fun `a firm candidate list is not an answer, on a page written large`() {
        // The failure four photographs of one booklet page produced, and the only kind they
        // produced: a candidate list pressed nearly as hard as an answer and wrapped onto
        // two lines, so it is neither faint enough nor far enough up the square to be told
        // by ink or by position, and tall enough to clear the floor an answer must clear.
        //
        // What is left to know it by is the rest of the page. This hand is half again the
        // height of the press, and a hand that size has no figures at two thirds of it.
        val readings = read(
            SyntheticGrid.rectified(
                SyntheticGrid.Page(
                    givens,
                    answers = halfWritten,
                    press = 0.44,
                    bold = true,
                    hand = SyntheticGrid.Hand.LARGE,
                    firmMarks = 0.9,
                    seed = 23,
                )
            )
        )
        checkPrinted(readings, "with firm candidate lists")

        val mistaken = (0 until 81).filter {
            givens[it] == '.' && halfWritten[it] == '.' && readings[it]?.ink == Ink.ANSWER
        }
        assertTrue(mistaken.isEmpty(), "firm candidate lists taken for answers at $mistaken")

        // And the rule has not eaten the handwriting it was told to measure.
        val lost = (0 until 81).filter {
            halfWritten[it] != '.' && readings[it]?.ink != Ink.ANSWER
        }
        assertTrue(lost.isEmpty(), "answers lost to the candidate rule at $lost")
    }

    @Test
    fun `an answer written over a rubbed-out digit is still one digit`() {
        // The same-ink rule under a different light: the ghost is close enough to touch and
        // faint enough not to belong, and gathering it would make a plus sign of a 7.
        val readings = read(
            SyntheticGrid.rectified(
                SyntheticGrid.Page(givens, answers = written, ghosts = 0.5, seed = 13)
            )
        )
        checkPrinted(readings, "over erasures")
    }

    @Test
    fun `a lamp off to one side does not change what is print`() {
        // Contrast is measured against the paper of each square rather than of the page, so
        // a gradient across the sheet should move a digit and its background together.
        val readings = read(
            SyntheticGrid.rectified(SyntheticGrid.Page(givens, shadow = 0.45, seed = 2))
        )
        checkPrinted(readings, "under a lamp")
    }

    @Test
    fun `a blurred and speckled page still reads`() {
        val readings = read(
            SyntheticGrid.rectified(
                SyntheticGrid.Page(givens, blur = 1.0, noise = 12.0, seed = 3)
            )
        )
        checkPrinted(readings, "blurred")
    }

    @Test
    fun `the hardest of them together`() {
        val readings = read(
            SyntheticGrid.rectified(
                SyntheticGrid.Page(
                    givens,
                    answers = halfWritten,
                    figures = SyntheticGrid.Figures.OLD_STYLE,
                    family = Font.SERIF,
                    hand = SyntheticGrid.Hand.LOOSE,
                    marks = 0.35,
                    ghosts = 0.4,
                    shadow = 0.3,
                    blur = 1.0,
                    noise = 8.0,
                    seed = 17,
                )
            )
        )
        checkPrinted(readings, "everything at once")
    }
}
