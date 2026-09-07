package org.freevia.sudokubuddy.app

import org.freevia.sudokubuddy.model.Cell
import org.freevia.sudokubuddy.model.CellSource
import org.freevia.sudokubuddy.recognize.Ink
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The sentence the cell editor puts at the top of the sheet.
 *
 * Three sources for one line and they are easy to confuse: what the reader made of the
 * square, what the user has since made of it, and - for a puzzle reopened from history,
 * where no reading was kept - simply what is there. The middle one is the one that goes
 * wrong: a corrected square has no report any more, so without it the sheet answers a
 * square somebody has just cleared with "Empty", which reads as though the correction had
 * not taken.
 */
class CellEditorTextTest {

    private val empty = Cell(digit = null, source = CellSource.EMPTY)
    private val printed = Cell(digit = 4, source = CellSource.GIVEN)
    private val written = Cell(digit = 7, source = CellSource.GUESS)

    @Test
    fun `with no reading it says what is in the square`() {
        assertEquals("Empty.", describeCell(empty, report = null, corrected = false))
        assertEquals("A printed 4.", describeCell(printed, report = null, corrected = false))
        assertEquals("A handwritten 7.", describeCell(written, report = null, corrected = false))
    }

    @Test
    fun `a square the user has put right says so, rather than describing it flatly`() {
        assertEquals("You cleared this square.", describeCell(empty, null, corrected = true))
        assertEquals(
            "You set this to a printed 4.",
            describeCell(printed, null, corrected = true),
        )
        assertEquals(
            "You set this to a handwritten 7.",
            describeCell(written, null, corrected = true),
        )
    }

    @Test
    fun `a reading speaks for itself, whatever is in the square`() {
        val report = CellReport(
            ink = Ink.PRINTED,
            digit = 4,
            confidence = 0.99f,
            runnerUp = null,
            runnerUpConfidence = 0f,
        )
        // The reading wins over both other cases: it is the only one that can say how sure
        // the app was, and a corrected square never has one.
        assertEquals(report.describe(), describeCell(printed, report, corrected = false))
        assertEquals(report.describe(), describeCell(empty, report, corrected = true))
    }

    @Test
    fun `printed and handwritten are the only pair there is to swap between`() {
        assertEquals(CellSource.GUESS, flipped(CellSource.GIVEN))
        assertEquals(CellSource.GIVEN, flipped(CellSource.GUESS))
        // An empty square offers no swap button, so this is only ever asked of a filled
        // one - and answering GIVEN keeps it from silently becoming a given by default.
        assertEquals(CellSource.GIVEN, flipped(CellSource.EMPTY))
    }
}
