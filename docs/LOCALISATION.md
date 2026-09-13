# Localisation guide

Thanks for wanting to bring Twidget to your language. This guide covers where
translations live, how to add a new language, what code needs a small change
so the language shows up in the pickers, and how to check your work before
opening a pull request.

Twidget currently ships in **English** (the default) and **German**
(`values-de`, contributed by Aaron the Techie). There is no hosted
translation platform; translations are plain Android XML files edited in a
fork and submitted through GitHub.

## Before you start

- Search open issues and pull requests for your language so effort is not
  duplicated. If nobody has claimed it, open an issue saying which language
  (and regional variant, if any) you intend to translate.
- You should be a fluent speaker of the target language. Machine translation
  is fine as a starting point, but every string must be reviewed by a person
  who will notice wrong terminology, gender, formality, and truncation.
- One language per pull request.

## Where the strings live

All user-facing text is in Android resource files under
`app/src/main/res/`. The English source of truth is the `values/` folder.
Only these files contain translatable text:

| Default file | What it covers |
| --- | --- |
| `values/strings.xml` | Almost everything: dashboard, settings, widgets, Brief, scheduling, About |
| `values/milestone_strings.xml` | Follower milestone cards and notifications |
| `values/streak_splash_strings.xml` | Streak splash screen |
| `values/top_followers_browser_strings.xml` | Top Followers browser |

Do **not** copy or translate `attrs.xml`, `colors.xml`, `dimens.xml`,
`ids.xml`, `notice_colors.xml`, `overlayable.xml`, `themes.xml`, or
`widget_sizes.xml`. They hold no text.

Text generated at runtime by the Brief AI features comes from the language
model and is not part of the resource files, so it is out of scope here.

## Step 1: create the language folder

Create `app/src/main/res/values-<qualifier>/` next to `values/`, where the
qualifier follows Android's resource naming:

| Language | Folder |
| --- | --- |
| French | `values-fr` |
| Brazilian Portuguese | `values-pt-rBR` |
| Simplified Chinese | `values-zh-rCN` |
| Serbian (Latin script) | `values-b+sr+Latn` |

Prefer a plain language code (`values-fr`) unless regional differences are
significant enough that speakers of the other variant would object. A plain
code is used for every region of that language, so `values-pt` also serves
Brazil unless `values-pt-rBR` exists.

Inside the folder, mirror the default file names and copy each file's
contents as your starting point:

```bash
TARGET=fr   # your qualifier
mkdir -p app/src/main/res/values-$TARGET
for f in strings milestone_strings streak_splash_strings top_followers_browser_strings; do
  cp app/src/main/res/values/$f.xml app/src/main/res/values-$TARGET/$f.xml
done
```

Then translate the text between the tags. (The German folder also has a
`credits_strings.xml`; that is a historical split and you do not need to
replicate it.)

## Step 2: translate the strings

### Rules that keep the build green

- **Never change `name="..."` attributes.** The name is how code finds the
  string. Only the text between `<string>` and `</string>` changes.
- **Keep every placeholder** exactly as written: `%1$s`, `%2$d`, `%d`, `%s`.
  You may reorder numbered placeholders (`%2$s … %1$s`) to suit your grammar,
  but the set of placeholders must match the English string. Lint fails the
  build on mismatches.
- **Keep `\n`** line breaks where the English string has them; they matter
  for widgets and dialogs.
- **Escape apostrophes and quotes** as `\'` and `\"`, and write `&` as
  `&amp;` and `<` as `&lt;`.
- **Plurals** (`<plurals>`) use CLDR quantity keys: `zero`, `one`, `two`,
  `few`, `many`, `other`. Add the keys your language needs and drop the ones it
  does not; `other` is always required. English only uses `one` and `other`,
  so languages such as Polish, Russian, or Arabic will need more items than
  the source file shows.
- **Skip strings marked `translatable="false"`** (for example
  `about_aaron_title` and `link_aaron`). Delete them from your copy; lint
  reports them as errors if they appear in a translated file.
- **Copy URLs and identifiers verbatim.** Strings such as `link_tjg`,
  `link_app_repo`, `link_privacy_policy`, `analytics_url`, and
  `schedule_public_url_hint` must stay identical to the English values.

### Style

- The English source uses British spelling and a friendly, direct tone.
  Match the register your language uses for consumer apps (for example,
  informal *du* in German rather than *Sie*).
- Keep proper nouns and product names untranslated: **Twidget**, **X**,
  **Twitter**, **Buffer**, **FxTwitter**, **FxEmbed**, **Rettiwt**,
  **TwitterAPIs**, **Gemini Nano**, **One UI**, **Cloudinary**.
- Translate Twidget's own feature names consistently and keep a list while
  you work: *Your Brief*, *Top Followers*, *milestone*, *streak*, *bridge*,
  *self-hosted bridge*, *shared history*, *data source*, *Awaiting
  confirmation*. Once you have chosen a term, use it everywhere.
- **Widgets have very little room.** Strings whose names start with
  `widget_`, `lockscreen_`, or `brief_widget_`, plus short labels such as
  `followers`, should be as short as the English text or shorter. Home-screen
  and lock-screen widgets truncate rather than wrap in many layouts.
- Apart from the two picker labels described in Step 3, do not edit
  `values/strings.xml` in a translation pull request. If you find a typo or
  unclear English string, open a separate issue or pull request so the fix is
  reviewed on its own.

### Finding missing or stale strings

To compare your file set against the English source:

```bash
TARGET=fr
diff <(grep -oh 'name="[^"]*"' app/src/main/res/values/strings.xml app/src/main/res/values/*_strings.xml | sort) \
     <(grep -oh 'name="[^"]*"' app/src/main/res/values-$TARGET/*.xml | sort)
```

Lines starting with `<` are missing from your translation; lines starting
with `>` exist only in your translation. The only expected `<` lines are the
`translatable="false"` strings mentioned above.

## Step 3: register the language in the app

Android will already use your folder when the device language matches, but
Twidget also offers explicit language pickers, so a few small code changes
are needed. Use the German localisation as a worked example
([#18](https://github.com/thatjoshguy67/twidget/pull/18) and
[#19](https://github.com/thatjoshguy67/twidget/pull/19)). If you are not
comfortable editing Kotlin, submit the strings and say so in the pull request;
a maintainer can do this wiring for you.

1. **`app/src/main/res/xml/locales_config.xml`** — add a line such as
   `<locale android:name="fr" />`. This makes the language appear in Android
   13+ per-app language settings and tells Play which languages the app
   supports.

2. **`app/src/main/java/com/tjg/twidget/core/AppLocales.kt`** — add your tag
   to `resolve()` so widgets that store an explicit language can build the
   right `Locale`:

   ```kotlin
   "fr" -> Locale.FRENCH   // or Locale.forLanguageTag("pt-BR")
   ```

3. **Settings → Language picker** —
   `app/src/main/java/com/tjg/twidget/settings/SettingsPreferenceFragment.kt`
   builds the list from `tags` and `entries`. Add your tag to `tags` and a
   matching entry backed by a new `language_<name>` string.

4. **Widget language picker** —
   `app/src/main/java/com/tjg/twidget/widget/WidgetConfigActivity.kt` has the
   same pattern in `pickLanguage()` and `languageLabel()`. Add your tag and a
   `widget_language_<tag>` string.

5. **Picker labels in `values/strings.xml`** — add the two new strings above.
   Follow the existing format: the English label followed by the native name
   in brackets, e.g. `French (Français)`. These are the only additions to the
   English file a translation pull request should make. Also add them to
   `values-de/strings.xml` (and to your own folder) so lint's missing
   translation check passes.

### Optional language-specific behaviour

Several formatting paths currently branch on `locale.language == "de"` and
otherwise fall back to English conventions with locale-aware month and day
names. Your language will work without touching these, but you can improve it:

- **Spelled-out follower counts** in the artwork widget come from
  `TwidgetWidget.followersInWords()`. Without a dedicated implementation your
  language gets English number words. Adding one means writing a
  `<language>NumberWords()` function next to `germanNumberWords()` and a test
  case in `app/src/test/java/com/tjg/twidget/widget/WidgetFollowerWordsTest.kt`.
- **Date and time patterns** live in `AppLocales.formatDate()`,
  `TwidgetStore`, `ScheduleComposeActivity`, and `ScheduleQueueHostActivity`.
  If the English `MMM d` / `h:mm a` style is wrong for your language, add a
  branch alongside the German one.
- **Widget text fitting** in `WidgetArtworkRenderer` uses a smaller minimum
  text size and tighter line height for German because of long compound
  words. Languages with similar characteristics may want the same treatment.

Keep these changes in the same pull request only if they are small; otherwise
land the strings first and follow up.

## Step 4: test it

Follow the development setup in [CONTRIBUTING.md](../CONTRIBUTING.md) (JDK 17+,
Android SDK, and a `github.properties` with a `read:packages` token for the
One UI dependencies), then run:

```bash
./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
```

Lint catches missing translations, placeholder mismatches, malformed XML, and
translations of `translatable="false"` strings. Note that pull requests from
forks only run bridge checks in CI, so this local run is the only Android check
your change gets before review.

Install the debug APK and check on a device or emulator:

- Settings → Language switches the whole app, including notifications and
  the About screen.
- Android 13+: System Settings → Apps → Twidget → Language lists your
  language.
- Each widget's configuration screen offers your language, and both the
  follower artwork widget and the Brief widget re-render in it, including the
  lock-screen 1x1 and 2x1 widgets on One UI.
- Look for clipped or overlapping text in widgets, buttons, dialogs, and the
  milestone and streak screens. Tablet and foldable layouts
  (`values-sw600dp`, `values-sw720dp`) use the same strings.
- Right-to-left languages: `android:supportsRtl` is enabled but no RTL
  language has shipped yet, so expect some layout issues and list them in the
  pull request.

## Step 5: open the pull request

- Title it `Add <Language> translation` and fill in the repository pull
  request template.
- Include screenshots of a few translated screens (dashboard, settings, a
  widget) with personal data removed.
- List any strings you were unsure about, and any that you had to shorten or
  rephrase heavily so a reviewer who speaks the language can focus there.
- Add a line to the unreleased section of [`CHANGELOG.md`](../CHANGELOG.md)
  under a language heading, following the German entry as a model.
- If you would like to be credited in the About screen's **Thanks to**
  section, say so and include the name and link you want shown; maintainers
  add credits alongside the existing entries.

## Keeping a translation up to date

Strings change between releases. When new English strings land, lint fails on
`MissingTranslation` for every shipped language, so maintainers may add
interim translations to your folder to keep builds green; these deserve a
native speaker's review. Run the diff command from Step 2 against `main`
periodically, or watch the repository for changes to
`app/src/main/res/values/`, and send a follow-up pull request correcting
interim wording and filling gaps. Small, frequent updates are easier to review
than one large sync.

## Questions

Open a blank issue describing the language and what you are unsure about, or
ask in the pull request itself. Partial translations are welcome as draft pull
requests if you want early feedback on terminology.
