package io.github.tonyxmelon.aisudoku.recognize

import io.github.tonyxmelon.aisudoku.vision.GrayImage
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Grids drawn to order, so the reader can be asked about pages the corpus does not have.
 *
 * The corpus is twelve photographs of one newspaper and one hand, and none of it is
 * committed - so on CI every measurement taken from it skips, and any assumption it never
 * challenged travels unguarded. That is how a printed page whose figures are not all one
 * height reached a phone: nothing in the corpus is set in an old-style font, so nothing
 * ever said the height rule had an assumption in it.
 *
 * These are drawn rather than photographed, which means they can be committed, they run
 * everywhere, and the ground truth is exact rather than transcribed. They are not a
 * substitute for photographs - nothing here has a crease, a shadow, a rubbed-out pencil
 * mark or a camera - and they are not scored as if they were. What they are good for is
 * the assumptions: one font against another, one figure style against another, print with
 * and without a hand over it.
 *
 * The figure styles are drawn by scaling the glyphs rather than by asking for a font that
 * has them, because the fonts on a CI runner are not the fonts on a laptop and a test that
 * silently draws something else is worse than no test. The proportions are the ones
 * measured off Georgia, which is the font that found the fault.
 */
object SyntheticGrid {

    const val SIDE = 1152

    /**
     * How tall each digit is drawn, as a fraction of the tallest.
     *
     * [LINING] is what most fonts do and what the reader assumed of all of them. [OLD_STYLE]
     * is text figures, measured off Georgia: 1 and 2 at x-height, 6 and 8 ascending, the
     * rest between.
     */
    enum class Figures(val heights: Map<Char, Double>) {
        LINING(('1'..'9').associateWith { 1.0 }),
        OLD_STYLE(
            mapOf(
                '1' to 0.74, '2' to 0.74, '3' to 0.98, '4' to 0.98, '5' to 0.96,
                '6' to 1.00, '7' to 0.96, '8' to 1.00, '9' to 0.98,
            )
        ),
    }

    /** A pen is fainter and thinner than a press, which is what the ink measure is for. */
    private const val PEN_GREY = 95
    private const val PRESS_GREY = 0

    /**
     * Draws one grid.
     *
     * [givens] and [answers] are 81 characters each, a digit or a dot. The answers are
     * drawn as a hand would: a different size in every square, turned a little, and in a
     * pen that leaves less ink than the press.
     */
    fun rectified(
        givens: String,
        answers: String? = null,
        figures: Figures = Figures.LINING,
        family: String = Font.SANS_SERIF,
        blur: Double = 0.0,
        noise: Double = 0.0,
        seed: Long = 1,
    ): GrayImage {
        require(givens.length == 81) { "givens must be 81 characters" }
        require(answers == null || answers.length == 81) { "answers must be 81 characters" }

        val image = BufferedImage(SIDE, SIDE, BufferedImage.TYPE_BYTE_GRAY)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color.WHITE
        g.fillRect(0, 0, SIDE, SIDE)

        val cell = SIDE / 9.0
        g.color = Color.BLACK
        for (i in 0..9) {
            val at = (i * cell).toFloat()
            val width = if (i % 3 == 0) 5f else 2f
            g.stroke = BasicStroke(width)
            g.drawLine(at.roundToInt(), 0, at.roundToInt(), SIDE)
            g.drawLine(0, at.roundToInt(), SIDE, at.roundToInt())
        }

        val printed = Font(family, Font.PLAIN, (cell * 0.62).roundToInt())
        val random = Random(seed)

        for (index in 0 until 81) {
            val row = index / 9
            val column = index % 9
            val centreX = (column + 0.5) * cell
            val centreY = (row + 0.5) * cell

            val given = givens[index]
            if (given != '.') {
                draw(g, given, printed, centreX, centreY, PRESS_GREY,
                    tall = figures.heights.getValue(given), turn = 0.0)
                continue
            }
            val answer = answers?.get(index) ?: continue
            if (answer == '.') continue

            // A hand: never the same size twice, never quite straight, and in a pen.
            val hand = Font(family, Font.PLAIN, (cell * random.nextDouble(0.52, 0.72)).roundToInt())
            draw(
                g, answer, hand,
                centreX + random.nextDouble(-cell * 0.06, cell * 0.06),
                centreY + random.nextDouble(-cell * 0.05, cell * 0.05),
                PEN_GREY, tall = 1.0, turn = random.nextDouble(-0.12, 0.12),
            )
        }
        g.dispose()

        var pixels = ByteArray(SIDE * SIDE)
        val raster = image.raster
        for (y in 0 until SIDE) {
            for (x in 0 until SIDE) pixels[y * SIDE + x] = raster.getSample(x, y, 0).toByte()
        }
        if (blur > 0) pixels = blurred(pixels, blur)
        if (noise > 0) pixels = speckled(pixels, noise, Random(seed + 1))
        return GrayImage(SIDE, SIDE, pixels)
    }

    private fun draw(
        g: java.awt.Graphics2D,
        digit: Char,
        font: Font,
        centreX: Double,
        centreY: Double,
        grey: Int,
        tall: Double,
        turn: Double,
    ) {
        val text = digit.toString()
        val metrics = g.getFontMetrics(font)
        val bounds = metrics.getStringBounds(text, g)
        val ascent = metrics.getLineMetrics(text, g).ascent.toDouble()

        val old = g.transform
        g.color = Color(grey, grey, grey)
        g.font = font
        val move = AffineTransform()
        move.translate(centreX, centreY)
        move.rotate(turn)
        // Height only. A short old-style figure is not a squashed lining one, but it does
        // keep the weight of its strokes, which uniform scaling would take away with it -
        // and the weight is half of what the reader measures as ink.
        move.scale(1.0, tall)
        g.transform(move)
        g.drawString(text, (-bounds.width / 2).toFloat(), (ascent - bounds.height / 2).toFloat())
        g.transform = old
    }

    private fun blurred(pixels: ByteArray, radius: Double): ByteArray {
        val out = ByteArray(pixels.size)
        val r = radius.roundToInt().coerceAtLeast(1)
        for (y in 0 until SIDE) {
            for (x in 0 until SIDE) {
                var total = 0
                var count = 0
                for (dy in -r..r) {
                    for (dx in -r..r) {
                        val ny = y + dy
                        val nx = x + dx
                        if (ny in 0 until SIDE && nx in 0 until SIDE) {
                            total += pixels[ny * SIDE + nx].toInt() and 0xFF
                            count++
                        }
                    }
                }
                out[y * SIDE + x] = (total / count).toByte()
            }
        }
        return out
    }

    private fun speckled(pixels: ByteArray, amount: Double, random: Random): ByteArray {
        val out = ByteArray(pixels.size)
        for (i in pixels.indices) {
            val value = (pixels[i].toInt() and 0xFF) + ((random.nextDouble() - 0.5) * 2 * amount)
            out[i] = value.coerceIn(0.0, 255.0).roundToInt().toByte()
        }
        return out
    }
}
