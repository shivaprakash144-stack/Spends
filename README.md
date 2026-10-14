# Spend Tracker (Android)

A native Android app that reads bank and UPI SMS on the phone, turns each one into a debit or credit transaction, puts it in a spend category and shows where the money goes. Everything stays on the device: there's no account, no server and no `INTERNET` permission.

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
  data/sms/         SmsReader (inbox), SmsReceiver (live), SmsProcessor (pipeline)
  data/repository/  Transaction / Category / Account / Settings repos, RulesSource
  domain/parser/    ParserEngine, BankPattern, SenderFilter, MerchantCleaner, SmsDate
  domain/categorize/Categorizer, MerchantRules
  domain/dedupe/    Deduplicator (duplicates + self-transfers)
  ui/...            Compose screens, theme, charts
  work/             InboxScanWorker
app/src/main/assets/ bank_patterns.json, merchant_rules.json
app/src/test/        parser, categoriser, dedupe tests (38 tests)
```

## Notes and deviations

- **Charts:** these are drawn with Compose Canvas, not Vico. Vico has no donut chart, and Canvas keeps one consistent look with no third-party chart dependency. The Vico dependency can be added in Phase 2 if needed.
- **UPI-app SMS:** the GPay and PhonePe patterns are best guesses, because those apps rarely send SMS. Bank SMS cover those payments anyway.
- **Monthly budget:** this is a single overall budget for the Home budget bar. Per-category budgets and the 80% and 100% alerts are Phase 2.
- **Play Store release:** you will need the SMS Permissions Declaration ("SMS-based money management"), a privacy policy page and the Data safety form ("no data collected"). None of this is needed for a debug APK installed over USB.
