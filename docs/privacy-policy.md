# Privacy policy for Sudoku Buddy

Last updated 8 September 2026.

Sudoku Buddy is published by **Freevia**.
Questions about this policy or the app's handling of data can be sent to
[info@freevia.org](mailto:info@freevia.org).

## The short version

Freevia does not collect or receive personal data from Sudoku Buddy. The app has no account,
ads, analytics or internet permission. Camera processing and puzzle history stay on your
device. A photograph leaves the app only when you deliberately share it with another app.

## What stays on your device

Everything the app keeps stays on your phone, in storage that belongs to the app:

| What | Where | Why |
| --- | --- | --- |
| Photographs of puzzles you scan | the app's private files directory | so a puzzle can be reopened from the history |
| The digits read from each puzzle, and your corrections | the app's private files directory | so your progress survives closing the app |
| The last few photographs the app *refused* to read | the app's external files directory | so you can look at what went wrong, or send it to the developer |
| Your settings | the app's private preferences | so they persist |

The refused photographs are the one category kept outside the app's fully private area.
They live in the app's own folder on shared storage, which on most phones a file manager
or a USB connection can reach. This is deliberate — it is what makes them possible to
share — but it is worth knowing they are not as sealed off as the rest.

The app keeps at most six refused photographs and discards the oldest beyond that. You can
delete any of them from the puzzle history at any time.

## No network connection

The app declares no `INTERNET` permission. This is not a promise about intent, it is a
restriction Android enforces: the app is incapable of making a network connection, and any
attempt would be refused by the operating system rather than by our own code. It also
removes the `ACCESS_NETWORK_STATE` permission that arrives through a dependency, so the
permission list does not imply a capability the app does not have.

There is no crash reporting, no analytics and no advertising identifier.

## Sharing is your choice

The app can hand a photograph to another app — a mail client, a messaging app — when you
press Share. That happens only when you press it, one file at a time, and the receiving
app is granted read access to exactly the file you chose and to nothing else.

Once a photograph reaches another app, this policy stops applying to it and that app's own
policy takes over.

## Camera processing

The app asks for camera access because reading a puzzle from a printed page is what it
does. The camera preview is processed on the phone, frame by frame, and frames are not
recorded — only the photograph you actually capture is written to storage.

## Deleting your data

Uninstalling the app removes everything it stored, including the photographs. The app sets
`allowBackup="false"`, so its data is not copied into Google's backup service either.

## Children

Sudoku Buddy is intended for a general audience aged 13 and over. Freevia does not collect
personal data from users of any age through the app.

## Changes to this policy

If this policy changes, the date at the top changes with it, and the history of this file
is public in the repository it lives in.

## Contact

Email [info@freevia.org](mailto:info@freevia.org). You can also raise a public technical
issue at https://github.com/tony-xmelon/sudoku-buddy/issues.
