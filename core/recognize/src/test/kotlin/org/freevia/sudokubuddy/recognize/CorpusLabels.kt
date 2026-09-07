package org.freevia.sudokubuddy.recognize

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File

/**
 * Hand-transcribed ground truth for the corpus photographs.
 *
 * The labels are committed even though the photographs are not, so this checks for both
 * and skips when either is missing. Each file holds two nine-row blocks: `givens` is
 * what is printed, `written` is everything on the paper.
 *
 * Every label was machine-verified when written: each givens block has exactly one
 * solution, and for the completed puzzle that solution equals the written grid.
 */
object CorpusLabels {

    enum class Source { GIVEN, GUESS, EMPTY }

    /**
     * Pages whose handwriting is the size of the print, which the triage cannot sort.
     *
     * Finding the printed digits first rested on their being the one population that
     * shares a font, a colour and a size. On these ten the reader wrote at the size of
     * the print, so the printed band swallowed the answers. Sorting them by ink as well
     * as by size has taken the cost from 220 cells to 57 over the twelve; the cause and
     * what is left are set out in [RecognitionAccuracyTest]. They are named here because
     * every test that walks the corpus meets them.
     *
     * They stay in the corpus. A page the reader cannot sort is the reason to keep it,
     * and their digits are still scored by the classifier, which is where they earn their
     * keep: the tally [RecognitionAccuracyTest] prints for them is measured on every run
     * rather than quoted here, because it moves whenever the model is retrained.
     *
     * Twelve of the thirteen came from the same reader, which is worth saying plainly:
     * that much is one person's handwriting, not a law about newsprint. The thirteenth is
     * a second hand and it settles the question the other twelve could not - a syndicated
     * newspaper puzzle finished in black marker by somebody else, with pencilled
     * candidates left in the corners. Seven of its eighty-one squares are sorted wrongly,
     * five of them printed digits taken for answers, which is the same failure in the same
     * direction. The size assumption fails for at least two real hands.
     *
     * The fifteenth page is not a hand at all and is here because it is the limit of the
     * same problem. An illustrated puzzle draws its clues in black and its answers in
     * blue, in one font at one size - so the two differ in colour and in nothing else, and
     * colour is the one thing a grayscale reader cannot see. What is left of the
     * difference is that blue ink is lighter, which is a real signal and not a sufficient
     * one: twenty-three of its sixty filled squares are sorted wrongly, eighteen clues
     * taken for answers and five the other way. It is the honest floor of sorting by size
     * and ink, and nothing short of reading colour will move it far.
     */
    val sameSizeHandwriting = setOf(
        "sudoku-buddy-2026-09-04-newsprint-blue-1.jpg",
        "sudoku-buddy-2026-09-04-newsprint-blue-2.jpg",
        "sudoku-buddy-2026-09-04-newsprint-red-1.jpg",
        "sudoku-buddy-2026-09-04-newsprint-red-2.jpg",
        "sudoku-buddy-2026-09-04-newsprint-red-mistakes.jpg",
        "sudoku-buddy-2026-09-04-newsprint-blue-3.jpg",
        "sudoku-buddy-2026-09-04-newsprint-blue-4.jpg",
        "sudoku-buddy-2026-09-04-newsprint-red-3.jpg",
        "sudoku-buddy-2026-09-04-newsprint-red-4.jpg",
        "sudoku-buddy-2026-09-04-newsprint-partial.jpg",
        "sudoku-buddy-2026-09-04-newsprint-welded-border.jpg",
        "sudoku-buddy-2026-09-04-newsprint-curled.jpg",
        "sudoku-buddy-2026-09-07-newsprint-mepham-marker.jpg",
        "wikihow-2026-09-07-paper-highlighted-b.jpg",
    )

    /**
     * The one photograph of a screen, and what is still wrong with it.
     *
     * An advertisement on a phone lying on a car seat: out of focus, its digits light grey
     * on white, with a shadow across half the puzzle. Every one of its forty-five printed
     * digits is now read correctly and the grid is found and straightened; twenty of its
     * squares are still filed as handwriting rather than print.
     *
     * The note here has been wrong twice, which is worth leaving on the record. It first
     * said the ink could not be found - inferred from the classifier test skipping those
     * cells, when the reason it skips them is that a cell called a mark is never read. It
     * then said the digits were arriving in pieces, which was reading the small heights
     * without looking at the cells. What was actually happening is that the cells were
     * being cut in the wrong places: this page arrives through the rescue that accepts an
     * obscured grid, whose quad takes in a little margin, so the real rules sat further and
     * further left of their nominal ninths until the eighth column held the ninth column's
     * digit. Fitting the grid as a whole before hunting any single line fixed that, and
     * took this page from 31 wrong to 20.
     *
     * What is left is the shadow. Where the page is washed out the threshold finds only
     * part of each digit, so those blobs measure 0.2 to 0.8 of the printed band and are
     * offered as answers rather than print. Every digit is present and right; they are
     * editable where they should be fixed, which is the mildest form this fault takes.
     */
    /**
     * Pages somebody has drawn over, where the drawing crosses the digits.
     *
     * Illustrations from puzzle articles, where the point being taught is marked on the
     * grid in red: a ring round a digit, a line down a column, a line along a row. The
     * lines are drawn through the cells rather than beside them, so in grayscale a digit
     * and the line through it are one mark - taller than the print, which is what
     * handwriting looks like, and a different shape, so a 7 with a line down it reads as
     * a 1.
     *
     * It is the same mechanism as the striping a camera makes of a monitor, and it is not
     * fixed the same way: that is a hairline and this is a stroke as heavy as the digit.
     * Whether it is worth fixing is a fair question - a red pen through a printed digit is
     * an editorial mark rather than something a solver does - but it is measured here
     * rather than assumed away.
     */
    val drawnOver = setOf(
        "rd-2026-09-07-illustrated-1.jpg",
    )

    val faintOnScreen = setOf(
        "sudoku-buddy-2026-09-05-screen-ambiguous.jpg",
    )

    /**
     * Single cells that are known to be wrong, on pages that are otherwise right.
     *
     * The buckets above exempt a whole photograph, which is the right shape when the
     * whole photograph defeats a rule and the wrong shape when one square does. Listing
     * the square keeps the other eighty honest: every cell of these pages is scored, and
     * only the one named here is allowed to be wrong.
     *
     * A pencilled "19" written large and firmly across the top of a square, over the grey
     * of two earlier marks rubbed out. It is the height of a printed digit and sits
     * inside the printed band, so size and position both call it print, and it carries
     * 0.23 of the press's ink where that page's real print carries 0.65 to 1.27.
     *
     * Ink is therefore the thing that knows, and the reader will not ask it here: ink is
     * only consulted for print when the size rule has already failed, and on this page
     * size works for all eighty other squares. Making it a standing condition was
     * measured - it takes this cell and three like it and costs no given - but the margin
     * is 0.30 to 0.34, one page either side, which is thinner than anything else the
     * reader decides on, and the cell it saves is still wrong afterwards: demoted from
     * print it becomes an answer rather than a mark. Worth a fifth photograph of this
     * page before it is worth a threshold.
     */
    val fusedIntoPrint = setOf(
        "sudoku-buddy-2026-09-07-booklet-pencil-4.jpg" to 6,
    )

    data class Truth(val digit: Int?, val source: Source)

    private val directory = File("../../corpus-labels").canonicalFile

    val isAvailable: Boolean get() = directory.isDirectory && (directory.listFiles()?.isNotEmpty() == true)

    fun requireLabels() {
        assumeTrue(isAvailable, "corpus labels not found at $directory")
    }

    /** The 81 cells of one photograph, row-major, or null when it has no label file. */
    fun forPhoto(photoName: String): List<Truth>? {
        val file = File(directory, photoName.substringBeforeLast('.') + ".json")
        if (!file.isFile) return null
        val text = file.readText()

        val givens = block(text, "givens")
        val written = block(text, "written")
        require(givens.length == 81 && written.length == 81) {
            "${file.name}: expected 81 cells, got ${givens.length} and ${written.length}"
        }

        return (0 until 81).map { i ->
            when {
                givens[i] != '.' -> Truth(givens[i] - '0', Source.GIVEN)
                written[i] != '.' -> Truth(written[i] - '0', Source.GUESS)
                else -> Truth(null, Source.EMPTY)
            }
        }
    }

    /**
     * Pulls one named array of nine-character strings out of the JSON.
     *
     * Hand-parsed rather than pulling in a JSON library for two fields in a file this
     * regular; the shape is fixed by the generator that writes it.
     */
    private fun block(text: String, name: String): String {
        val start = text.indexOf("\"$name\"")
        require(start >= 0) { "no \"$name\" array in the label file" }
        val open = text.indexOf('[', start)
        val close = text.indexOf(']', open)
        require(open in 0 until close) { "malformed \"$name\" array" }
        return Regex("\"([.1-9]{9})\"")
            .findAll(text.substring(open, close))
            .joinToString("") { it.groupValues[1] }
    }
}
