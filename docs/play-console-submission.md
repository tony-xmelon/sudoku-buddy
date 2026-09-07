# AI Sudoku — Play Console submission sheet

Prepared 7 September 2026. Use this as the copy-and-paste checklist after the Freevia
organization account is verified.

## Create app

| Play Console field | Answer |
| --- | --- |
| Default language | English (United States) — `en-US` |
| App name | AI Sudoku |
| App or game | App |
| Free or paid | Free |
| Support email | `info@freevia.org` |
| Package name | `org.freevia.aisudoku` |

Accept the Developer Program Policies, US export laws, and Play App Signing terms.
Enrol in Play App Signing when the first bundle is uploaded. The existing release key is
the upload key.

## Main store listing

| Field | Answer or file |
| --- | --- |
| App name | AI Sudoku |
| Short description | Scan printed Sudoku puzzles and learn each solution step by step |
| Full description | Use the text under **Full description** below |
| App icon | `docs/store/icon-512.png` |
| Feature graphic | `docs/store/feature-graphic-1024x500.png` |
| Phone screenshots | Four files listed in `docs/store/screenshots/README.md` |
| App category | Education |
| Tags | Sudoku; Puzzle; Education, where those tags are offered |
| Support email | `info@freevia.org` |
| Website | `https://freevia.org` |
| Privacy policy | `https://freevia.org/aisudoku/privacy` |

The feature graphic was created with generative image tooling. If Play Console shows its
asset-level AI-content checkbox, enable the AI label for that graphic. The app icon is
derived from the app's existing artwork and real in-app screenshots are not AI-generated.

### Full description

AI Sudoku reads a printed Sudoku through your camera and teaches you how to solve it one
step at a time.

Photograph a puzzle from a newspaper or book. The app finds the grid, reads the printed
clues, and gives you an editable board. It can also read handwritten entries, and every
recognized digit can be corrected before you continue.

Ask for a hint when you get stuck. AI Sudoku explains the solving technique, highlights
the relevant cells, and shows why the next move follows. It supports techniques ranging
from naked and hidden singles to subsets, fish patterns, wings, coloring, and forcing
chains. You can also check your entries or reveal the completed solution.

Scanned puzzles are kept in an on-device history so you can continue later.

Privacy is simple: processing happens on your phone. AI Sudoku has no accounts, no ads,
no analytics, and no internet permission. Your photographs and puzzles are not uploaded.

## App content declarations

### Privacy policy

- URL: `https://freevia.org/aisudoku/privacy`
- The URL is public, active, non-PDF, and also shown inside the app's About screen.

### Ads

- **Does your app contain ads?** No.

### App access

- **Are all app features available without special access?** Yes.
- No account, login, membership, location restriction, or access code is required.
- Reviewer note if a text box is offered:

  > All functionality is available without an account or login. Grant camera permission,
  > point the camera at a printed Sudoku grid, and capture it. The recognized board can be
  > edited before using hints, checking entries, or viewing the solution.

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
  share sheet. The receiving app is chosen by the user; AI Sudoku does not transmit it.
- The app has no account-creation mechanism.
- Privacy policy: `https://freevia.org/aisudoku/privacy`.

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
confirm its application ID is `org.freevia.aisudoku` before upload.

## Items still requiring a person or Play Console

- D-U-N-S number and organization verification
- A public, OTP-capable developer telephone number
- A private contact telephone number for Google
- One-time developer registration payment
- Four real in-app phone screenshots
- Final answers to any new declarations Play Console adds after this checklist was prepared

