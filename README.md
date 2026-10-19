# Spend Tracker (Android)

A native Android app that reads bank and UPI SMS on the phone, turns each one into a debit or credit transaction, puts it in a spend category and shows where the money goes. To catch payments whose SMS never arrived, it can also read bank alert emails (Gmail), Gmail/bank/UPI-app notifications, and bank statement PDFs. Everything stays on the device: there's no account and no server. The only network use is reading your own Gmail, directly from Google, if you connect it.

This is Phase 1 (MVP) of the build handoff, complete:

- Onboarding with an SMS disclosure, the permission request, a scan-range picker (1, 3, 6 or 12 months) and a live scan count. If permission is denied you get a retry path and a shortcut to app settings.
- A one-time inbox scan (WorkManager) plus a live `SmsReceiver` for new SMS.
- A data-driven parser (`assets/bank_patterns.json`) for HDFC, ICICI, SBI, Axis, Kotak, Paytm, PhonePe and GPay, with credit-card alerts and a generic fallback.
- OTPs, promos, failed, declined and reversed transactions, payment requests and due reminders are ignored.
- Duplicates are removed when the bank and the UPI app both send an SMS for one payment. Self-transfers are detected and excluded.
- Categories are rule-based (`assets/merchant_rules.json`). A user rule always wins, and "Apply to all from this merchant?" teaches it.
- Home: spent, received and net, a budget bar, a category donut, the last 5 transactions and an "N to review" chip.
- Transactions: grouped by day with daily totals, search, and filters for period, type, category, account and mode. Swipe right to exclude, swipe left to change category.
- Detail: category picker, note, exclude toggle, delete and the original SMS text. Manual cash entry with edit.
- Insights: month picker, this month vs last (cumulative line), category bars, top merchants and category changes.
- Review: uncategorised transactions and SMS the parser couldn't read, with quick fixes.
- Settings: accounts (rename, latest balance or limit), categories (add, rename, recolour, merge, forget learned rules), app lock, scan range, re-scan, reset and rebuild, theme, wallpaper colours, and about and privacy.
- Security: Room on SQLCipher, with a random key wrapped by Android Keystore. Biometric or device-PIN lock on open and after 1 minute away. `FLAG_SECURE`, no backups, and log calls are stripped from release builds.
- Extra sources: bank statement PDF import, a notification reader (Gmail plus bank and UPI apps) and Gmail sync. See below.

## Bills, daily summary and date filters

- **Bills and due dates:** credit-card dues, EMIs and electricity, mobile, broadband, gas, water and insurance reminders are picked up from SMS, emails and notifications. They show on Home under "Upcoming bills" and in **Settings → Bills and due dates**. A bill is marked paid automatically when a payment of the same amount (total or minimum due) arrives, and you can mark it paid or dismiss it yourself.
- **Daily summary:** every evening (9 PM by default, adjustable in **Settings → Notifications**) you get a notification with today's spend, the top category, budget left and bills due in the next 3 days. **Send today's summary now** tests it straight away.
- **Date filters:** Home and Transactions have Today, Yesterday, This week, This month, Last month and **Pick dates** for any custom range. Tapping a category in "Where it went" opens its transactions for the same period.

## Other sources (beyond SMS)

All three end up in the same pipeline as SMS (parse, dedupe, categorise), so a payment seen in an SMS *and* an email *and* a notification is counted once.

### 1. Bank statement PDF
**Settings → Import bank statement → Choose statement PDF.**
- Password-protected PDFs are supported. The password is used once and not stored.
- Rows already captured from SMS, email or notifications are shown as "already captured" and skipped.
- Debit vs credit comes from the Dr/Cr marker, then the running balance, then keywords. If a bank's layout comes out wrong, add an anonymised text extract to `StatementParserTest.kt`.
- Scanned (image-only) PDFs can't be read.

### 2. Notifications (offline)
**Settings → Read notifications → turn Spend Tracker on.** If Android says "Restricted setting", go to Settings → Apps → Spend Tracker → ⋮ → **Allow restricted settings**, then try again.
- Reads notifications only from Gmail, Outlook and the bank / UPI apps listed in `bank_patterns.json` (`emailApps` and each bank's `packages`). Every other app is ignored unread.
- Non-transaction notifications are discarded, not stored.
- Only catches new notifications from the moment it's turned on.

### 3. Gmail (past + new bank emails)
Read-only access via Google's on-device sign-in. One-time setup in Google Cloud (about 10 minutes, free):

1. Go to **console.cloud.google.com** and create a project (e.g. "Spend Tracker").
2. **APIs & Services → Library**: search for **Gmail API** and click **Enable**.
3. **Google Auth Platform → Branding** (OAuth consent screen): app name "Spend Tracker", your email as the support and developer contact, then save. Under **Audience**, choose **External** and keep it in **Testing**, then add your Gmail address under **Test users**.
4. **Data access → Add or remove scopes**: add `https://www.googleapis.com/auth/gmail.readonly` and save.
5. **Clients → Create client → Android**:
   - Package name: `app.spendtracker.debug`
   - SHA-1: `7C:04:E2:D1:55:08:EA:9F:D0:9C:66:23:85:87:44:94:B8:6A:02:B4`
   This SHA-1 belongs to `app/debug.keystore`, which is committed on purpose so that every GitHub build is signed with the same key. Don't use it for a Play Store release.
6. In the app: **Settings → Gmail → Connect Gmail**, pick your Google account in the phone's account chooser, then allow "Read your email" on Google's screen. On "Google hasn't verified this app", tap **Continue**; it's your own app.

After that, emails sync every 6 hours (and with **Sync now**). The first sync goes back as far as the SMS scan range. If anything is misconfigured, the exact reason appears in red under Gmail (for example, a package name / SHA-1 mismatch or the Gmail API not being enabled). While the Google project is in Testing mode, Google may ask you to approve again after about 7 days; tap **Approve access** when shown.

## Build and install on your phone

1. Install **Android Studio** (latest stable). It brings JDK 17+ and the SDK. In SDK Manager, install Android SDK Platform 35 and Platform-Tools.
2. On the phone: open Settings → About phone and tap **Build number** 7 times. Then go to Developer options and turn on **USB debugging**. Connect the phone by USB and accept the prompt.
3. In Android Studio choose **File → Open**, select this `spend-tracker` folder and let the Gradle sync finish. The first sync downloads the dependencies.
4. Select your phone in the device dropdown and press **Run ▶**. You can also use a terminal:
   ```
   ./gradlew installDebug        # Windows: gradlew.bat installDebug
   ./gradlew testDebugUnitTest   # parser / categoriser / dedupe tests
   ```
   The debug build installs as `app.spendtracker.debug`, so it can sit next to a future release build.

If Android Studio offers to upgrade AGP or Gradle, accepting is fine.

## Teaching it your bank's SMS

Bank wording varies and changes over time, so the patterns are data, not code.

1. When an SMS isn't understood it shows up in **Review → Bank SMS not understood** with its full text.
2. Add a pattern for it to that bank in `app/src/main/assets/bank_patterns.json`. You can use the named groups `amount`, `last4`, `merchant`, `ref`, `balance`, `limit` and `date`, and the macros `{AMT}`, `{CUR}`, `{MASK}` and `{DATE}`.
3. Add the anonymised SMS as a test in `app/src/test/.../ParserEngineTest.kt` and run `./gradlew testDebugUnitTest`.
4. Reinstall the app, then tap **Review → Retry all** (or **Settings → Reset & rebuild**).

## Project layout

```
app/src/main/java/app/spendtracker/
  data/db/          Room entities, DAOs, AppDatabase (SQLCipher)
  data/security/    DatabaseKeyManager (Keystore-wrapped DB key)
  data/sms/         SmsReader (inbox), SmsReceiver (live), SmsProcessor (pipeline for every source)
  data/notify/      BankNotificationListener
  data/email/       GmailClient (read-only Gmail API)
  data/statement/   StatementReader (PDF -> text via PDFBox)
  data/repository/  Transaction / Category / Account / Settings repos, RulesSource
  domain/parser/    ParserEngine, BankPattern, SenderFilter, MerchantCleaner, SmsDate
  domain/categorize/Categorizer, MerchantRules
  domain/dedupe/    Deduplicator (duplicates + self-transfers)
  domain/statement/ StatementParser, NarrationCleaner
  domain/email/     EmailText (Gmail JSON / HTML -> text)
  ui/...            Compose screens, theme, charts
  work/             InboxScanWorker, EmailSyncWorker
app/src/main/assets/ bank_patterns.json, merchant_rules.json
app/src/test/        parser, email/notification, statement, categoriser, dedupe tests (52 tests)
```

## Notes and deviations

- **Charts:** these are drawn with Compose Canvas, not Vico. Vico has no donut chart, and Canvas keeps one consistent look with no third-party chart dependency. The Vico dependency can be added in Phase 2 if needed.
- **UPI-app SMS:** the GPay and PhonePe patterns are best guesses, because those apps rarely send SMS. Bank SMS cover those payments anyway.
- **Monthly budget:** this is a single overall budget for the Home budget bar. Per-category budgets and the 80% and 100% alerts are Phase 2.
- **APK size:** only the 64-bit ARM (`arm64-v8a`) native code is included, which halves the APK. Every recent phone, including the Moto G73, is 64-bit ARM.
- **Play Store release:** you will need the SMS Permissions Declaration ("SMS-based money management"), a privacy policy page and the Data safety form ("no data collected"). None of this is needed for a debug APK installed over USB.
