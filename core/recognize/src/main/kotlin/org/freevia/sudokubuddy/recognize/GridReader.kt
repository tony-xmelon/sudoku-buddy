package org.freevia.sudokubuddy.recognize

import org.freevia.sudokubuddy.model.Cell
import org.freevia.sudokubuddy.model.Grid
import org.freevia.sudokubuddy.solver.SolveResult
import org.freevia.sudokubuddy.solver.Solver
import org.freevia.sudokubuddy.vision.GrayImage
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

        val settled = sortWhatALargeHandCannotHaveWritten(
            takeBackAFigureRank(putBackPencilThatSatLow(readings, ink, core), ink, core), ink, core,
        )

        val printed = settled.filter { it.ink == Ink.PRINTED }
        if (printed.size < MIN_GIVENS) {
            return ReadResult.Unreadable(
                "Only ${printed.size} printed digits were found, which is too few for a puzzle.",
                printed.map { it.index }.toSet(),
            )
        }

        val grid = assemble(settled)
        val weak = settled
            .filter { it.digit != null && it.margin < CONFIDENT_MARGIN }
            .map { it.index }
            .toSet()

        return when (Solver.solve(grid)) {
            is SolveResult.Unique ->
                if (weak.isEmpty()) {
                    ReadResult.Accepted(grid, settled)
                } else {
                    ReadResult.NeedsConfirmation(
                        grid, settled, weak,
                        "Some digits were not read confidently.",
                    )
                }

            else -> repair(settled, grid, weak, core)
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
    /**
     * Takes back a whole rank of printed digits that the size rule sent to the handwriting.
     *
     * The reader assumes a printed digit is one height, because in a lining font it is.
     * Not every font is lining. In an old-style font - text figures, which newspapers do
     * set - 1 and 2 sit at x-height while 6 and 8 ascend, and the short ones measure about
     * three quarters of the tall ones. That is well below the printed band, so every 1 and
     * 2 on the page is sorted as handwriting, and a puzzle loses a third of its givens
     * without a single digit being misread.
     *
     * Measured on a grid set in Georgia: five printed digits sorted as handwriting, all of
     * them 1s and 2s, every one at 0.737 of the core height against the font's own 0.74.
     * The same puzzle in Arial reads thirty printed and nothing wrong.
     *
     * What gives a rank away is that it is a rank. A font repeats a glyph exactly, so the
     * short figures all measure the same to three decimals, while a hand does not: across
     * the corpus the printed digits of a page spread by 0.011 to 0.047 and the handwriting
     * by 0.09 to 0.21, and the only handwriting tighter than that is two cells on a page
     * that has only two. So a group is taken back only if there are [ENOUGH_FOR_A_RANK] of
     * them, they are as dark as the print rather than as a pen - printed digits sit at 1.00
     * of their page's core contrast at the median against handwriting's 0.61 - and, once
     * the pen has been set aside that way, they sit within [ONE_FIGURE_SPREAD] of each
     * other. The darkness comes first because a page can hold both at once: a puzzle set in
     * text figures and half filled in has short print and real answers in the same pile,
     * and asking that pile as a whole to be tight finds nothing.
     *
     * The last guard is the one that makes the rest safe: the rank is only taken back if
     * the page still comes out with a plausible number of givens. On a solved puzzle the
     * fifty answers would have to join thirty givens to make eighty, which no sudoku has,
     * so the rule cannot fire where there is real handwriting to lose.
     */
    private fun takeBackAFigureRank(
        readings: List<CellReading>,
        ink: List<CellInk?>,
        core: PrintedCore,
    ): List<CellReading> {
        // Darkness first, because a page can hold both: a puzzle set in text figures and
        // half filled in has short print and real answers in the same pile, and asking the
        // pile as a whole to be tight would find nothing. What the press laid down is as
        // dark as the rest of the print; what a pen laid down is not.
        val rank = readings
            .filter { it.ink == Ink.ANSWER }
            .mapNotNull { reading -> ink[reading.index]?.blob?.let { reading to it } }
            .filter { (_, blob) -> blob.contrast / core.contrast >= RANK_CONTRAST }
        if (rank.size < ENOUGH_FOR_A_RANK) return readings
        if (readings.count { it.ink == Ink.PRINTED } + rank.size > PLAUSIBLE_GIVENS) return readings

        // Around the middle of them rather than across all of them. One cell that is still
        // the wrong height - a digit fused to something thicker than a hairline - would
        // otherwise spoil a rank that is exact everywhere else: on the Georgia page five
        // short figures sit within 0.01 of each other, and a sixth cell at 1.18 hid all
        // five of them.
        val middle = median(rank.map { (_, blob) -> blob.heightRatio / core.height })

        // Short of the printed band, which is the only thing this rule is for. A figure
        // that is not shorter than the print does not need taking back - if it were the
        // height of the print it would already be print - and a group that is taller is
        // handwriting, which is what the size rule is right about. Without this the rule
        // reaches into the pages where a hand works at the size of the press and makes
        // two more of them wrong.
        if (middle >= PRINTED_MIN) return readings

        val agreeing = rank.filter { (_, blob) ->
            Math.abs(blob.heightRatio / core.height - middle) <= ONE_FIGURE_SPREAD
        }
        if (agreeing.size < ENOUGH_FOR_A_RANK) return readings
        if (readings.count { it.ink == Ink.PRINTED } + agreeing.size > PLAUSIBLE_GIVENS) {
            return readings
        }

        val taken = agreeing.mapTo(mutableSetOf()) { (reading, _) -> reading.index }
        val promoted = readings.map { if (it.index in taken) it.copy(ink = Ink.PRINTED) else it }

        // And the page itself gets the last word, because everything above is satisfied by
        // a second printed population as neatly as by a rank of short figures - and one
        // page in the corpus is exactly that. A sudoku app photographed part way through
        // sets its clues in one weight and the solver's own entries in a lighter, smaller
        // one: seven entries, every one 0.83 of the printed height, every one within 0.01
        // of the others, and as dark as the press because they are the press. That is a
        // rank by every test here, and promoting it put seven answers among the clues.
        //
        // What tells the two apart is not in the ink. It is that a sudoku's clues have
        // exactly one solution, so a promotion that leaves the clues with none has
        // promoted something that is not a clue. The rank is taken only when the page can
        // still be a puzzle afterwards - and only refused when it was one before, so a
        // page that was never going to solve is left to the repair that follows.
        return if (!makesAPuzzle(promoted) && makesAPuzzle(readings)) readings else promoted
    }

    /** Whether the cells called print are a sudoku: enough of them, and exactly one answer. */
    private fun makesAPuzzle(readings: List<CellReading>): Boolean {
        val printed = readings.mapNotNull { reading ->
            reading.digit?.takeIf { reading.ink == Ink.PRINTED }?.let { reading.index to it }
        }
        if (printed.size < MIN_GIVENS) return false
        var grid = Grid.Empty
        for ((index, digit) in printed) grid = grid.with(index, Cell.given(digit))
        return Solver.solve(grid) is SolveResult.Unique
    }

    /**
     * What a page's own handwriting says cannot have come from it, in both directions.
     *
     * A pencilled candidate list is small and sits in a corner, so size and position
     * catch nearly all of them. What escapes is the list somebody has written firmly:
     * pressed hard, in a cramped square, two or three figures deep. It is faint for
     * neither test - it carries a quarter to four fifths of the print's ink - and though
     * each figure is small, one of them fused to its neighbour measures two thirds of the
     * printed height, which clears the floor an answer has to clear.
     *
     * Nothing about such a mark on its own says it is one. What says so is the rest of
     * the page. Where the writer's hand is plainly larger than the press - answers
     * running half again the height of a printed digit - that hand has no small figures
     * in it, and a blob at two thirds of the print did not come from it. Four
     * photographs of one booklet page, taken as it was solved, produced nine of these and
     * no other kind of error.
     *
     * So the page is asked where its own handwriting lies, and everything far below that
     * is put back - and, for the same reason, anything among the answers that carries
     * press ink is given to the press, which is the other way the same measure goes
     * wrong. See [FROM_THE_PRESS]. This is the mirror of [takeBackAFigureRank], which asks the same
     * question of the print and promotes what agrees with it, and it is deliberately
     * confined the same way. It is allowed to run only when the two populations are
     * plainly apart - a median at [A_LARGER_HAND] of the print or more, which every page
     * in the corpus is either well above or well below, with nothing between 1.18 and
     * 1.42 - and it only takes what is below [TOO_SMALL_FOR_THAT_HAND] of that median.
     * Both thresholds sit in the middle of a plateau rather than on an edge: anything
     * from 0.60 to 0.70 takes the same nine cells and costs nothing, and 0.75 begins to
     * cost real answers.
     */
    /**
     * Pencilled candidate lists read as printed clues, because they sit low.
     *
     * Every rule about where a mark sits guards the top of the square, since that is where
     * a candidate list is written. A list too long for one line wraps, and its second line
     * sits below the middle - lower than a printed digit ever sits - and one figure of it
     * fused to its neighbour is the height of the print. Nothing then objected: the printed
     * band asked that a blob not sit too high and never that it not sit too low.
     *
     * This is the worst shape the fault can take. Two such lists in the bottom row of a
     * booklet page put two clues into it that nobody had printed, and the puzzle stopped
     * solving - so the app refused a page whose twenty-four real clues it had read
     * perfectly, and the digits it had invented were not among the ones it offered to
     * correct. A photograph of the app saying so is how it was found.
     *
     * Position alone will not do it. Real clues do sit low - a page photographed at an
     * angle, a face with a deep descender - and the lowest of those sit lower than the
     * highest of these. What separates them is what they are made of: clues sitting that
     * low carry 0.685 of the press's ink and more, every false one carries 0.540 and less,
     * so the line goes in a gap of 0.145 - wider than anything else the reader cuts on.
     *
     * It runs here rather than in [classify] for a reason worth keeping. Put among the
     * tests that decide what a cell is, it also changes how many cells come out printed,
     * and that count is what decides whether the page gives up on size and sorts by ink
     * instead. Pages that had been switching stopped switching, and the collision pages
     * went from 86 wrong to 142 - a rule that costs nothing where it fires, wrecking pages
     * it never fired on. Run after the mode is chosen it cannot reach that decision.
     */
    private fun putBackPencilThatSatLow(
        readings: List<CellReading>,
        ink: List<CellInk?>,
        core: PrintedCore,
    ): List<CellReading> = readings.map { reading ->
        val blob = ink[reading.index]?.blob
        if (reading.ink == Ink.PRINTED && blob != null &&
            blob.verticalOffset > PRINT_SITS_NO_LOWER &&
            inkOf(blob, core) < PRESSED_ENOUGH_TO_SIT_LOW
        ) {
            reading.copy(ink = Ink.MARK, probabilities = null)
        } else {
            reading
        }
    }

    private fun sortWhatALargeHandCannotHaveWritten(
        readings: List<CellReading>,
        ink: List<CellInk?>,
        core: PrintedCore,
    ): List<CellReading> {
        val hand = readings
            .filter { it.ink == Ink.ANSWER }
            .mapNotNull { reading -> ink[reading.index]?.blob?.heightRatio?.div(core.height) }
        if (hand.size < ENOUGH_FOR_A_HAND) return readings

        // A median rather than a mean, and over the answers as they stand: the marks this
        // rule is looking for are among them, and a handful of small intruders must not be
        // able to drag the measure of the hand down towards themselves.
        val middle = median(hand)
        if (middle < A_LARGER_HAND) return readings

        val floor = middle * TOO_SMALL_FOR_THAT_HAND
        return readings.map { reading ->
            val blob = ink[reading.index]?.blob
            if (reading.ink != Ink.ANSWER || blob == null) {
                reading
            } else if (blob.heightRatio / core.height < floor) {
                reading.copy(ink = Ink.MARK, probabilities = null)
            } else if (inkOf(blob, core) >= FROM_THE_PRESS) {
                reading.copy(ink = Ink.PRINTED)
            } else {
                reading
            }
        }
    }

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

        // Something has to have been written here. Every other test below asks how big the
        // ink is or where it sits, and none of them asks whether it is ink - so a smear of
        // moire off a monitor, or what a rectification leaves of a grid line, is the height
        // of a digit, sits where a digit sits, and is read as one. Photographing a page set
        // in Georgia produced fourteen of them, every one read as a printed 1, each with a
        // contrast against its own paper of about three grey levels where the digits on
        // that page carry a hundred and thirty.
        if (core.contrast > 0 && blob.contrast < core.contrast * REAL_INK) return Ink.MARK

        val relative = blob.heightRatio / core.height
        return when {
            relative in PRINTED_MIN..PRINTED_MAX && blob.verticalOffset >= PRINTED_TOP_LIMIT &&
                (!sortByInk || inkOf(blob, core) >= PRINT_INK) -> Ink.PRINTED

            (relative >= ANSWER_MIN || inkOf(blob, core) >= INKY_ENOUGH_ANYWAY) &&
                blob.verticalOffset >= topLimitFor(blob, core) && !isPencilledMark(ink, core) ->
                if (isResidue(ink)) Ink.MARK else Ink.ANSWER

            else -> Ink.MARK
        }
    }

    /**
     * How high in its square a blob may sit, given how much ink it carries.
     *
     * Candidate marks are written along the top of a square and answers in the middle, so
     * where the ink sits is a real signal and the one line that catches a mark the size of
     * a digit. But it was a single number, and it is the reason five of the ten digits the
     * app fails to show are lost - three of them by a hundredth or two, and one of them a
     * full-size 4 sitting 0.02 too high. The rule was measured on one reader's pages, and
     * the second reader writes higher in the box.
     *
     * What separates a mark from a digit written high is not where it is but what it is
     * made of: marks are pencil, and this corpus has none carrying more than 0.255 of the
     * print's ink at the ninetieth percentile. So a blob carrying half the print's ink is
     * allowed to sit as high as the printed band is, and everything fainter is held to the
     * line where answers are.
     */
    private fun topLimitFor(blob: Blob, core: PrintedCore): Double =
        if (inkOf(blob, core) >= INK_THAT_EXCUSES_HEIGHT) INKY_TOP_LIMIT else ANSWER_TOP_LIMIT

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
        /**
         * How dark a blob must be, against the print of its own page, to be anything.
         *
         * Not a threshold between print and pen - that is [RANK_CONTRAST] and it sits far
         * higher - but the line under which a blob is not a mark anybody made. The faintest
         * genuine printed digit in the corpus carries 0.463 of its page's core and the
         * faintest handwriting 0.34, so this clears both by a wide margin; the phantoms
         * that prompted it carry 0.02 to 0.06.
         *
         * It has to be a fraction of the page rather than a number of grey levels, because
         * the pages that need it most are the faint ones: the photograph of a screen has a
         * core contrast of 34 where a newspaper has 150, and a fixed floor would either
         * pass the noise there or throw away the digits.
         */
        private const val REAL_INK = 0.10

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
        /**
         * How many cells make a rank of figures rather than a coincidence.
         *
         * Two is not enough: two pages in the corpus have exactly two handwritten answers
         * and those two sit within 0.01 and 0.02 of each other, which would look like a
         * rank on any measure of tightness. Every page with three or more spreads by at
         * least 0.045.
         */
        private const val ENOUGH_FOR_A_RANK = 3

        /**
         * How closely a group must agree on its height to be one figure of one font.
         *
         * The printed digits of a page spread by 0.011 to 0.047 across the corpus and the
         * handwriting by 0.09 to 0.21. This sits at the tight end of print and three times
         * inside the loose end of handwriting.
         */
        private const val ONE_FIGURE_SPREAD = 0.03

        /**
         * How dark a rank must be, against the print of its own page, to be print.
         *
         * Contrast rather than [inkOf], and the difference matters. inkOf multiplies
         * contrast by stroke width, and stroke width is mostly a fact about which digit it
         * is: a 1 is thin because a 1 is thin. On a drawn old-style page the 1s carry 0.48
         * of the core by that measure and the 2s 0.78, while a printed 7 on the same page
         * carries 0.58 - so the measure was sorting glyphs, not pens.
         *
         * Contrast is about what made the mark. Across the corpus, printed digits sit at
         * 1.00 of their page's core at the median and 0.77 at the fifth percentile;
         * handwriting sits at 0.61 and reaches 0.75 only at its seventy-fifth.
         *
         * This sits a little under the print's fifth percentile rather than above
         * handwriting's seventy-fifth, which looks like the wrong side to err on and is
         * not: the drawn pages put a monospaced 2 at 0.755, and a printed digit that faint
         * is a thing the corpus has too. What carries the separation is the tightness test
         * this filter feeds, not the filter itself - the filter only has to set aside the
         * pen on a page that holds both. Measured at 0.80 and 0.72 the corpus does not
         * move: triage 1949/2025 either way.
         *
         * The pages where a hand does reach the print - the writer bearing down as hard as
         * the press - are solved pages, and there the count guard refuses the rule first.
         */
        private const val RANK_CONTRAST = 0.72

        /**
         * How many answers a page needs before its handwriting has a size worth measuring.
         *
         * A median over three or four cells is not a population, and this rule demotes on
         * the strength of it. Every page it fires on has sixteen or more.
         */
        private const val ENOUGH_FOR_A_HAND = 5

        /**
         * When a page's handwriting counts as plainly larger than its print.
         *
         * The corpus splits cleanly here and the number is the middle of the gap: pages
         * where the hand works at the size of the press have a median answer of 1.00 to
         * 1.18 of the printed height, and pages where it is plainly larger run 1.42 to
         * 1.59. Nothing lies between.
         */
        private const val A_LARGER_HAND = 1.30

        /** How far below a page's own handwriting a blob may not have come from it. */
        private const val TOO_SMALL_FOR_THAT_HAND = 0.70

        /**
         * How much ink says a blob was laid down by the press, whatever its size.
         *
         * The other half of the same page. A printed digit with a pencil stroke fused to
         * it - a stray tail from the square below, a red ring drawn round it to teach
         * something - measures too tall for the printed band and is offered as an answer.
         * Its size is genuinely wrong and no size rule can save it; what is still right
         * about it is the ink, because a press lays down more than a pen or a pencil can
         * and neither the fused stroke nor the ring is heavy enough to dilute that much.
         *
         * Over the eleven large-hand pages the whole of the handwriting carries at most
         * 0.73 of the print's ink and the two fused givens carry 0.78 and 0.82, so the
         * line goes between them. It is a narrower margin than the rest of this class and
         * is written down as such: it takes two cells and costs none, but it has a page
         * either side of it rather than the corpus, and it is confined to pages where the
         * hand is plainly larger than the press for that reason.
         */
        private const val FROM_THE_PRESS = 0.76

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

        /** How far below the middle of its square a printed digit is ever found. */
        private const val PRINT_SITS_NO_LOWER = 0.12

        /** The ink a blob must carry to be believed as print when it sits that low. */
        private const val PRESSED_ENOUGH_TO_SIT_LOW = 0.55
        private const val ANSWER_TOP_LIMIT = -0.13

        /**
         * Ink that excuses a blob for sitting high in its square.
         *
         * See [topLimitFor]. Half the print's ink is far above anything a pencil mark in
         * this corpus carries - they reach 0.255 at the ninetieth percentile - so this is
         * the edge of their population rather than a number chosen to fit.
         */
        private const val INK_THAT_EXCUSES_HEIGHT = 0.50

        /**
         * And how high such a blob may then sit.
         *
         * Swept together with the ink above. Half the print's ink at this height recovers
         * two of the digits the app was dropping and costs nothing at all - no cell sorted
         * wrongly, and no digit invented on an empty square. Letting fainter ink through
         * buys one more digit and a phantom with it, which is the worse trade: a number
         * that is not there is harder to notice than one that is missing.
         */
        private const val INKY_TOP_LIMIT = -0.25

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
