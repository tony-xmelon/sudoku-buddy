# Registering Freevia for Google Play

This is the registration path for publishing free Android apps under the Freevia name.

## Have these ready

- Google account: `info@freevia.org`
- Account type: **Organization**
- Public developer name: **Freevia**
- Organization name: **Freevia** — enter it exactly as shown in the organization's official
  records and payments profile
- Website: `https://freevia.org`
- Public developer/support email: `info@freevia.org`
- A telephone number that can receive Google's verification code
- The organization's D-U-N-S number
- Company-registration document and the authorized representative's valid photo ID, if
  Google requests them
- A supported debit or credit card for the one-time USD 25 registration fee

The USD 25 registration fee applies even when every app is free. A Google Play merchant
account, paid-app setup, tax profile for sales, and in-app billing are not needed while
Freevia publishes only free apps with no purchases.

## Registration sequence

1. Sign in to `https://play.google.com/console/signup` as `info@freevia.org`.
2. Choose **Organization** as the developer account type.
3. Create or select the organization payments profile. Enter the legal name and address
   exactly as they appear in the company and D-U-N-S records.
4. Enter the D-U-N-S number and the requested organization details.
5. Set the public developer name to **Freevia**, the website to `https://freevia.org`, and
   the public developer email to `info@freevia.org`.
6. Verify the contact email and telephone number.
7. Pay the one-time USD 25 registration fee and save the receipt email.
8. Complete identity and organization verification. Upload only documents Google asks for;
   names and addresses must match the payments profile exactly.
9. Wait for approval before creating the production release. Verification can take several
   days; obtaining a new D-U-N-S number can take longer.

Google's current references:

- Account types and organization requirements:
  https://support.google.com/googleplay/android-developer/answer/10840893
- Registration fee and developer verification:
  https://support.google.com/android-developer-console/answer/16604405
- Verification documents:
  https://support.google.com/googleplay/android-developer/answer/15633622

## First app after approval

1. Choose **Create app**.
2. Use **Sudoku Buddy** as the title, choose **App**, and choose **Free**.
3. Use `info@freevia.org` as the support email and accept the required declarations.
4. Upload the signed AAB from the GitHub Actions artifact named `sudoku-buddy-bundle`.
   Its permanent application ID is `org.freevia.sudokubuddy`.
5. Enrol in Play App Signing. The existing Sudoku Buddy release key becomes the upload key.
6. Complete the store listing and App content declarations in `docs/play-store.md`.
7. Run an internal test, then a closed test, before production.

Do not create a merchant account or add monetization merely to publish a free app. Those
steps can be added later if Freevia introduces paid apps, subscriptions, or in-app products.

One pricing rule is permanent: after an app has been offered for free, Google Play will not
let that same package become a paid download. In-app products or subscriptions can still be
added later, but charging up front would require a new app and package name.
