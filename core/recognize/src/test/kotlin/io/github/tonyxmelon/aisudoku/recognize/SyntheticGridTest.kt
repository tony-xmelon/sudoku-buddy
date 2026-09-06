package io.github.tonyxmelon.aisudoku.recognize

import io.github.tonyxmelon.aisudoku.vision.CellExtractor
import io.github.tonyxmelon.aisudoku.vision.CellGeometry
import io.github.tonyxmelon.aisudoku.vision.GrayImage
import io.github.tonyxmelon.aisudoku.vision.OpenCvNatives
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
 * They are deliberately easy - no crease, no shadow, no camera - and are held to the
 * standard an easy page deserves: every printed digit sorted as print and read correctly. A
 * photograph is a different question and the corpus is still the only thing that can ask it.
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

    private fun read(image: GrayImage): List<CellReading> {
        OpenCvNatives.ensureLoaded { nu.pattern.OpenCV.loadShared() }
        val cells = CellExtractor.extract(image, CellGeometry.evenNinths(SyntheticGrid.SIDE))
        return when (val result = GridReader().read(cells)) {
            is ReadResult.Accepted -> result.readings
            is ReadResult.NeedsConfirmation -> result.readings
            is ReadResult.Unreadable -> error("the reader refused a drawn grid: ${result.reason}")
        }
    }

    private fun checkPrinted(readings: List<CellReading>, label: String) {
        val byIndex = readings.associateBy { it.index }
        val wrongKind = (0 until 81).filter {
            givens[it] != '.' && byIndex[it]?.ink != Ink.PRINTED
        }
        assertTrue(
            wrongKind.isEmpty(),
            "$label: printed digits sorted as ${wrongKind.map { byIndex[it]?.ink }} at $wrongKind",
        )
        val misread = (0 until 81).filter {
            givens[it] != '.' && byIndex[it]?.digit != givens[it] - '0'
        }
        assertTrue(
            misread.isEmpty(),
            "$label: printed digits misread at $misread " +
                "(${misread.map { "${givens[it]} read ${byIndex[it]?.digit}" }})",
        )
    }

    @Test
    fun `an unsolved page reads whatever its figures are`() {
        for (figures in SyntheticGrid.Figures.entries) {
            for (family in listOf(Font.SANS_SERIF, Font.SERIF, Font.MONOSPACED)) {
                val label = "$figures $family"
                val readings = read(
                    SyntheticGrid.rectified(givens, figures = figures, family = family)
                )
                checkPrinted(readings, label)
                assertEquals(
                    30, readings.count { it.ink == Ink.PRINTED },
                    "$label: expected thirty printed digits",
                )
            }
        }
    }

    @Test
    fun `old-style figures are print, not a second hand`() {
        // The fault this whole family of tests exists for: 1 and 2 at x-height are three
        // quarters the height of the tall figures, which is well outside the printed band.
        val readings = read(
            SyntheticGrid.rectified(givens, figures = SyntheticGrid.Figures.OLD_STYLE)
        ).associateBy { it.index }
        val short = (0 until 81).filter { givens[it] == '1' || givens[it] == '2' }
        assertTrue(short.isNotEmpty(), "the puzzle must contain the short figures")
        for (i in short) {
            assertEquals(Ink.PRINTED, readings[i]?.ink, "cell $i holds a printed ${givens[i]}")
        }
    }

    @Test
    fun `a finished page keeps the pen and the press apart`() {
        val readings = read(SyntheticGrid.rectified(givens, answers = written, seed = 7))
            .associateBy { it.index }
        val wrong = (0 until 81).filter { written[it] != '.' && readings[it]?.ink != Ink.ANSWER }
        assertTrue(
            wrong.size <= 2,
            "answers sorted as ${wrong.map { readings[it]?.ink }} at $wrong",
        )
    }

    @Test
    fun `a blurred and speckled page still reads`() {
        val readings = read(SyntheticGrid.rectified(givens, blur = 1.0, noise = 12.0, seed = 3))
        checkPrinted(readings, "blurred")
    }
}
