package io.github.tonyxmelon.aisudoku.recognize

import io.github.tonyxmelon.aisudoku.model.Cell
import io.github.tonyxmelon.aisudoku.model.Grid
import io.github.tonyxmelon.aisudoku.solver.SolveResult
import io.github.tonyxmelon.aisudoku.solver.Solver
import io.github.tonyxmelon.aisudoku.vision.GrayImage
import kotlin.math.abs

/**
 * What one cell was judged to hold.
 *
 * [MARK] and [NONE] are both empty as far as the puzzle is concerned, but they are not
 * the same thing to a person looking at the page: one cell has pencilled candidates in
 * it that the reader deliberately ignored, the other has nothing at all.
 */
enum class Ink { PRINTED, ANSWER, MARK, NONE }

/** How the reader judged one cell. */
data class CellReading(
    val index: Int,
    val ink: Ink,
    /** Probabilities for digits 1..9, or null when the cell holds no digit. */
    val probabilities: FloatArray?,
    val heightRatio: Double,
    val darkness: Double,
) {
    val digit: Int? get() = probabilities?.let { p -> p.indices.maxBy { p[it] } + 1 }

    /** Gap between the two most likely digits. Small means the classifier is guessing. */
    val margin: Float
        get() {
            val p = probabilities ?: return 1f
            val sorted = p.sortedDescending()
            return sorted[0] - sorted[1]
        }

    override fun equals(other: Any?): Boolean = other is CellReading && other.index == index
    override fun hashCode(): Int = index
}

/** What the reader concluded about a whole photograph. */
sealed interface ReadResult {

    /** A grid the reader stands behind. */
    data class Accepted(val grid: Grid, val readings: List<CellReading>) : ReadResult

    /**
     * A grid, but with cells the reader is unsure of. Those are exactly the cells to
     * ask the user about - rejection and correction are the same mechanism at different
     * scales.
     */
    data class NeedsConfirmation(
        val grid: Grid,
        val readings: List<CellReading>,
        val uncertainCells: Set<Int>,
        val reason: String,
    ) : ReadResult

    /** Too little was read with confidence to be worth confirming cell by cell. */
    data class Unreadable(val reason: String, val uncertainCells: Set<Int>) : ReadResult
}

/**
 * Turns 81 cell images into a grid, and decides whether to stand behind it.
 *
 * Everything starts from the printed digits. They are the one population on the page
 * whose properties are guaranteed: one font, one ink, one size, and never fewer than
 * seventeen of them, because no sudoku with a single solution can have fewer. Find them
 * and the rest follows - what is taller is handwriting, and what is smaller, or sits
 * high in the cell, is a pencilled candidate mark - so every threshold comes from the
 * photograph in hand rather than being fixed in advance.
 *
 * Acceptance is judged on whether extraction actually succeeded, not on how the
 * photograph scored. Image metrics reject usable photographs and pass unusable ones;
 * blur is the clearest case, raising the grid-detection score while destroying
 * legibility. So the verdict comes from classifier margins, and from whether the printed
 * digits form a puzzle with exactly one solution.
 */
class GridReader(private val classifier: DigitClassifier = DigitClassifier.load()) {

    fun read(cells: List<GrayImage>): ReadResult {
        require(cells.size == 81) { "expected 81 cells but got ${cells.size}" }

        val ink = CellAnalyzer.inspect(cells)
        val core = findPrintedCore(ink.mapNotNull { it?.blob })
            ?: return ReadResult.Unreadable(
                "Could not find the printed digits in that photo.", emptySet(),
            )

        fun readAll(sortByInk: Boolean) = ink.mapIndexed { index, cell ->
            val kind = if (cell == null) Ink.NONE else classify(cell, core, sortByInk)
            CellReading(
                index = index,
                ink = kind,
                probabilities = if (cell == null || kind == Ink.MARK || kind == Ink.NONE) null
                else classifier.classify(cell.normalised),
                heightRatio = cell?.blob?.heightRatio ?: 0.0,
                darkness = cell?.blob?.darkness ?: 255.0,
            )
        }

        // Size first, because on a page where the writing is taller than the print it is
        // both the cheapest test and the surest. When the writer works at the size of the
        // print it is neither, and the page says so out loud: the printed band fills with
        // answers and the puzzle comes out with seventy-odd givens, which no sudoku has.
        // Only then is it worth separating them by ink, which is a finer measure and a
        // riskier one - it costs printed digits on pages where nothing was wrong.
        val readings = readAll(sortByInk = false).let { first ->
            if (first.count { it.ink == Ink.PRINTED } > PLAUSIBLE_GIVENS) {
                // The page has said its size rule failed. Sort it by ink, and then let the
                // two populations settle where they actually lie. See [settle].
                settle(ink, core, readAll(sortByInk = true))
            } else {
                first
            }
        }

        val printed = readings.filter { it.ink == Ink.PRINTED }
        if (printed.size < MIN_GIVENS) {
            return ReadResult.Unreadable(
                "Only ${printed.size} printed digits were found, which is too few for a puzzle.",
                printed.map { it.index }.toSet(),
            )
        }

        val grid = assemble(readings)
        val weak = readings
            .filter { it.digit != null && it.margin < CONFIDENT_MARGIN }
            .map { it.index }
            .toSet()

        return when (Solver.solve(grid)) {
            is SolveResult.Unique ->
                if (weak.isEmpty()) {
                    ReadResult.Accepted(grid, readings)
                } else {
                    ReadResult.NeedsConfirmation(
                        grid, readings, weak,
                        "Some digits were not read confidently.",
                    )
                }

            else -> repair(readings, grid, weak, core)
        }
    }

    /**
     * The print and the handwriting told apart by where they actually fall, not by a bar.
     *
     * Every threshold before this is a line drawn across one measurement, and on these
     * pages there is no line to draw: the writer works at the size of the print and bears
     * down as hard as the press did, so height overlaps, ink overlaps, and each of the six
     * things measured of a blob overlaps on its own. Measured over the twelve pages, the
     * best rule anyone can write over those six leaves about seventy of the seventy-nine
     * cells wrong, which is barely better than the seventy-nine it starts from.
     *
     * What is true of them together is that they are two populations rather than one, and
     * two populations can be found without knowing where the line between them goes. The
     * split the ink sort produced is taken as a starting guess and the cells are allowed to
     * settle into the two clusters they are nearest, over all five measurements at once,
     * each standardised so no one of them decides by having the largest numbers. The
     * cluster carrying more ink is the print, because print is toner and an answer is not.
     *
     * Nothing here is fitted to the corpus: there is no threshold, and the starting point
     * is whatever the rules already decided. It takes those twelve pages from 84 wrong to
     * 45. It runs only where the page has already declared the size rule broken, so no
     * photograph that reads correctly today is touched by it - started cold instead of from
     * the existing split it is worse, 63 rather than 45, and run on every page it costs a
     * cell on one that was perfect.
     */
    private fun settle(
        ink: List<CellInk?>,
        core: PrintedCore,
        readings: List<CellReading>,
    ): List<CellReading> {
        val considered = readings.indices.filter {
            readings[it].ink == Ink.PRINTED || readings[it].ink == Ink.ANSWER
        }
        if (considered.size < ENOUGH_TO_SETTLE) return readings

        val unlike = unlikeItsTwins(considered, ink, readings)
        val measured = considered.map { index ->
            val cell = ink[index] ?: return readings
            val blob = cell.blob
            doubleArrayOf(
                blob.heightRatio / core.height,
                blob.verticalOffset,
                inkOf(blob, core),
                blob.contrast / 255.0,
                blob.strokeWidth / 20.0,
            )
        }
        val inkiness = measured.map { it[2] }
        val features = measured.mapIndexed { i, row -> row + unlike[i] }
        standardise(features)

        // Settling on the measurements first and bringing the shape in for a second pass
        // was tried, on the thought that shape is the noisiest axis and should not drag the
        // starting split about. It lands in exactly the same place - 57 either way - so the
        // simpler of the two is here.
        var labels = considered.map { if (readings[it].ink == Ink.PRINTED) 1 else 0 }.toIntArray()
        repeat(SETTLING_ROUNDS) {
            val centres = Array(2) { group -> centre(features, labels, group) }
            val moved = IntArray(labels.size) { i ->
                if (apart(features[i], centres[1]) < apart(features[i], centres[0])) 1 else 0
            }
            if (moved.contentEquals(labels)) return@repeat
            labels = moved
        }

        // Print is toner and an answer is not, so of the two clusters the inkier one is the
        // print. Which side a cluster started on says nothing - the settling is free to
        // swap them, and on some pages it does.
        val inkiest = (0..1).maxBy { group ->
            val members = labels.indices.filter { labels[it] == group }
            if (members.isEmpty()) -1.0 else members.sumOf { inkiness[it] } / members.size
        }
        val printed = labels.count { it == inkiest }
        if (printed < MIN_GIVENS || printed > PLAUSIBLE_GIVENS) return readings

        val settled = readings.toMutableList()
        considered.forEachIndexed { i, index ->
            val kind = if (labels[i] == inkiest) Ink.PRINTED else Ink.ANSWER
            if (kind != settled[index].ink) settled[index] = settled[index].copy(ink = kind)
        }
        return settled
    }

    /**
     * How unlike the other copies of its own digit a cell is, in the shape of the ink.
     *
     * Print is a font. Every printed seven on a page is the same seven, struck by the same
     * press, while a handwritten seven is only ever similar to the others - measured over
     * these pages, two printed copies of a digit sit 33 apart at the median and two written
     * ones 80. It is the strongest single thing that separates them and it does not appear
     * anywhere in the five measurements of a blob, all of which are about how big and how
     * dark the ink is rather than what shape it makes.
     *
     * Used on its own it disappoints, and that is worth recording because it looked like
     * the answer: templates built from the *true* printed cells separate the corpus with
     * seven errors, but that needs the answer to compute. Built from what can be had - the
     * closest pair of any digit on the page, which really is two printed cells 91 times in
     * a hundred - the best threshold anyone could draw leaves 71 and an honest rule leaves
     * 79, against 84 without it. A template from two samples carries too much of its own
     * noise, and this reader's hand is too consistent, for a line drawn on that one number.
     *
     * As one more axis for the settling it is a different thing entirely, because it does
     * not have to be right on its own - it only has to pull in the same direction as the
     * other five. It takes the twelve pages from 62 wrong to 57.
     */
    private fun unlikeItsTwins(
        considered: List<Int>,
        ink: List<CellInk?>,
        readings: List<CellReading>,
    ): DoubleArray {
        val byDigit = considered.indices.groupBy { readings[considered[it]].digit }
        val out = DoubleArray(considered.size)
        for ((digit, members) in byDigit) {
            if (digit == null || members.size < 2) continue
            val shapes = members.map { ink[considered[it]]?.normalised ?: return DoubleArray(considered.size) }

            var closest = Double.MAX_VALUE
            var pair: Pair<Int, Int>? = null
            for (a in members.indices) {
                for (b in a + 1 until members.size) {
                    val apart = between(shapes[a], shapes[b])
                    if (apart < closest) {
                        closest = apart
                        pair = a to b
                    }
                }
            }
            val (first, second) = pair ?: continue
            val template = FloatArray(shapes[first].size) { (shapes[first][it] + shapes[second][it]) / 2f }
            members.forEachIndexed { i, index -> out[index] = between(shapes[i], template) }
        }
        return out
    }

    private fun between(a: FloatArray, b: FloatArray): Double {
        var sum = 0.0
        for (i in a.indices) sum += Math.abs(a[i] - b[i]).toDouble()
        return sum
    }

    /** Each measurement centred and scaled, so none of them decides by being the largest. */
    private fun standardise(rows: List<DoubleArray>) {
        val width = rows.first().size
        for (column in 0 until width) {
            val mean = rows.sumOf { it[column] } / rows.size
            val spread = Math.sqrt(rows.sumOf { (it[column] - mean) * (it[column] - mean) } / rows.size)
            for (row in rows) row[column] = (row[column] - mean) / (spread + 1e-9)
        }
    }

    private fun centre(rows: List<DoubleArray>, labels: IntArray, group: Int): DoubleArray {
        val members = labels.indices.filter { labels[it] == group }
        val width = rows.first().size
        if (members.isEmpty()) return DoubleArray(width)
        return DoubleArray(width) { column -> members.sumOf { rows[it][column] } / members.size }
    }

    private fun apart(row: DoubleArray, centre: DoubleArray): Double =
        row.indices.sumOf { (row[it] - centre[it]) * (row[it] - centre[it]) }

    /**
     * The printed digits, found before anything else is decided.
     *
     * Take the seventeen blobs most alike in height and in ink, preferring the darkest
     * such group. Printed digits are toner and everything else on the page is pencil, so
     * on a puzzle covered in annotations - where the marks are numerous enough that
     * seventeen of *them* are more uniform in size than the print - darkness is what
     * still tells the two apart. Measured over the corpus this picks the printed digits
     * on every photograph, including the one where size alone picks the marks.
     *
     * Height alone is not enough, and three schemes built on it each regressed
     * photographs that read perfectly: the widest gap is the one between print and
     * handwriting, the densest cluster is the handwriting on a completed puzzle, and the
     * lowest cluster of seventeen straddles the mark/print boundary.
     */
    internal fun findPrintedCore(blobs: List<Blob>): PrintedCore? {
        val present = blobs.filter { it.heightRatio >= MARK_FLOOR }.sortedBy { it.heightRatio }
        if (present.size < MIN_GIVENS) return null

        var best: List<Blob>? = null
        var bestScore = Double.MAX_VALUE
        for (start in 0..present.size - MIN_GIVENS) {
            val window = present.subList(start, start + MIN_GIVENS)
            val heightSpread = window.last().heightRatio - window.first().heightRatio
            val darknessSpread = (window.maxOf { it.darkness } - window.minOf { it.darkness }) / 255.0
            val lightness = median(window.map { it.darkness }) / 255.0
            val score = heightSpread + darknessSpread * DARKNESS_SPREAD_WEIGHT +
                lightness * LIGHTNESS_WEIGHT
            if (score < bestScore) {
                bestScore = score
                best = window
            }
        }

        val window = best ?: return null
        return PrintedCore(
            height = median(window.map { it.heightRatio }),
            darkness = median(window.map { it.darkness }),
            strokeWidth = median(window.map { it.strokeWidth }),
            contrast = median(window.map { it.contrast }),
        )
    }

    /**
     * What one blob is, measured against the printed digits of the same photograph.
     *
     * Printed digits vary in height by no more than 5% of their own median across the
     * whole corpus, so the printed band can be tight, and handwriting is never less than
     * 15% taller than the print. The only things that reach into either band are large
     * candidate marks - a ringed pair of digits, say - and those are given away by where
     * they sit: candidate marks are written along the top of a cell, answers in the
     * middle of it. Measured, no mark reaching digit size sits lower than 0.14 of a cell
     * above centre, no printed digit higher than 0.15, and no answer higher than 0.10.
     */
    internal fun classify(ink: CellInk, core: PrintedCore, sortByInk: Boolean = false): Ink {
        val blob = ink.blob
        val relative = blob.heightRatio / core.height
        return when {
            relative in PRINTED_MIN..PRINTED_MAX && blob.verticalOffset >= PRINTED_TOP_LIMIT &&
                (!sortByInk || inkOf(blob, core) >= PRINT_INK) -> Ink.PRINTED

            (relative >= ANSWER_MIN || inkOf(blob, core) >= INKY_ENOUGH_ANYWAY) &&
                blob.verticalOffset >= ANSWER_TOP_LIMIT && !isPencilledMark(ink, core) ->
                if (isResidue(ink)) Ink.MARK else Ink.ANSWER

            else -> Ink.MARK
        }
    }

    /**
     * How much ink a blob carries, against the print of this same photograph.
     *
     * Its contrast with the paper beside it, times its stroke width, both as fractions of
     * the print's own. Contrast is measured against the cell rather than the page so a
     * crease or a shadow moves ink and background together and leaves this alone, and
     * everything is relative to the print so neither the exposure, the paper nor the pen
     * has to be assumed.
     *
     * Measured over the corpus this orders the three populations where size does not:
     * print runs 0.65 to 1.28, answers 0.21 to 0.68, marks 0.05 to 0.36. They overlap at
     * the edges, which is why size still does the first cut and this decides within it.
     */
    private fun inkOf(blob: Blob, core: PrintedCore): Double {
        if (core.contrast <= 0.0 || core.strokeWidth <= 0.0) return 1.0
        return (blob.contrast / core.contrast) * (blob.strokeWidth / core.strokeWidth)
    }

    /**
     * Whether digit-sized ink is a pencilled candidate rather than an answer.
     *
     * Faint alone will not do: the faintest real answers in the corpus carry less ink
     * than the marks do, and cutting on ink alone costs twenty-four of them to catch
     * four marks. What marks have that those answers do not is company. They are written
     * in groups, because a group is what a candidate list is, while an answer stands
     * alone in its square in nine cases out of ten.
     *
     * Both together cost four answers and catch every digit-sized mark in the corpus.
     */
    private fun isPencilledMark(ink: CellInk, core: PrintedCore): Boolean =
        inkOf(ink.blob, core) < MARK_INK && ink.company >= MARK_COMPANY

    /**
     * Whether digit-sized ink is what is left of an erased digit rather than an answer.
     *
     * A rubbed-out digit keeps its size and its place in the middle of the square, so
     * nothing about its shape says it is gone; only the graphite is gone. Two things
     * together say so, and neither would on its own:
     *
     * The ink is faint against its own paper. Alone this is useless - the faintest real
     * answer in the corpus is fainter, in absolute grey levels, than some erasures.
     *
     * And something darker is written in the same square. A player who erases a digit
     * and pencils candidates over it leaves the candidates darker than the ruins; a
     * player who writes an answer leaves it the darkest thing in the square, which is
     * why 174 of the 180 answers in the corpus are outshone by nothing at all.
     *
     * Measured on one erasure, which is one more than a threshold usually gets and far
     * fewer than it deserves. What makes it worth standing on is that the conditions are
     * independent and both are far from the nearest real answer: every threshold from
     * 25 to 35 grey levels of contrast, against 15 to 30 of being outshone, catches the
     * erasure and loses none of the 180 answers. This sits in the middle of that.
     */
    private fun isResidue(ink: CellInk): Boolean =
        ink.blob.contrast < RESIDUE_CONTRAST && ink.outshoneBy > RESIDUE_OUTSHONE

    private fun assemble(readings: List<CellReading>): Grid {
        var grid = Grid.Empty
        for (reading in readings) {
            val digit = reading.digit ?: continue
            grid = grid.with(
                reading.index,
                if (reading.ink == Ink.PRINTED) Cell.given(digit) else Cell.guess(digit),
            )
        }
        return grid
    }

    /**
     * The printed digits do not make a puzzle, so the reading of them is wrong somewhere.
     *
     * Only printed digits can be at fault: the solver works from the givens alone, so no
     * handwritten answer, right or wrong, changes the outcome.
     *
     * Removing a given is tried before changing one. The observed failure is a false
     * positive - a clump of candidate marks that happens to match the print in size -
     * and removing a *real* given almost never yields a unique puzzle, while changing one
     * can quietly produce a different puzzle that solves cleanly. That is how an earlier
     * version turned a correctly read 1 into a 7 and reported success.
     *
     * Suspects are ranked by how far they sit from the printed core, so the least
     * print-like digit is questioned first.
     */
    private fun repair(
        readings: List<CellReading>,
        original: Grid,
        weak: Set<Int>,
        core: PrintedCore,
    ): ReadResult {
        val suspects = readings
            .filter { it.ink == Ink.PRINTED && it.digit != null }
            .sortedByDescending { deviation(it, core) }
            .take(REPAIR_CELLS)

        for (suspect in suspects) {
            val without = original.with(suspect.index, Cell.Empty)
            if (Solver.solve(without) is SolveResult.Unique) {
                return ReadResult.NeedsConfirmation(
                    without, readings, weak + suspect.index,
                    "One cell looked like a printed digit but is not.",
                )
            }
        }

        for (suspect in suspects.sortedBy { it.margin }) {
            val probabilities = suspect.probabilities ?: continue
            val ranked = probabilities.indices.sortedByDescending { probabilities[it] }
            for (alternative in ranked.drop(1).take(REPAIR_ALTERNATIVES)) {
                val attempt = original.with(suspect.index, Cell.given(alternative + 1))
                if (Solver.solve(attempt) is SolveResult.Unique) {
                    return ReadResult.NeedsConfirmation(
                        attempt, readings, weak + suspect.index,
                        "One printed digit was corrected automatically.",
                    )
                }
            }
        }

        // Handed back as read, and never refused for this.
        //
        // Being unable to solve a puzzle is not the same as being unable to read one, and
        // this used to conflate them: when more than six cells were in doubt the whole
        // photograph came back unreadable, which is the wrong thing to say about a page
        // whose digits were in fact all read correctly. The corpus now holds an
        // advertisement promising that only IQ 180 can solve its puzzle, which is true in
        // the sense that nobody can - it has many solutions rather than one. Its
        // forty-five digits are legible and every one of them is read right, and there is
        // nothing useful about answering that with "could not read".
        //
        // So the grid goes on with the cells to question attached. Only a failure of
        // recognition - no printed digits found, or too few to be a puzzle - stops the
        // pipeline now. What cannot be solved is still shown, and the person looking at it
        // can see for themselves what the app made of their page.
        return ReadResult.NeedsConfirmation(
            original, readings, weak + suspects.map { it.index },
            "The printed digits do not make a solvable puzzle.",
        )
    }

    /** How unlike the printed core a reading is, in units of the core's own spread. */
    private fun deviation(reading: CellReading, core: PrintedCore): Double =
        abs(reading.heightRatio / core.height - 1.0) / 0.05 +
            abs(reading.darkness - core.darkness) / 40.0

    /** The printed digits of one photograph: one font, one ink, one size. */
    internal data class PrintedCore(
        val height: Double,
        val darkness: Double,
        val strokeWidth: Double,
        /** How far the print stands out from its own paper. See [Blob.contrast]. */
        val contrast: Double = 1.0,
    )

    companion object {

        private fun median(values: List<Double>): Double {
            val sorted = values.sorted()
            return sorted[sorted.size / 2]
        }

        /** Below this a blob is speckle or a mark, and never joins the printed search. */
        private const val MARK_FLOOR = 0.25

        /** The proven minimum number of givens for a puzzle with one solution. */
        private const val MIN_GIVENS = 17

        /**
         * More printed digits than this and the printed band has caught something else.
         *
         * A published sudoku carries between seventeen and forty; the corpus runs 23 to
         * 31. Seventy is not a hard puzzle, it is a page whose writing is the size of its
         * print, and it is the signal to sort that page by ink instead.
         */
        private const val PLAUSIBLE_GIVENS = 45

        /** Fewest cells worth letting settle into two clusters. */
        private const val ENOUGH_TO_SETTLE = 20

        /** How many times the cells may move between the two clusters before stopping. */
        private const val SETTLING_ROUNDS = 40

        /** How much agreement on ink counts next to agreement on size. */
        private const val DARKNESS_SPREAD_WEIGHT = 0.5

        /**
         * How much being the darkest group counts. Print is toner and everything else is
         * pencil, so this is what separates the print from a page of candidate marks.
         */
        private const val LIGHTNESS_WEIGHT = 1.0

        /**
         * The printed band, as a multiple of the core height. Measured across the corpus,
         * printed digits fall in 0.93 to 1.05 and handwriting starts at 1.15.
         */
        private const val PRINTED_MIN = 0.90
        private const val PRINTED_MAX = 1.09

        /**
         * How small a blob may be and still be an answer rather than a candidate mark.
         *
         * It was 1.10 - an answer had to be taller than the print - and that is only true
         * of a reader who writes large. Twenty-eight answers across the newsprint pages
         * are *smaller* than the print beside them, and every one was being thrown away as
         * a pencilled mark: forty-seven answers lost in all, which is more than a third of
         * everything the triage got wrong.
         *
         * Dropping it to 0.80 recovers twenty-five of them and costs nothing anywhere
         * else. Lower is better still on those pages - 0.75 leaves eighteen wrong rather
         * than twenty-two - and 0.80 is where it stops without breaking a page that reads
         * correctly today, which is the trade being made and not an optimum. What sets the
         * real floor is not size at all: [ANSWER_TOP_LIMIT] and [isPencilledMark] are what
         * separate a mark from an answer, because marks run along the top of the cell and
         * are pencil while answers sit centred and are pen.
         */
        private const val ANSWER_MIN = 0.80

        /**
         * Ink that makes a blob a digit whatever its size.
         *
         * The size floor above assumes a digit is drawn whole. Blur does not oblige: on a
         * photograph of a screen taken out of focus the threshold breaks digits into
         * pieces, and the largest piece of a 1.00-height digit measures 0.29 to 0.79 - so
         * forty-eight of the fifty-three digits the triage was filing as pencil marks were
         * lost to size alone, several of them carrying more ink than the print itself.
         *
         * A blob carrying this much ink is not a pencil mark whatever its size, and 0.25
         * is where that stops being an opinion: genuine marks run to 0.255 at the
         * ninetieth percentile, so this is the edge of their population rather than a
         * number picked to fit. It is also exactly where the corpus turns - 0.30 leaves
         * 1929 cells sorted and 0.25 leaves 1932, while 0.22 costs a cell on a page that
         * is correct today.
         */
        private const val INKY_ENOUGH_ANYWAY = 0.25

        /**
         * How far above the centre of its cell a blob may sit and still be a digit.
         *
         * Candidate marks are written along the top edge. The printed test can afford the
         * looser limit because its height band already excludes almost everything.
         */
        private const val PRINTED_TOP_LIMIT = -0.18
        private const val ANSWER_TOP_LIMIT = -0.13

        /**
         * How much of the print's ink a blob must carry to be counted as print.
         *
         * Size alone put the answers of nine corpus pages into the printed band, because
         * their writer works at the size of the print. See [inkOf].
         */
        private const val PRINT_INK = 0.55

        /**
         * And below this, in the company of others, it is a pencilled mark.
         *
         * Ink and company together, because neither settles it alone: the faintest real
         * answers are fainter than some marks. Both had to widen when the size floor came
         * down, because size is no longer keeping the marks out on its own - a mark is now
         * caught here or not at all. Three pieces of ink is what a candidate list looks
         * like, where a written answer sits alone in its square in 581 of 582 cases.
         */
        private const val MARK_INK = 0.32

        /** How many other pieces of ink make a square a candidate list rather than an answer. */
        private const val MARK_COMPANY = 3

        /**
         * How faint digit-sized ink must be, in grey levels against its own cell's
         * paper, before being outshone in the same square condemns it as an erasure.
         */
        private const val RESIDUE_CONTRAST = 30.0

        /** How much darker other ink in the same square must be to condemn it. */
        private const val RESIDUE_OUTSHONE = 22.0

        /** Gap between the top two probabilities below which a cell counts as weak. */
        private const val CONFIDENT_MARGIN = 0.60f

        private const val REPAIR_CELLS = 8
        private const val REPAIR_ALTERNATIVES = 2

        /** More uncertain cells than this and a retake beats confirming one by one. */
        private const val CONFIRMABLE_CELLS = 6
    }
}
