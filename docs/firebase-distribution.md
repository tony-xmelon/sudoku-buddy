# Distributing test builds through Firebase

Product: **Sudoku Buddy**

Android package: **`org.freevia.sudokubuddy`**

Firebase / Google Cloud project ID: **`sudoku-buddy-freevia`** (pending creation)

Firebase App Distribution is a build-time service only. The application does not include
Firebase SDKs, has no `INTERNET` permission, and sends no user data to Firebase.

## Required Firebase setup

1. Sign in to Firebase as `info@freevia.org`.
2. Create the project **Sudoku Buddy** with project ID `sudoku-buddy-freevia`.
3. Register an Android app with package name `org.freevia.sudokubuddy` and nickname
   **Sudoku Buddy Android**.
4. Open App Distribution, enable it for that Android app, and create the tester group
   `testers`.
5. Copy the generated Firebase App ID from Project settings -> General.

The package name is permanent for a Firebase Android registration. Do not reuse a
registration belonging to another package.

`google-services.json` is deliberately not used. App Distribution only needs the
Firebase App ID during the release upload.

## GitHub Actions configuration

The repository needs these Actions secrets:

- `FIREBASE_APP_ID`: the generated App ID for `org.freevia.sudokubuddy`.
- `FIREBASE_TOKEN`: a Firebase CLI refresh token authenticated as `info@freevia.org`.
- The existing Android signing secrets remain unchanged.

Set the values with:

```bash
gh secret set FIREBASE_APP_ID --repo tony-xmelon/sudoku-buddy
gh secret set FIREBASE_TOKEN --repo tony-xmelon/sudoku-buddy
```

The workflow first checks that both values exist. A push to `main` then builds the
signed arm64 APK and distributes it to the `testers` group.

## Distributing from this machine

Authenticate the Firebase CLI as `info@freevia.org`, set the Firebase App ID in the
environment, and run:

```bash
firebase login
FIREBASE_APP_ID=<generated-app-id> ./gradlew :app:distributeLocal
```

On PowerShell:

```powershell
$env:FIREBASE_APP_ID = "<generated-app-id>"
.\gradlew.bat :app:distributeLocal
```

The task deliberately refuses to upload when `FIREBASE_APP_ID` is absent. This prevents
a renamed build from being sent to an obsolete Firebase app registration.

## Testers

Manage testers at:

https://console.firebase.google.com/project/sudoku-buddy-freevia/appdistribution

Or add a tester from the CLI:

```bash
firebase appdistribution:testers:add your.email@example.com --project sudoku-buddy-freevia
```

Adding a tester sends an invitation email, so only add addresses that Freevia intends to
invite.

## Retirement of the previous project

Keep the previous Firebase project intact until a signed Sudoku Buddy build has reached
at least one tester through the new project. After that verification, the previous
project can be disabled or deleted separately.
