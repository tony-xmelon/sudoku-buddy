package org.freevia.sudokubuddy.app

import org.freevia.sudokubuddy.recognize.CellReading
import org.freevia.sudokubuddy.recognize.Ink

/**
 * What the reader made of one square, kept so the user can look at it.
 *
 * The classifier's own numbers, not a retelling: without them there is no way to tell a
 * confident misread from a coin flip, and those want different responses - one is a bug
 * worth a photograph, the other is just a hard cell to correct and move on from.
 */
data class CellReport(
    val ink: Ink,
    val digit: Int?,
    /** How likely the classifier thought its answer was, 0 to 1. */
    val confidence: Float,
    val runnerUp: Int?,
    val runnerUpConfidence: Float,
) {
    /**
     * Whether the classifier was really only guessing at this square.
     *
     * A third sure is not a reading, it is the best of nine bad options, and saying
     * "read as a printed 5, 33% sure" states it as a fact and then undermines the fact
     * in the same breath. Below this the square is offered as a question instead.
     */
    val onlyAGuess: Boolean get() = digit != null && confidence < SURE_ENOUGH_TO_SAY

    /** One line for the cell editor. */
    fun describe(): String = when {
        onlyAGuess -> "Not read with any confidence - the closest guess is " +
            "${if (ink == Ink.PRINTED) "a printed" else "a handwritten"} $digit at " +
            "${percent(confidence)}. Please set this square yourself."

        else -> when (ink) {
            Ink.PRINTED -> "Read as a printed $digit, ${percent(confidence)} sure."
            Ink.ANSWER -> "Read as a handwritten $digit, ${percent(confidence)} sure."
            Ink.MARK -> "Read as pencilled candidate marks, and ignored."
            Ink.NONE -> "Read as empty - no ink found."
        }
    }

    /** The second guess, when there was a real contest. */
    fun secondGuess(): String? {
        if (digit == null || runnerUp == null || runnerUpConfidence < 0.02f) return null
        return "Second guess: $runnerUp, ${percent(runnerUpConfidence)}."
    }

    private fun percent(value: Float) = "${Math.round(value * 100)}%"

    companion object {
        /**
         * How sure the classifier must be before the app states what a square holds.
         *
         * Two thirds. Below it the app has no business asserting anything: the runner-up
         * is close enough that the two are competing rather than one winning, and the
         * square is put to the user as an open question.
         */
        const val SURE_ENOUGH_TO_SAY = 0.67f

        fun of(reading: CellReading): CellReport {
            val p = reading.probabilities
                ?: return CellReport(reading.ink, null, 1f, null, 0f)
            val ranked = p.indices.sortedByDescending { p[it] }
            return CellReport(
                ink = reading.ink,
                digit = ranked[0] + 1,
                confidence = p[ranked[0]],
                runnerUp = ranked[1] + 1,
                runnerUpConfidence = p[ranked[1]],
            )
        }
    }
}
