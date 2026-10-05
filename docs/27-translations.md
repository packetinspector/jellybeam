# 27 — Translations

Status: **Ready for translations; US English is the only shipped language.**
There is no in-app language setting: Jellybeam follows the TV's system
language whenever that language ships.

## 1. What a viewer sees

- **Text** is in the TV's language when that language ships, else US English.
- **Dates, times and numbers** always follow the TV's locale: a full date is
  "Oct 5, 2026" on a US TV and "5 Oct 2026" on a UK one, even while the text is
  English. Short dates keep one layout ("Oct 5") with the month name localized.
- **Server data** — library, item and person names, overviews, Seerr's own
  messages — is shown exactly as the server sends it, never translated.
- Not translated: the diagnostic report page and crash files, which are for
  bug reports, and technical error details passed through from the network.

## 2. The ship gate

`app/build.gradle.kts` holds one list:

```kotlin
val shippedLanguages = listOf("en")
```

It feeds `androidResources.localeFilters` (translations outside the list are
left out of the APK), the generated per-app language list Android shows in
system settings, and `BuildConfig.SHIPPED_LANGUAGES`, which
`i18n/AppLocale.kt` reads. A translation can be merged long before it ships;
adding its language code to the list is the one-line change that ships it.

## 3. Where text lives

- `app/src/main/res/values/strings*.xml` — every user-visible English string,
  split by area (`strings.xml`, `strings_app.xml`, `strings_browse.xml`,
  `strings_detail.xml`, `strings_player.xml`). Android merges them.
- Composables read text with `stringResource`/`pluralStringResource`.
- Formatters and ViewModels that build text outside Compose take
  `i18n/UiStrings`, so they stay JVM-testable; their tests use
  `ResourceUiStrings`, which reads the real `strings*.xml`, so every test
  assertion checks the shipped English.
- Dates and numbers format with `AppLocale.format`; all-caps text uses
  `uppercaseUi()`, which cases by the text's own language.
- Never build a sentence by joining strings in code: one resource, with
  numbered placeholders (`%1$s`, `%2$d`) so a translation can reorder them.
  Counts use `<plurals>`.

## 4. Contributing a translation

1. Create `app/src/main/res/values-xx/` (`xx` is the ISO 639-1 code, e.g.
   `es`; add a region only when it differs, e.g. `values-pt-rBR`).
2. Copy each `values/strings*.xml` you translate into it, keeping file and
   string names. Translate the text only.
3. Keep every placeholder exactly as in English (`%1$s`, `%2$d`, `%%`);
   reorder them freely. A plural's `one` may spell the number out. Add the
   plural quantities your language needs (`zero`, `few`, `many`, …).
4. Skip anything marked `translatable="false"` (brands and technical tokens).
5. Escape apostrophes as `\'` and keep `\n` line breaks.
6. Partial translations are fine: a missing string falls back to English and
   lint reports it as a warning.
7. Run `./build.sh check`. `I18nTest` fails if a placeholder is missing,
   extra or changed, or a translated name no longer exists in English.
8. To see it on a TV, add the code to `shippedLanguages` locally, build, and
   switch the TV's language. Leave that line out of the PR; the maintainer
   ships the language.

Text length: TV layouts reserve fixed widths for buttons and chips. Prefer the
shortest natural wording and check long strings on a screen.

## 5. Core and app boundary

The Rust core never produces display text. Where a core value reaches the
screen it crosses the FFI as an enum or `None` — `UnreachableReason`,
`UpdateFailure`, `CoreError::UpdateStorageUnavailable`, `CoreError::SeerrUnreachable`, a Seerr season with no
name — and Kotlin words it from resources. Free text that does cross is either
server data or a technical detail shown verbatim inside a translated sentence.
