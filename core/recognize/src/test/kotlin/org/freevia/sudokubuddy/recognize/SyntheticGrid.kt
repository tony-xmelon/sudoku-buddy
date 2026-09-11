package org.freevia.sudokubuddy.recognize

import org.freevia.sudokubuddy.vision.GrayImage
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
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
 * substitute for photographs and are not scored as if they were - a drawn page has no
 * camera behind it, and the shortcomings of a real one are not guessable. What they are
 * good for is assumptions: one figure style against another, a hand that presses hard
 * against one that does not, pencil under ink, and a lamp off to one side.
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

    /**
     * How the answers are written, when there are any.
     *
     * [NEAT] is a careful hand at a steady size. [LOOSE] is the one that matters: bigger
     * than the print, leaning, and never the same size twice, which is what the corpus's
     * writer actually does and what the size rule was built on.
     */
    enum class Hand(
        val size: ClosedFloatingPointRange<Double>,
        val slant: ClosedFloatingPointRange<Double>,
        val wobble: Double,
    ) {
        NEAT(0.52..0.66, -0.05..0.05, 1.5),
        LOOSE(0.60..0.86, -0.28..0.20, 4.0),

        /**
         * A hand that fills the square, for use with a small [Page.press].
         *
         * Where a newspaper prints a big grid and a small face, so that a solver writes at
         * about the size of the press, a puzzle booklet prints a small face in a large
         * square and the hand fills it: half again the height of a printed digit, which is
         * what four photographs of one booklet page measure. The reader leans on that gap
         * and this is the only hand here that has it.
         *
         * The gap is made by shrinking the press rather than by swelling the hand. Drawn
         * at the size that ratio would otherwise need, the figures came close enough to
         * the rules that a font metric one platform apart put them into it, which failed
         * on CI and not here. Everything drawn stays inside the sizes the other pages use.
         */
        LARGE(0.62..0.74, -0.12..0.12, 3.0),
    }

    /**
     * One page to draw.
     *
     * Everything that varies between real pages and can be drawn honestly. What cannot -
     * a crease, a fold, the shape of a phone lens - is left to the photographs.
     */
    data class Page(
        val givens: String,
        val answers: String? = null,
        val figures: Figures = Figures.LINING,
        /**
         * The printed face, as a fraction of the square.
         *
         * A newspaper fills its squares and a booklet does not, and the difference is the
         * whole reason a hand can be plainly larger than the press. Left at the newspaper
         * size unless a page says otherwise.
         */
        val press: Double = 0.62,
        val family: String = Font.SANS_SERIF,
        val bold: Boolean = false,
        val hand: Hand = Hand.NEAT,
        /** How dark the pen is against the paper, where the press is 255. */
        val pen: Int = 150,
        /** Fraction of the empty squares carrying pencilled candidate marks. */
        val marks: Double = 0.0,
        /**
         * Fraction of the empty squares carrying a firm, cramped candidate list.
         *
         * The hard kind, and a different thing from [marks]. Those are what a candidate
         * mark is supposed to be - small, faint, and along the top of the square - and
         * both size and position give them away. These are what somebody writing quickly
         * in a small square actually leaves: pressed nearly as hard as an answer, so ink
         * does not separate them, and wrapped onto a second line, so the list as a whole
         * reaches two thirds of the height of a printed digit.
         */
        val firmMarks: Double = 0.0,
        /**
         * Fraction of the empty squares carrying a wrapped list with a candidate struck out.
         *
         * What a solver leaves behind when a candidate is ruled out: the list wraps onto a
         * second line below the middle of the square, and one figure on that line is
         * crossed through. The cancel stroke runs past the figure at both ends, so the
         * ink it belongs to is the height of a printed digit while sitting lower than a
         * printed digit ever sits - which was the one place no rule was looking, since
         * every limit on where a mark sits guards the top, where a list starts.
         */
        val struckMarks: Double = 0.0,
        /** Fraction of the answers written over a rubbed-out digit. */
        val ghosts: Double = 0.0,
        /** A lamp off to one side: 0 is flat, 1 takes a third of the light off one corner. */
        val shadow: Double = 0.0,
        val blur: Double = 0.0,
        val noise: Double = 0.0,
        val seed: Long = 1,
    ) {
        init {
            require(givens.length == 81) { "givens must be 81 characters" }
            require(answers == null || answers.length == 81) { "answers must be 81 characters" }
        }
    }

    private const val PRESS = 0

    fun rectified(page: Page): GrayImage {
        val image = BufferedImage(SIDE, SIDE, BufferedImage.TYPE_BYTE_GRAY)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(
            RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON
        )
        g.color = Color.WHITE
        g.fillRect(0, 0, SIDE, SIDE)

        val cell = SIDE / 9.0
        g.color = Color.BLACK
        for (i in 0..9) {
            val at = (i * cell).roundToInt()
            g.stroke = BasicStroke(if (i % 3 == 0) 5f else 2f)
            g.drawLine(at, 0, at, SIDE)
            g.drawLine(0, at, SIDE, at)
        }

        val weight = if (page.bold) Font.BOLD else Font.PLAIN
        val printed = Font(page.family, weight, (cell * page.press).roundToInt())
        val random = Random(page.seed)

        for (index in 0 until 81) {
            val centreX = (index % 9 + 0.5) * cell
            val centreY = (index / 9 + 0.5) * cell

            val given = page.givens[index]
            if (given != '.') {
                draw(g, given, printed, centreX, centreY, PRESS, page.figures.heights.getValue(given))
                continue
            }

            val answer = page.answers?.get(index)?.takeIf { it != '.' }
            if (answer == null) {
                if (page.struckMarks > 0 && random.nextDouble() < page.struckMarks) {
                    struckMarks(g, page, cell, centreX, centreY, random)
                } else if (page.firmMarks > 0 && random.nextDouble() < page.firmMarks) {
                    firmMarks(g, page, cell, centreX, centreY, random)
                } else if (page.marks > 0 && random.nextDouble() < page.marks) {
                    pencilMarks(g, page, cell, centreX, centreY, random)
                }
                continue
            }

            // The ghost goes down first, and the answer over it - which is the order it
            // happened in, and the reason the two touch.
            if (page.ghosts > 0 && random.nextDouble() < page.ghosts) {
                val rubbed = ('1' + random.nextInt(9))
                written(
                    g, rubbed, page, cell,
                    centreX + random.nextDouble(-cell * 0.08, cell * 0.08),
                    centreY + random.nextDouble(-cell * 0.06, cell * 0.06),
                    grey = 255 - ((255 - page.pen) * 0.28).roundToInt(), random = random,
                )
            }
            written(g, answer, page, cell, centreX, centreY, page.pen, random)
        }
        g.dispose()

        var pixels = ByteArray(SIDE * SIDE)
        val raster = image.raster
        for (y in 0 until SIDE) {
            for (x in 0 until SIDE) pixels[y * SIDE + x] = raster.getSample(x, y, 0).toByte()
        }
        if (page.shadow > 0) pixels = lit(pixels, page.shadow)
        if (page.blur > 0) pixels = blurred(pixels, page.blur)
        if (page.noise > 0) pixels = speckled(pixels, page.noise, Random(page.seed + 1))
        return GrayImage(SIDE, SIDE, pixels)
    }

    /** A printed digit: one size, one weight, sitting where the press put it. */
    private fun draw(
        g: Graphics2D,
        digit: Char,
        font: Font,
        centreX: Double,
        centreY: Double,
        grey: Int,
        tall: Double,
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
        // Height only. A short old-style figure is not a squashed lining one, but it does
        // keep the weight of its strokes, which uniform scaling would take away with it -
        // and the weight is half of what the reader measures as ink.
        move.scale(1.0, tall)
        g.transform(move)
        g.drawString(text, (-bounds.width / 2).toFloat(), (ascent - bounds.height / 2).toFloat())
        g.transform = old
    }

    /**
     * An answer, written rather than printed.
     *
     * Four things separate a hand from a press and all four are drawn: it is a different
     * size in every square, it leans, it is not centred, and it is fainter because a pen
     * leaves less on the paper than a press does. The wobble is a small rotation on top of
     * the slant - a hand does not repeat itself, which is the whole basis of the rule that
     * takes a rank of identical figures back from the handwriting.
     */
    private fun written(
        g: Graphics2D,
        digit: Char,
        page: Page,
        cell: Double,
        centreX: Double,
        centreY: Double,
        grey: Int,
        random: Random,
    ) {
        val hand = page.hand
        val size = (cell * random.nextDouble(hand.size.start, hand.size.endInclusive)).roundToInt()
        val font = Font(page.family, Font.PLAIN, size)
        val text = digit.toString()
        val metrics = g.getFontMetrics(font)
        val bounds = metrics.getStringBounds(text, g)
        val ascent = metrics.getLineMetrics(text, g).ascent.toDouble()

        val old = g.transform
        g.color = Color(grey, grey, grey)
        g.font = font
        val move = AffineTransform()
        move.translate(
            centreX + random.nextDouble(-cell * 0.06, cell * 0.06),
            centreY + random.nextDouble(-cell * 0.05, cell * 0.05),
        )
        move.rotate(random.nextDouble(-hand.wobble, hand.wobble) * Math.PI / 180)
        move.shear(random.nextDouble(hand.slant.start, hand.slant.endInclusive), 0.0)
        g.transform(move)
        g.drawString(text, (-bounds.width / 2).toFloat(), (ascent - bounds.height / 2).toFloat())
        g.transform = old
    }

    /**
     * Pencilled candidates: small, faint, and along the top of the square.
     *
     * Where they sit is the point. The reader tells a mark from an answer by height and by
     * how much ink it carries, and marks are written above the middle of the cell in a
     * pencil that leaves a fraction of what a pen does.
     */
    private fun pencilMarks(
        g: Graphics2D,
        page: Page,
        cell: Double,
        centreX: Double,
        centreY: Double,
        random: Random,
    ) {
        val grey = 255 - ((255 - page.pen) * 0.30).roundToInt()
        val font = Font(page.family, Font.PLAIN, (cell * 0.26).roundToInt())
        val how = 2 + random.nextInt(3)
        val digits = (1..9).shuffled(random).take(how).sorted()
        val step = cell * 0.20
        val start = centreX - step * (how - 1) / 2.0
        for ((i, digit) in digits.withIndex()) {
            draw(g, '0' + digit, font, start + i * step, centreY - cell * 0.30, grey, 1.0)
        }
    }

    /**
     * A candidate list written firmly and cramped onto two lines. See [Page.firmMarks].
     *
     * Nothing here is faint and nothing sits far enough up the square to be caught by
     * where it is. What is left to know it by is that its figures are small, and that
     * the square is on a page whose handwriting is not.
     */
    private fun firmMarks(
        g: Graphics2D,
        page: Page,
        cell: Double,
        centreX: Double,
        centreY: Double,
        random: Random,
    ) {
        val grey = 255 - ((255 - page.pen) * 0.95).roundToInt()
        val font = Font(page.family, Font.BOLD, (cell * page.press * 0.72).roundToInt())
        val how = 2 + random.nextInt(2)
        val digits = (1..9).shuffled(random).take(how).sorted()
        val step = cell * page.press * 0.48
        val start = centreX - step * (how - 1) / 2.0
        // Wherever there was room, which is not always the top: across the four booklet
        // photographs these sit anywhere from a fifth of a square above centre to a
        // quarter below it, so position is no more reliable here than ink is.
        val drop = random.nextDouble(-cell * 0.14, cell * 0.12)
        for ((i, digit) in digits.withIndex()) {
            draw(g, '0' + digit, font, start + i * step, centreY + drop, grey, 1.0)
        }
    }

    /**
     * A wrapped candidate list with one figure crossed out. See [Page.struckMarks].
     *
     * Three figures along the top and two below them, and a stroke through one of the
     * lower pair. The stroke is what matters: it is drawn past the figure at both ends,
     * so what it joins is taller than the figure alone and reaches the printed band while
     * staying where no printed digit sits.
     */
    private fun struckMarks(
        g: Graphics2D,
        page: Page,
        cell: Double,
        centreX: Double,
        centreY: Double,
        random: Random,
    ) {
        val grey = 255 - ((255 - page.pen) * 0.95).roundToInt()
        val font = Font(page.family, Font.BOLD, (cell * page.press * 0.72).roundToInt())
        val digits = (1..9).shuffled(random).take(5).sorted()
        val step = cell * page.press * 0.48
        val lower = centreY + cell * 0.18
        for ((i, digit) in digits.withIndex()) {
            val line = if (i < 3) 0 else 1
            val onLine = if (line == 0) 3 else 2
            val start = centreX - step * (onLine - 1) / 2.0
            draw(
                g, '0' + digit, font,
                start + (i % 3) * step,
                if (line == 0) centreY - cell * 0.22 else lower,
                grey, 1.0,
            )
        }
        // Through one of the lower pair, chosen at random.
        val struck = centreX - step / 2.0 + random.nextInt(2) * step
        g.color = Color(grey, grey, grey)
        g.stroke = BasicStroke((cell * 0.035).toFloat())
        val reach = cell * 0.145
        g.drawLine(
            (struck - cell * 0.05).roundToInt(), (lower - reach).roundToInt(),
            (struck + cell * 0.05).roundToInt(), (lower + reach).roundToInt(),
        )
    }

    /** A lamp off to one side, so no two squares share a background. */
    private fun lit(pixels: ByteArray, strength: Double): ByteArray {
        val out = ByteArray(pixels.size)
        for (y in 0 until SIDE) {
            for (x in 0 until SIDE) {
                val across = (x + y) / (2.0 * SIDE)
                val scale = 1.0 - strength * across
                val value = (pixels[y * SIDE + x].toInt() and 0xFF) * scale
                out[y * SIDE + x] = value.coerceIn(0.0, 255.0).roundToInt().toByte()
            }
        }
        return out
    }

    private fun blurred(pixels: ByteArray, radius: Double): ByteArray {
        val out = ByteArray(pixels.size)
        val r = radius.roundToInt().coerceAtLeast(1)
        val wide = IntArray(pixels.size)
        for (y in 0 until SIDE) {
            for (x in 0 until SIDE) {
                var total = 0
                var count = 0
                for (dx in -r..r) {
                    val nx = x + dx
                    if (nx in 0 until SIDE) {
                        total += pixels[y * SIDE + nx].toInt() and 0xFF
                        count++
                    }
                }
                wide[y * SIDE + x] = total / count
            }
        }
        for (y in 0 until SIDE) {
            for (x in 0 until SIDE) {
                var total = 0
                var count = 0
                for (dy in -r..r) {
                    val ny = y + dy
                    if (ny in 0 until SIDE) {
                        total += wide[ny * SIDE + x]
                        count++
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
