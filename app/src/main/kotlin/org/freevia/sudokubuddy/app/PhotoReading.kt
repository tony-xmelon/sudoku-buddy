package org.freevia.sudokubuddy.app

import android.content.Context
import org.freevia.sudokubuddy.recognize.GridReader
import org.freevia.sudokubuddy.recognize.ReadResult
import org.freevia.sudokubuddy.vision.GateVerdict
import org.freevia.sudokubuddy.vision.StructuralGate

/** What came of pointing the reader at one photograph. */
sealed interface PhotoOutcome {

    /** A puzzle, ready to put on screen. */
    data class Read(val state: PuzzleState) : PhotoOutcome

    /** Nothing usable, and why - phrased for the person holding the phone. */
    data class Refused(val message: String) : PhotoOutcome
}

/**
 * Turning the bytes of a photograph into a puzzle.
 *
 * Lifted out of [CameraScreen] because of where it used to run. `takePicture` was given
 * `getMainExecutor`, so decoding a twelve megapixel JPEG, locating the grid and pushing
 * eighty-one cells through the classifier all happened on the main thread - measured at
 * between 108 and 399 milliseconds on a desktop, and a phone is several times slower
 * again. The screen set a spinner first, but the thread that would have drawn it was the
 * one doing the work, so pressing the shutter looked like pressing nothing.
 *
 * None of this touches the view, so it belongs off the main thread and away from the
 * composable. Call it from a background dispatcher.
 */
object PhotoReading {

    /**
     * Refuses a photograph, keeping it so it can be looked at afterwards.
     *
     * [label] becomes part of the file name and is what the list shows, so it is short
     * and says which check refused; [message] is the sentence the user reads now.
     *
     * Only claim the photograph was kept if it was. The message used to say so
     * unconditionally, which left a user whose write had quietly failed hunting a list
     * for something that had never been put in it.
     */
    private fun refuse(
        context: Context,
        bytes: ByteArray,
        message: String,
        label: String,
    ): PhotoOutcome.Refused {
        val kept = Diagnostics.keep(context, bytes, label)
        return PhotoOutcome.Refused(
            message + if (kept != null) {
                " The photo is in your puzzle list, under \"Would not read\"."
            } else {
                " That photo could not be kept, so there is nothing to send."
            }
        )
    }

    fun read(context: Context, bytes: ByteArray, rotationDegrees: Int): PhotoOutcome {
        val image = Images.fromJpeg(bytes, rotationDegrees)

        return when (val verdict = StructuralGate.assess(image)) {
            is GateVerdict.Rejected ->
                refuse(context, bytes, verdict.reason.message, verdict.reason.toString())

            is GateVerdict.Usable -> {
                val lines = GridLines(
                    vertical = verdict.geometry.verticalLines
                        .map { (it / verdict.rectified.width).toFloat() },
                    horizontal = verdict.geometry.horizontalLines
                        .map { (it / verdict.rectified.height).toFloat() },
                )
                fun puzzle(grid: org.freevia.sudokubuddy.model.Grid,
                           uncertain: Set<Int>, complaint: String?,
                           readings: List<org.freevia.sudokubuddy.recognize.CellReading>) =
                    PuzzleState(
                        photo = Images.toBitmap(verdict.rectified),
                        grid = grid,
                        uncertainCells = uncertain,
                        framingNote = verdict.complaint?.message,
                        readerComplaint = complaint,
                        lines = lines,
                        reports = readings.map(CellReport::of),
                    ).let { read ->
                        // Straight into the reading layer when there is something to
                        // settle. The squares in question are marked on the photograph
                        // and that layer is what shows them, so asking the user to find
                        // and press a button before they can see what is being asked
                        // about put a step between the question and its own answer.
                        //
                        // Asked of the questions that are actually open rather than of
                        // everything the reader flagged, so a page that solves and was
                        // only ever doubted by the solver opens as an ordinary puzzle.
                        if (read.openQuestions.isEmpty()) read
                        else read.copy(overlay = OverlayMode.READING)
                    }

                // The framing complaint travels with the reading instead of replacing it.
                // "The grid was small" is the likeliest explanation for a page of wrong
                // digits, and it is worth saying beside them - but it was never a good
                // reason to refuse a photograph the app had already straightened.
                when (val result = GridReader().read(verdict.cells)) {
                    // Kept for the same reason a photograph the gate turned away is
                    // kept. This path refuses a photograph the gate was happy with -
                    // the grid was found, and the digits in it could not be made into a
                    // puzzle - which is the harder failure of the two to reproduce, and
                    // the one it kept no evidence of at all. A user who could not get a
                    // page to scan was told to look in a list that nothing was ever put
                    // in, and there was nothing to send afterwards.
                    is ReadResult.Unreadable ->
                        refuse(context, bytes, result.reason, "Digits not read")

                    is ReadResult.Accepted -> PhotoOutcome.Read(
                        puzzle(result.grid, emptySet(), null, result.readings)
                    )

                    is ReadResult.NeedsConfirmation -> PhotoOutcome.Read(
                        puzzle(
                            result.grid, result.uncertainCells,
                            result.reason, result.readings,
                        )
                    )
                }
            }
        }
    }
}
