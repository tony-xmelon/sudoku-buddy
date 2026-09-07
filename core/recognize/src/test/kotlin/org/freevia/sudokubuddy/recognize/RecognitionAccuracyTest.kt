package org.freevia.sudokubuddy.recognize

import org.freevia.sudokubuddy.model.CellSource
import org.freevia.sudokubuddy.vision.CorpusFixtures
import org.freevia.sudokubuddy.vision.GateVerdict
import org.freevia.sudokubuddy.vision.OpenCvNatives
import org.freevia.sudokubuddy.vision.StructuralGate
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Measures the Kotlin pipeline against the hand-labelled corpus.
 *
 * These numbers must track the Python prototype in `tools/recognizer/`. If Kotlin
 * inference drifts from the PyTorch model that produced the weights, everything
 * downstream is quietly wrong and only a comparison like this would notice.
 */
class RecognitionAccuracyTest {

    private companion object {
        /**
         * The pages whose writing is the size of their print, and what that still costs.
         *
         * Everything downstream rests on finding the printed digits first, and that rested
         * entirely on size: the print is one population sharing a font, a colour and a
         * size, and the writing was always taller. On these ten it is not. Their
         * handwriting sits at 1.02 to 1.12 of the printed height where the print sits at
         * 0.98 to 1.01, so the printed band swallowed the answers and a finished page came
         * out claiming seventy-odd givens and no puzzle. It cost 220 cells of 729,
         * measured over the nine such pages there were at the time.
         *
         * It now costs 77. What separates them is ink - contrast against the paper of the
         * cell itself, times stroke width, both as fractions of the print's own - and it
         * is applied only to a page that has already shown the fault by finding more
         * printed digits than any sudoku has. Sorting every page that way costs printed
         * digits on pages where nothing was wrong, and lost three of them outright.
         *
         * What is left is 57 cells on twelve pages. The count is a ceiling rather than a
         * target, and it has moved for six different reasons, which is worth separating.
         *
         * It grew by pages joining: 94 on ten, 109 on eleven, 125 on twelve. Each time the
         * new cells were the new page's own and nothing that sorted correctly before
         * sorted wrongly after, so a page from this one reader costs fifteen or sixteen
         * cells here whatever else changes.
         *
         * It then fell from 125 to 100, and that was a fault of a different kind. Forty-
         * seven of those cells were not the print/handwriting collision at all: they were
         * answers thrown away as pencilled candidate marks for being smaller than the
         * print. See [GridReader.ANSWER_MIN]. What is left is the collision proper, and
         * it is a harder thing - measured, no rule over the six things the reader
         * measures does better than about seventy of these cells, where a small learned
         * function over the same six does thirty-three. Clustering has since taken most of
         * that ground without a model and without a threshold; what a learned function
         * might still add is unmeasured, and it would want corpus from a second hand.
         *
         * It then rose from 100 to 110, and that is the third reason and the least
         * obvious: a page that used to be refused outright now reads, so its cells are
         * counted here for the first time.
         *
         * And then fell from 110 to 77, which is the first time the collision itself has
         * given ground rather than the accounting changing. No new threshold: the two
         * populations are simply allowed to settle into the two clusters they lie in
         * instead of being cut apart by a bar. See [GridReader.settle].
         *
         * And from 77 to 62, when the size floor stopped being the only way into the
         * answer band: blur breaks a digit into pieces, and the biggest piece of a whole
         * digit can measure a third of one. See [GridReader.INKY_ENOUGH_ANYWAY].
         *
         * And from 62 to 57, when the shape of the ink joined the five measurements of it
         * as one more axis to settle on. Print is a font and handwriting is not, which is
         * the strongest thing there is here and appears nowhere in how big or how dark a
         * blob is. See [GridReader.unlikeItsTwins]. Being unable to solve a puzzle is no longer
         * treated as being unable to read one, and blue-3 - whose printed digits do not
         * make one puzzle - comes back as a grid to be questioned rather than as nothing
         * at all. It brings ten of these cells with it. Nothing that was counted before
         * moved.
         *
         * And from 57 to 63 on a thirteenth page, which is an addition rather than a
         * regression: a newspaper puzzle finished by a second reader in black marker. Six
         * of its squares land here, five of them printed digits taken for answers. The
         * twelve pages counted before it stand at the same 57 they did.
         *
         * And from 63 to 86 on a fifteenth, which is the same addition again and a larger
         * one: an illustrated puzzle whose clues and answers are one font at one size,
         * differing only in a colour the reader cannot see. Twenty-three of its sixty
         * filled squares. The fourteen pages counted before it are unmoved.
         */
        const val SAME_SIZE_HANDWRITING_MISSORTS = 86

        /**
         * Cells sorted wrongly on the one photograph of a screen.
         *
         * It was 31 and is 20, and the difference was not recognition at all: the cells
         * under those digits were being cut in the wrong places. See
         * [CorpusLabels.faintOnScreen] for what is left.
         */
        const val FAINT_INK_MISSORTS = 20
    }

    private fun setUp() {
        CorpusLabels.requireLabels()
        CorpusFixtures.requireCorpus()
        OpenCvNatives.ensureLoaded { nu.pattern.OpenCV.loadShared() }
    }

    /**
     * The structural half of the problem: which cells hold print, which hold an answer,
     * and which hold nothing but pencilled candidate marks.
     *
     * This is stricter than it looks. One corpus photograph has candidate marks as tall
     * as the printed digits, including ringed pairs taller than any of them, so nothing
     * about it can be settled by size alone.
     */
    @Test
    fun `every cell is sorted into print, handwriting or pencil marks`() {
        setUp()
        val reader = GridReader()
        var right = 0
        var total = 0
        var knownWrong = 0
        var faintWrong = 0
        var drawnWrong = 0
        var fusedWrong = 0
        val wrong = StringBuilder()

        for (file in CorpusFixtures.photos) {
            val truth = CorpusLabels.forPhoto(file.name) ?: continue
            val verdict = assertIs<GateVerdict.Usable>(StructuralGate.assess(CorpusFixtures.load(file)))
            val grid = when (val result = reader.read(verdict.cells)) {
                is ReadResult.Accepted -> result.grid
                is ReadResult.NeedsConfirmation -> result.grid
                is ReadResult.Unreadable -> null
            } ?: continue

            for (i in 0 until 81) {
                total++
                val expected = truth[i].source
                val actual = when (grid[i].source) {
                    CellSource.GIVEN -> CorpusLabels.Source.GIVEN
                    CellSource.GUESS -> CorpusLabels.Source.GUESS
                    CellSource.EMPTY -> CorpusLabels.Source.EMPTY
                }
                if (actual == expected) {
                    right++
                } else if (file.name in CorpusLabels.sameSizeHandwriting) {
                    knownWrong++
                } else if (file.name in CorpusLabels.faintOnScreen) {
                    faintWrong++
                } else if (file.name in CorpusLabels.drawnOver) {
                    drawnWrong++
                } else if ((file.name to i) in CorpusLabels.fusedIntoPrint) {
                    fusedWrong++
                } else {
                    wrong.append("\n  ${file.name} r${i / 9 + 1}c${i % 9 + 1}: $expected read as $actual")
                }
            }
        }
        println("triage: $right/$total cells sorted correctly")
        println("of which on pages that defeat it: $knownWrong")
        println("and on the one photograph of a screen: $faintWrong")
        println("and on pages drawn over in red: $drawnWrong")
        println("and single cells with pencil fused into the print: $fusedWrong")
        assertTrue(
            right + knownWrong + faintWrong + drawnWrong + fusedWrong == total,
            "cells sorted wrongly on pages that should be sorted correctly:$wrong",
        )
        assertTrue(
            knownWrong <= SAME_SIZE_HANDWRITING_MISSORTS,
            "the print/handwriting collision got worse: $knownWrong wrong, " +
                "against $SAME_SIZE_HANDWRITING_MISSORTS when it was measured",
        )
        // Counted apart from the collision on purpose. Two different faults summed into
        // one number is how the collision came to be blamed for a third of the cells it
        // was not responsible for, and that is not worth repeating.
        assertTrue(
            faintWrong <= FAINT_INK_MISSORTS,
            "the faint page got worse: $faintWrong wrong, against $FAINT_INK_MISSORTS",
        )
    }

    @Test
    fun `the classifier reproduces the accuracy measured in Python`() {
        setUp()
        val classifier = DigitClassifier.load()
        var printedRight = 0
        var printedTotal = 0
        var handRight = 0
        var handTotal = 0
        val misreads = mutableMapOf<String, Int>()
        var collidingRight = 0
        var collidingTotal = 0

        for (file in CorpusFixtures.photos) {
            val truth = CorpusLabels.forPhoto(file.name) ?: continue
            val verdict = assertIs<GateVerdict.Usable>(StructuralGate.assess(CorpusFixtures.load(file)))

            // A digit with a line drawn through it is a different shape. See [CorpusLabels.drawnOver].
            if (file.name in CorpusLabels.drawnOver) continue

            CellAnalyzer.inspect(verdict.cells).forEachIndexed { index, ink ->
                val expected = truth[index].digit ?: return@forEachIndexed
                val probabilities = classifier.classify((ink ?: return@forEachIndexed).normalised)
                val predicted = probabilities.indices.maxBy { probabilities[it] } + 1

                val correct = predicted == expected
                if (truth[index].source == CorpusLabels.Source.GIVEN) {
                    printedTotal++; if (correct) printedRight++
                } else {
                    handTotal++; if (correct) handRight++
                }
                if (!correct) {
                    val key = "$expected->$predicted"
                    misreads[key] = (misreads[key] ?: 0) + 1
                }
                // Separately for the pages the triage cannot sort, because that is the
                // claim they are kept for: the sorting defeats it, the reading does not.
                if (file.name in CorpusLabels.sameSizeHandwriting) {
                    collidingTotal++
                    if (correct) collidingRight++
                }
            }
        }
        println("classifier: printed $printedRight/$printedTotal, handwriting $handRight/$handTotal")
        println("on the pages the triage cannot sort: $collidingRight/$collidingTotal digits read")
        println("misreads: " + misreads.entries.sortedByDescending { it.value }.joinToString())

        // Anything less than perfect on printed digits means Kotlin inference has
        // drifted from the model that was trained.
        // Perfect, with no exception. There was one for a while - the screen photograph
        // misread two of its printed digits - and it went away when the cells under them
        // were cut in the right places rather than by making the rule softer.
        assertTrue(printedRight == printedTotal, "printed digits must be perfect: $printedRight/$printedTotal")
        assertTrue(handRight >= handTotal * 0.90, "handwriting regressed: $handRight/$handTotal")
    }
}
