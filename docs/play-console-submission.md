# Sudoku Buddy — Play Console submission sheet

Prepared 8 September 2026. Use this as the copy-and-paste checklist after the Freevia
organization account is verified.

## Create app

| Play Console field | Answer |
| --- | --- |
| Default language | English (United States) — `en-US` |
| App name | Sudoku Buddy |
| App or game | App |
| Free or paid | Free |
| Support email | `info@freevia.org` |
| Package name | `org.freevia.sudokubuddy` |

Accept the Developer Program Policies, US export laws, and Play App Signing terms.
Enrol in Play App Signing when the first bundle is uploaded. The existing release key is
the upload key.

## Main store listing

| Field | Answer or file |
| --- | --- |
| App name | Sudoku Buddy |
| Short description | Scan and check paper Sudoku, with clear hints when you get stuck |
| Full description | Use the text under **Full description** below |
| App icon | `docs/store/icon-512.png` |
| Feature graphic | `docs/store/feature-graphic-1024x500.png` |
| Phone screenshots | Five files listed in the release package's `screenshots/README.md` |
| App category | Education |
| Tags | Sudoku; Puzzle; Education, where those tags are offered |
| Support email | `info@freevia.org` |
| Website | `https://freevia.org` |
| Privacy policy | `https://freevia.org/sudoku-buddy/privacy` |

The feature graphic was created with generative image tooling. If Play Console shows its
asset-level AI-content checkbox, enable the AI label for that graphic. The app icon is
derived from the app's existing artwork and real in-app screenshots are not AI-generated.

### Full description

Sudoku Buddy is a camera companion for Sudoku puzzles in newspapers, books and magazines.
It is not another Sudoku game: it helps you check and understand the puzzle already on paper.

Point your camera at a printed grid. Sudoku Buddy reads the printed clues and your handwritten
progress, then lets you correct any digit it misread.

Use Check to see which handwritten answers are right and which need another look. When you are
stuck, ask for a hint. The app highlights the relevant cells, names the human solving technique
and explains the next deduction. You choose how much help to reveal.

You can also view the solution and reopen scanned puzzles from your on-device history.

Private by design:
- Camera processing happens on your phone
- No account
- No ads
- No analytics
- No internet permission
- No puzzle photographs or progress uploaded to Freevia

Sudoku Buddy is for people who enjoy solving on paper and want a second pair of eyes, not a
replacement game.

## App content declarations

### Privacy policy

- URL: `https://freevia.org/sudoku-buddy/privacy`
- The URL is public, active, non-PDF, and also shown inside the app's About screen.

### Ads

- **Does your app contain ads?** No.

### App access

- **Are all app features available without special access?** Yes.
- No account, login, membership, location restriction, or access code is required.
- Reviewer note if a text box is offered:

  > All functionality is available without an account or login. Grant camera permission,
  > point the camera at a printed Sudoku. After scanning, every recognized digit can be
  > corrected before the user checks handwritten answers, requests a hint or views the
  > solution. Processing and puzzle history stay on the device.

### Target audience and content

- Target age groups: **13–15, 16–17, and 18 and over**.
- The app is not specifically designed for children under 13.
- Store presence does not intentionally appeal to children.
- The app contains no advertising.

These selections describe the intended audience, not whether younger people are capable
of using Sudoku. Do not select an under-13 group unless Freevia intentionally chooses to
enter the Families programme and reassesses the listing against those policies.

### Data safety

- **Does the app collect or share any required user data types?** No.
- The app transmits no data off the device and declares no `INTERNET` permission.
- Camera frames, captured puzzle photographs, recognized digits, corrections, history,
  and settings are processed and stored only on the device.
- Sharing a selected diagnostic photograph happens only when the user invokes Android's
  share sheet. The receiving app is chosen by the user; Sudoku Buddy does not transmit it.
- The app has no account-creation mechanism.
- Privacy policy: `https://freevia.org/sudoku-buddy/privacy`.

Google defines collection for this form as transmitting data off the user's device.
On-device-only access does not count as collection. Recheck this declaration if an SDK,
analytics, crash reporting, advertising, cloud backup, or any network feature is added.

### Content rating questionnaire

Use an email address monitored by Freevia and select the general utility/education app
category offered by the questionnaire. For the current build:

- Violence: No
- Fear or horror: No
- Sexual content or nudity: No
- Profanity or crude humor: No
- Drugs, alcohol, or tobacco: No
- Gambling or simulated gambling: No
- User-generated content: No
- Users communicating or exchanging content: No
- Location sharing: No
- Purchases or paid digital goods: No
- Ads: No
- Unrestricted web access: No

Submit the questionnaire and retain the IARC certificate email. The rating authority,
not this checklist, assigns the final regional ratings.

### Other declarations

- News or magazine app: No
- Government app: No
- Financial features: None
- Health features: None
- VPN service: No
- Account creation: No
- Generative AI app: No. The app recognizes a fixed Sudoku grid and applies deterministic
  solving techniques; it does not generate open-ended text, images, audio, or video from
  user prompts.
- High-risk permissions declaration: none expected. `CAMERA` is the only requested runtime
  permission and is necessary for the app's core scan function.

## Countries and availability

- Make the app available worldwide except where Google Play or applicable law prevents it.
- No device exclusion is needed beyond the manifest requirement for a camera.
- The app is free and has no in-app products or subscriptions.

## Testing and release

1. Upload the signed `app-release.aab` to **Internal testing** first.
2. Add Freevia-controlled tester accounts and verify installation, camera permission,
   scanning, editing, hints, history, sharing, and uninstall behavior.
3. Review Play's automated pre-launch report.
4. Promote the tested bundle to a closed or production track as appropriate for the
   organization account.
5. Use `docs/play-release-notes-en-US.txt` for the first release notes.

Do not upload a locally debug-signed bundle. Use the signed GitHub Actions artifact and
confirm its application ID is `org.freevia.sudokubuddy` before upload.

## Items still requiring a person or Play Console

- D-U-N-S number and organization verification
- The organization's main telephone number, matching public or D&B records
- A public, OTP-capable developer telephone number
- A private contact telephone number for Google
- One-time developer registration payment
- Final answers to any new declarations Play Console adds after this checklist was prepared
