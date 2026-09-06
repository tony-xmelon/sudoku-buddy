package io.github.tonyxmelon.aisudoku.vision

import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

/** Pixel bounds of one cell in the rectified image. */
data class CellBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Where the ten vertical and ten horizontal grid lines actually are, in rectified
 * coordinates.
 */
data class CellGeometry(
    val verticalLines: List<Double>,
    val horizontalLines: List<Double>,
) {
    init {
        require(verticalLines.size == 10) { "expected 10 vertical lines, got ${verticalLines.size}" }
        require(horizontalLines.size == 10) { "expected 10 horizontal lines, got ${horizontalLines.size}" }
    }

    companion object {
        /**
         * Even ninths of the rectified square, for a grid whose rules cannot be measured.
         *
         * The fitted lines are better than this and are used wherever they can be had -
         * paper is not flat, and dividing by nine leaves cells progressively out of place
         * towards the edges. But a grid that has been found and straightened and then
         * cannot be measured is still a grid, and cutting it into ninths reads most of it.
         * The alternative was to throw the photograph away at the last step.
         */
        fun evenNinths(side: Int): CellGeometry {
            val lines = (0..9).map { it * side / 9.0 }
            return CellGeometry(lines, lines)
        }
    }

    /**
     * Bounds of cell [index] (row-major), inset so the printed lines themselves are
     * excluded. Ink that touches a grid line would otherwise be mistaken for a digit.
     */
    fun cellBounds(index: Int, marginFraction: Double = 0.12): CellBounds {
        val row = index / 9
        val column = index % 9
        val left = verticalLines[column]
        val right = verticalLines[column + 1]
        val top = horizontalLines[row]
        val bottom = horizontalLines[row + 1]
        val marginX = (right - left) * marginFraction
        val marginY = (bottom - top) * marginFraction
        return CellBounds(
            left = (left + marginX).toInt(),
            top = (top + marginY).toInt(),
            right = (right - marginX).toInt(),
            bottom = (bottom - marginY).toInt(),
        )
    }
}

/**
 * Locates the real grid lines in a rectified image.
 *
 * A single perspective transform assumes the page was flat. Two corpus photographs are
 * not - one sheet is curled, another is bowed over a clipboard - so the true lines drift
 * from an even ninth division and cropping by ninths clips digits near the edges.
 *
 * Each line is found by taking the strongest ink projection within a window around where
 * it ought to be, then refining to the intensity-weighted centre of that peak.
 */
object GridLineFitter {

    private const val SEARCH_WINDOW_FRACTION = 0.18

    /** Returns null when a line could not be found near one of the expected positions. */
    /**
     * How many of the ten lines on one axis may be put where the grid says rather than
     * found. Two of ten is an occlusion; more than that is not a grid.
     */
    private const val MOST_LINES_ASSUMED = 2

    /** How far the whole grid may sit from filling the straightened square, in cells. */
    private const val MOST_DRIFT = 0.6
    private const val DRIFT_STEP = 0.02

    /**
     * How much better a moved grid must score before it is preferred to the plain ninths.
     *
     * The straightened square usually is the grid, and on those photographs hunting each
     * line near its own ninth is right and this search only has the chance to be wrong.
     * It is worth taking only when it finds a great deal more line than the ninths do -
     * measured, requiring a fifth more keeps every page that was already right and still
     * rescues the one whose columns had slipped a whole cell.
     */
    private const val WORTH_MOVING_FOR = 1.20

    /** And how much wider or narrower than a ninth its cells may be. */
    private const val MOST_STRETCH = 0.12
    private const val STRETCH_STEP = 0.004

    fun fit(rectified: GrayImage): CellGeometry? {
        val binary = Mat()
        Imgproc.adaptiveThreshold(
            rectified.toMat(), binary, 255.0,
            Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, 31, 10.0,
        )
        val (columns, rows) = GridScorer.projections(binary)
        val vertical = fitAxis(columns) ?: return null
        val horizontal = fitAxis(rows) ?: return null
        return CellGeometry(vertical, horizontal)
    }

    /**
     * Where the ten lines sit, found as one regular grid before any of them is refined.
     *
     * Each line used to be hunted near its nominal ninth, within a fifth of a cell. That
     * assumes the straightened square *is* the grid, and it is not always: a quad that took
     * in a little margin - which the rescues that grow a candidate, or accept an obscured
     * one, will hand over - puts the real lines progressively further from their nominal
     * places, until the ones at the far end fall outside the window entirely and are
     * assumed at nominal instead. On the screen photograph that pushed the right-hand
     * columns almost a full cell across: the eighth column held the ninth column's digit
     * and the ninth held nothing but a rule.
     *
     * A grid is regular, so where it starts and how far apart its lines are can be fitted
     * before asking where any single line is. Every start and spacing within reach is
     * scored by how much line there is at the ten places it implies, and the best is kept.
     * Ten measurements of one two-parameter shape survive a few missing lines, which is
     * exactly the case this has to hold up in.
     */
    private fun regularGrid(profile: DoubleArray): Pair<Double, Double> {
        val size = profile.size
        val nominal = (size - 1.0) / 9.0
        var best = 0.0 to nominal
        var bestScore = (0..9).sumOf { line ->
            val at = (line * nominal).toInt()
            if (at in profile.indices) profile[at] else 0.0
        } * WORTH_MOVING_FOR
        var offset = -nominal * MOST_DRIFT
        while (offset <= nominal * MOST_DRIFT) {
            var pitch = nominal * (1 - MOST_STRETCH)
            while (pitch <= nominal * (1 + MOST_STRETCH)) {
                if (offset + 9 * pitch <= size - 1 + nominal * MOST_DRIFT) {
                    var score = 0.0
                    for (line in 0..9) {
                        val at = (offset + line * pitch).toInt()
                        if (at in profile.indices) score += profile[at]
                    }
                    if (score > bestScore) {
                        bestScore = score
                        best = offset to pitch
                    }
                }
                pitch += nominal * STRETCH_STEP
            }
            offset += nominal * DRIFT_STEP
        }
        return best
    }

    private fun fitAxis(profile: DoubleArray): List<Double>? {
        val size = profile.size
        val strongest = profile.max()
        if (strongest <= 0.0) return null

        val window = (size / 9.0 * SEARCH_WINDOW_FRACTION).toInt().coerceAtLeast(4)
        val (start, pitch) = regularGrid(profile)

        // A line that cannot be made out is put where the grid says it must be, rather
        // than failing the whole fit.
        //
        // Every one of the ten had to be found, and one hidden line - a thumb on the
        // corner, a highlight across a screen - lost the photograph after the locator had
        // already recognised the grid. That is the wrong answer twice over: the grid is
        // regular, so a rule nobody can see is still at a position everybody can compute,
        // and a person reading a partly covered puzzle does exactly this without noticing.
        //
        // Only a few may be assumed. Beyond that the evidence for this being a grid at all
        // is gone and the fit should fail, which is what [MOST_LINES_ASSUMED] is for.
        var assumed = 0
        val lines = (0..9).map { line ->
            val nominal = start + line * pitch
            val centre = nominal.toInt().coerceIn(0, size - 1)
            val from = (centre - window).coerceAtLeast(0)
            val to = (centre + window).coerceAtMost(size - 1)

            val peak = (from..to).maxOf { profile[it] }
            if (peak < strongest * 0.20) {
                assumed++
                return@map nominal
            }

            // Intensity-weighted centre of everything near the peak, so a line two or
            // three pixels wide resolves to its middle rather than its first pixel.
            val cutoff = peak * 0.6
            var weighted = 0.0
            var weight = 0.0
            for (i in from..to) {
                if (profile[i] >= cutoff) {
                    weighted += i * profile[i]
                    weight += profile[i]
                }
            }
            if (weight <= 0.0) {
                assumed++
                return@map nominal
            }
            weighted / weight
        }
        if (assumed > MOST_LINES_ASSUMED) return null

        // Ordering can break if two expected windows lock onto the same thick line.
        if (lines != lines.sorted()) return null
        if (lines.zipWithNext().any { (a, b) -> abs(b - a) < size / 9.0 * 0.4 }) return null
        return lines
    }
}
