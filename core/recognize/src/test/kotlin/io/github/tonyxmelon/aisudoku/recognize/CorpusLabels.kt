package io.github.tonyxmelon.aisudoku.recognize

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
     */
    val sameSizeHandwriting = setOf(
        "aisudoku-2026-09-04-newsprint-blue-1.jpg",
        "aisudoku-2026-09-04-newsprint-blue-2.jpg",
        "aisudoku-2026-09-04-newsprint-red-1.jpg",
        "aisudoku-2026-09-04-newsprint-red-2.jpg",
        "aisudoku-2026-09-04-newsprint-red-mistakes.jpg",
        "aisudoku-2026-09-04-newsprint-blue-3.jpg",
        "aisudoku-2026-09-04-newsprint-blue-4.jpg",
        "aisudoku-2026-09-04-newsprint-red-3.jpg",
        "aisudoku-2026-09-04-newsprint-red-4.jpg",
        "aisudoku-2026-09-04-newsprint-partial.jpg",
        "aisudoku-2026-09-04-newsprint-welded-border.jpg",
        "aisudoku-2026-09-04-newsprint-curled.jpg",
        "aisudoku-2026-09-07-newsprint-mepham-marker.jpg",
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
    val faintOnScreen = setOf(
        "aisudoku-2026-09-05-screen-ambiguous.jpg",
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
