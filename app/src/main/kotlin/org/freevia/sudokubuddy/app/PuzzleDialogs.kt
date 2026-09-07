package org.freevia.sudokubuddy.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.freevia.sudokubuddy.model.Cell
import org.freevia.sudokubuddy.model.CellSource
import org.freevia.sudokubuddy.solver.Techniques
/*
 * The things that open over the puzzle: choosing a technique, and correcting one square.
 *
 * Split out of PuzzleScreen. Both are modal, both are about a single decision, and
 * neither has anything to do with the screen underneath.
 */
@Composable
internal fun TutorPicker(
    state: PuzzleState,
    onChange: (PuzzleState) -> Unit,
    steps: Int,
    modifier: Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val counts = state.findingCounts

    Box(modifier = modifier) {
        TextButton(
            onClick = { open = true },
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.tutorTechnique ?: "Best route",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = "Choose what to be shown",
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Best route") },
                trailingIcon = { Text("$steps") },
                onClick = {
                    open = false
                    onChange(state.tutor())
                },
            )
            // Only what is actually there. Listing every technique with "none" beside it
            // was a menu of two dozen things you could not choose, and the handful you
            // could were lost in it. What the app knows belongs under Strategies, which is
            // for reading; this menu is for going somewhere.
            val available = Techniques.all.filter { (counts[it.name] ?: 0) > 0 }
            for (technique in available) {
                DropdownMenuItem(
                    text = { Text(technique.name) },
                    trailingIcon = { Text("${counts[technique.name]}") },
                    onClick = {
                        open = false
                        onChange(state.tutor(technique.name))
                    },
                )
            }
            if (available.isEmpty()) {
                DropdownMenuItem(
                    enabled = false,
                    text = { Text("No technique applies here") },
                    onClick = {},
                )
            }
        }
    }
}

/**
 * What the reader was unsure of, and the one action that settles it.
 *
 * This stays until every flagged square has been either corrected or accepted, and then
 * it goes. The first version left the message on screen after the user had dealt with
 * every one, which read as though the app had not noticed.
 */
@Composable
internal fun ReadingBanner(state: PuzzleState, onChange: (PuzzleState) -> Unit) {
    val count = state.uncertainCells.size
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0x22FFB300), RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            if (count == 1) "The app is not sure about one square."
            else "The app is not sure about $count squares.",
            style = MaterialTheme.typography.titleSmall,
            color = Overlays.uncertain,
        )
        // The reader's own reason, when it has one. "One cell looked like a printed
        // digit but is not" says far more than a count does, and it was being thrown away.
        state.readingNote?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "Look for the thick amber bar on the photo. Tap that square to fix it, or " +
                "accept the reading as it stands.",
            style = MaterialTheme.typography.bodySmall,
        )
        FilledTonalButton(onClick = { onChange(state.acceptReading()) }) {
            Text("All correct")
        }
    }
}

/**
 * Correcting one square.
 *
 * A modal sheet with a three-by-three keypad, which is the shape of the thing being
 * chosen. The first version put nine buttons and four more in flat rows at the bottom of
 * a scrolling page, where the last of them wrapped one letter per line.
 */
@Composable
internal fun CellEditor(state: PuzzleState, index: Int, onChange: (PuzzleState) -> Unit) {
    val cell = state.grid[index]
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // The sheet insets its top but not its bottom, so its last button was landing
            // underneath the system navigation buttons.
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Row ${index / 9 + 1}, column ${index % 9 + 1}",
            style = MaterialTheme.typography.titleMedium,
        )
        val report = state.reports?.getOrNull(index)
        Text(
            describeCell(cell, report, corrected = state.reports != null && report == null),
            style = MaterialTheme.typography.bodyMedium,
        )
        report?.secondGuess()?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        Keypad(chosen = cell.digit) { digit ->
            val source = if (cell.source == CellSource.EMPTY) CellSource.GUESS else cell.source
            onChange(state.withCell(index, digit, source).copy(selectedCell = null))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = {
                    onChange(state.withCell(index, null, CellSource.EMPTY).copy(selectedCell = null))
                },
                modifier = Modifier.weight(1f),
            ) { Text("Empty") }

            if (cell.isFilled) {
                OutlinedButton(
                    onClick = {
                        onChange(
                            state.withCell(index, cell.digit, flipped(cell.source))
                                .copy(selectedCell = null)
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (cell.source == CellSource.GIVEN) "Handwritten" else "Printed")
                }
            }
        }

        if (index in state.uncertainCells) {
            Button(
                onClick = {
                    onChange(
                        state.copy(
                            uncertainCells = state.uncertainCells - index,
                            selectedCell = null,
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("That is right") }
        }
    }
}

/**
 * What the square holds, in a sentence.
 *
 * Three cases and they are easy to confuse, which is why this is a function of its own
 * rather than a `when` inside a layout: what the reader said about the square, what the
 * user has since made of it, and - when there was never a reading at all - what is simply
 * there. A square the user corrected has no report any more, and saying "Empty" about a
 * square somebody had just cleared read as though the correction had not taken.
 */
internal fun describeCell(cell: Cell, report: CellReport?, corrected: Boolean): String = when {
    report != null -> report.describe()

    corrected -> when {
        !cell.isFilled -> "You cleared this square."
        cell.source == CellSource.GIVEN -> "You set this to a printed ${cell.digit}."
        else -> "You set this to a handwritten ${cell.digit}."
    }

    !cell.isFilled -> "Empty."
    cell.source == CellSource.GIVEN -> "A printed ${cell.digit}."
    else -> "A handwritten ${cell.digit}."
}

/** Printed becomes handwritten and back, which is the only pair there is to swap between. */
internal fun flipped(source: CellSource): CellSource =
    if (source == CellSource.GIVEN) CellSource.GUESS else CellSource.GIVEN

/**
 * The digits, in the shape of the thing being chosen.
 *
 * Three by three rather than a flat row of nine: the first version put nine buttons and
 * four more in flat rows at the bottom of a scrolling page, where the last of them wrapped
 * one letter per line.
 */
@Composable
private fun Keypad(chosen: Int?, onPick: (Int) -> Unit) {
    for (row in 0 until 3) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            for (column in 0 until 3) {
                val digit = row * 3 + column + 1
                val press = { onPick(digit) }
                if (digit == chosen) {
                    Button(onClick = press, modifier = Modifier.weight(1f)) { Text("$digit") }
                } else {
                    OutlinedButton(onClick = press, modifier = Modifier.weight(1f)) {
                        Text("$digit")
                    }
                }
            }
        }
    }
}

/**
 * Draws the overlay over the photograph.
 *
 * Fills carry meaning, and there is at most one per square. Doubt is drawn as a ring
 * around the square so it never mixes with a fill to make a colour that means nothing,
 * which is what the first version did.
 */
