# SESL9 migration and prototype history

As of 1.3.0, SESL9 is the default for GitHub and Play builds on staging, including
debug, beta and release variants. Native chrome and spacing live in `main`; the
SESL8 fallback has been removed. Normal builds keep `com.tjg.twidget`, updater
behavior and Buffer OAuth. CI uses JDK 25 and compile SDK 37.

The optional `sesl9Prototype` flag still creates an isolated debug installation
for device testing. The dated verification sections below describe the prototype
work before promotion; references there to SESL8 describe that historical state.

## Build and install alongside staging

Use JDK 25 or newer for the prototype (locally verified with JDK 26), Android SDK
37, and the existing GitHub Packages credentials described in CONTRIBUTING.md.
The published SESL9 libraries contain Java 24 bytecode. Twidget's own bytecode
target remains Java 17, minimum Android version remains API 26, and target SDK
remains 36. All builds compile against SDK 37.

```sh
JAVA_HOME=/path/to/jdk25 ./gradlew \
  :app:assembleGithubDebug -Psesl9Prototype=true
adb install -r app/build/outputs/apk/github/debug/app-github-debug.apk
```

On this Mac, the available newer JDK is:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :app:assembleGithubDebug -Psesl9Prototype=true
```

The APK installs as **Twidget SESL9**, package `com.tjg.twidget.sesl9`. Its settings,
credentials, databases, scheduled work, and widget instances belong to this
separate Android application. Set up accounts again and add new widgets from its
own widget-picker section. Uninstalling the prototype leaves staging installed.
Connecting the same account still uses the configured backend; this is app
isolation, not a separate staging server.

Both GitHub and Play debug distributions can be built, but they use the same
prototype package ID and replace each other. Neither replaces ordinary Twidget.
The prototype flag disables beta/release variants. Builds without the flag use the same SESL9 implementation with the original app
ID and normal distribution behavior.

## Deliberate prototype limitations

- In-app update checks and installation are disabled, so a normal Twidget release
  cannot be offered as an update for the prototype. Install later prototype APKs
  manually using the same debug certificate.
- Buffer OAuth is unconfigured and its callback activity disabled. The hosted
  callback relays to `twidget://oauth/buffer`, shared with staging. A separate
  registered callback and hosted relay are needed before enabling prototype
  Buffer sign-in. Local scheduling remains available.
- The design wrapper still targets One UI 8 upstream. SESL9 is explicitly pinned
  underneath it, and compatibility remains an experiment until checked on real
  Samsung devices, particularly foldables and wallpaper-palette integration.
- Home-screen widgets use RemoteViews/custom rendering; updating SESL does not
  automatically restyle them.

## Dependency and toolchain changes

- AGP 9.3.3, Gradle 9.5.0, and AGP's built-in Kotlin replace AGP 8.10.1 / Gradle
  8.11.1 / the separate Kotlin Android plugin.
- All 17 SESL AndroidX/Material modules are strictly pinned together in
  `app/build.gradle.kts`. This matters because both oneui-design and some SESL9
  package metadata still request SESL8, including versions that sort above SESL9.
- SESL9 Core/Fragment include Kotlin extensions; obsolete Core KTX and stock
  Fragment KTX are excluded to prevent duplicate classes.
- `android.uniquePackageNames=false` accommodates the upstream picker modules'
  shared `androidx.picker` namespace. `resValues` is explicitly enabled for AGP 9.
- Prototype resource overlays identify its Samsung palette target package and
  show SESL9 in About. Class names retain the original code namespace, including
  the launcher alias and widget configuration activity; provider authorities use
  the installed application ID.
- Chart range changes now set the activity result when saved, allowing the
  default back dispatcher to deliver it for gesture and toolbar Back.
- The milestone number picker retains `setCustomIntervalValue` with a scoped
  RestrictedApi lint suppression. The public `setCustomInterval` rejects the
  existing 1..999,999,999 bounds because they are not divisible by the wheel step.
  Keeping this setter preserves exact typed goals as well as wheel increments.

## Verification

```sh
JAVA_HOME=/path/to/jdk25 ./gradlew \
  :app:assembleGithubDebug :app:assemblePlayDebug \
  :app:testGithubDebugUnitTest :app:assembleGithubDebugAndroidTest \
  :app:lintGithubDebug :app:lintPlayDebug -Psesl9Prototype=true

# Use a disposable emulator: integration tests exercise real app settings.
JAVA_HOME=/path/to/jdk25 ./gradlew \
  :app:connectedGithubDebugAndroidTest -Psesl9Prototype=true

# Verify the normal SESL9 build with the original application ID.
JAVA_HOME=/path/to/jdk21 ./gradlew :app:assembleGithubDebug
```

The test suite includes installed-package isolation, a disabled staging OAuth
route, settings navigation, typography, widget settings, an exact typed milestone
goal, and chart results through the back dispatcher. A live bridge scan is opt-in
and is not part of the offline compatibility run. AOSP emulator tests cannot
validate Samsung-specific blur, launcher integration, or physical fold transitions.

Upstream references:

- https://github.com/tribalfs/sesl-androidx
- https://github.com/tribalfs/sesl-material-components-android
- https://github.com/tribalfs/oneui-design
- https://developer.android.com/build/releases/agp-9-3-0-release-notes

### Local result (18 September 2026)

GitHub and Play SESL9 debug APKs build successfully. All 306 JVM tests pass, and
both debug lint tasks pass (warnings remain). The offline emulator run covered
settings, typography, credentials, follower screens, and package isolation. Its
About-menu expectation was updated for disabled in-app updates and passed on
rerun. Focused chart-back and exact-goal-entry regressions also pass. The live
bridge test was not enabled. A normal SESL8 debug build and its 306 JVM tests pass
with the flag omitted and Java 21.

The disposable API 37 emulator had both `com.tjg.twidget` and
`com.tjg.twidget.sesl9` installed simultaneously. No staging package was replaced.
Real Samsung-device and older-Android validation remain outstanding.

### Floating chrome follow-up (1.2.4-sesl9.7)

The SESL9-only source adapter moves existing toolbar instances into the native
FloatingToolbarLayout sibling required by AppBarLayout's floating mode. It
preserves top-aligned About navigation and bottom-aligned standard toolbars,
including the existing menu presenters. The older wrapper's lazy search and
selection toolbar stubs remain in place until inflated.

The dashboard FAB is now a regular Material FAB in the fixed coordinator root,
with automatic hiding disabled and the existing navigation-bar margin retained.
It no longer depends on the SESL8 wrapper's scrolling-content offset calculation.
About explicitly starts expanded on fresh navigation.

The GitHub debug APK builds successfully. About fresh-entry and floating-action
checks pass twice, and all 14 settings navigation checks pass. Initial dashboard
FAB bounds pass after replacing the scroll-aware wrapper; the final gesture run
was interrupted by input injection targeting another emulator window/display.
Earlier dashboard scrolling captures confirmed floating action backgrounds.
Samsung validation and complete final dashboard gesture verification remain
pending. The interactive emulator has no wm size or density override.

### About collapse inset correction (1.2.4-sesl9.8)

The toolbar adapter includes app-bar top padding in the native collapsed height.
About applies the status-bar/cutout inset directly to its app bar, so that space
must remain reserved at the collapsed stop before native floating mode begins.
The targeted first-collapse regression reproduced 111 px of overlap in .7; the
corrected layout aligns the content and toolbar boundaries within 1 px of SESL's
fractional scroll rounding. The test now covers this intermediate stop explicitly.

All three final chrome checks pass on the interactive emulator: first collapsed
About stop, fresh expanded About entry followed by scrolling (two visits), and
dashboard toolbar/FAB bounds throughout scrolling. GitHub debug lint passes.
These checks also complete the previously interrupted dashboard verification.

### Native schedule navigation and reader toolbar (1.2.4-sesl9.9)

The schedule List/Calendar switcher now uses SESL9 BottomNavigationView in its
native floating, wrap-content style. A 6dp elevation, native rounded background,
light/charcoal surface tint, inset checked-state selector, and per-destination
label weight match the supplied Samsung references. The checked-state selector
is application styling because this SESL release supplies only a press ripple
for floating navigation items. Selection is synchronized with the existing
queue tabs; the legacy tabs remain hidden and retain their business callbacks.

The standard Material FAB stays separate above the nav and is positioned in the
fixed coordinator overlay with navigation-bar clearance. Selection and trash
actions use native floating bottom bars, while long-press action mode uses the
native floating toolbar and close icon. The overlay follows the embedded queue's
visibility and disappears when returning to the dashboard.

The changelog reader uses a full-width native Toolbar in FloatingToolbarLayout.
Its position is set by system insets, with the old button offsets removed. Native
measurement reserves the complete navigation touch target, and the toolbar's
transparent background reveals the native floating projection.

Focused emulator checks pass for scheduling navigation, checked state after
scrolling and tab changes, separate FAB bounds, long-press/pin behavior, dark-mode
resources, embedded scheduling visibility, and the reader's position, scrolling,
and back action. The existing chart-result test fixture now supplies nonempty
history labels, matching the storage contract; its rerun passes. About and
dashboard chrome checks also passed during this iteration. Final GitHub debug
lint passes, and the shared source changes compile with the SESL8 flag omitted.
The interactive emulator still has no display size or density override.

Artifact: `app/build/outputs/sesl9/Twidget-1.2.4-sesl9.9.apk`.


### Native fades, selection pills and drawer boundary (1.2.4-sesl9.10)

SESL9's built-in fading edges are enabled on NestedScrollView and RecyclerView,
including lists attached by preference fragments after initial inflation. Each
newly enabled helper receives a fresh window-inset dispatch so it uses SESL's
status-bar fade. FloatingToolbarLayout is connected to the currently visible
scrolling view, allowing its native app-bar integration to manage fade clamping.
No application-drawn gradient or shader is added.

Page coordinators extend behind the status bar; their app bars retain the safe
inset for floating controls. The drawer panel instead has a top margin equal to
the safe inset plus its original native margin, so its entire rounded surface
starts below the status bar.

Selection mode hides the older wrapper's normal toolbar, shows the numeric
selected count beside the select-all checkbox, and retains Cancel as a native
single-line menu action in its own floating projection. The count container has
no extra start padding. Cancel is measured again after the app font is applied.
Toolbar height wraps the native controls, preserving their padded touch targets.

Final targeted emulator checks pass for drawer surface bounds, native preference
fade inset delivery, dashboard toolbar/FAB bounds, and dark selection/navigation.
About entry/collapse, light selection/navigation, embedded queue visibility, and
reader navigation also passed during this iteration. The shared changes compile
with SESL8, and SESL9 GitHub debug lint passes. The interactive emulator retains its physical 1080x2092 resolution and 420dpi, with
no display overrides. Samsung device verification remains a user follow-up.

Artifact: `app/build/outputs/sesl9/Twidget-1.2.4-sesl9.10.apk`.


### Native widget actions and persistent toolbars (1.2.4-sesl9.11)

Widget settings now uses SESL9 DividerButtonLayout inside FloatingBottomLayout.
The library supplies button dimensions, typography, divider, floating projection,
elevation and light/dark styling. The original Cancel/Save IDs and callbacks are
retained. The container stays fixed above navigation insets, and the settings
list reserves the measured action-bar clearance. SESL8 keeps its existing bar.

Selection mode no longer overrides the toolbar's contentInsetStart or the count
container's start padding. The toolbar resolves its start inset from the native
theme. FloatingToolbarLayout scroll transitions are disabled across the shared
page adapter and changelog reader; the native app-bar/fading integration remains.

Five targeted emulator tests pass: native widget actions in light and dark mode,
Cancel/Save persistence, dashboard toolbar visibility during scrolling, and
selection/default toolbar inset with native schedule navigation. SESL9 GitHub
debug lint passes.

Artifact: `app/build/outputs/sesl9/Twidget-1.2.4-sesl9.11.apk`.


### Composer toolbar bounds (1.2.4-sesl9.12)

The composer's bottom controls are moved out of the scrolling-content container
into a fixed coordinator overlay using native FloatingBottomLayout. The old
custom surface, elevation and translation are removed; SESL supplies the floating
projection. Navigation/IME insets position the bar, and measured bottom clearance
is reserved in the editor scroll view.

Draft and Save use native toolbar menu items instead of the fixed-size combined
action view. Their enabled states and draft/schedule callbacks remain intact.
This lets the floating toolbar measure and project the actual native actions.
SESL8 retains its original action view and bottom container.

The composer regression passes for initial and scrolled toolbar bounds, input
panel open/close, action enablement, and local draft persistence. This emulator
uses a floating handwriting input panel with zero bottom IME inset; a docked
Samsung keyboard still needs device verification. Its original hardware-keyboard
preference was restored after the test. SESL9 GitHub debug lint passes.

Artifact: `app/build/outputs/sesl9/Twidget-1.2.4-sesl9.12.apk`.

### Visual regression audit — SESL9.13

The emulator audit covered dashboard scrolling and FAB bounds, drawer insets,
About entry/collapse, schedule List/Calendar and selection mode, the changelog
reader, composer actions and keyboard visibility, widget settings, settings
categories, Brief settings, and native dialogs. A separate 12-page sweep captured
entry and scrolled states in both light and dark mode (plus the language dialog).
The initial 29 instrumentation checks and the additional theme sweep passed.

Screenshot review found two issues that existing assertions did not catch:

- Calendar cells and post chips had no night resources, leaving white text on
  pale backgrounds. They now use the existing dark card and divider colours.
  The calendar check now verifies actual rendered label/background contrast.
- Fixed-height app bars did not reserve their status-bar inset. Widget settings
  consequently opened in the floating state with its title hidden and preview
  behind the back button. The SESL9 bridge now includes the inset in the native
  custom height. Tests check the title and initial preview/toolbar separation.

The updated prototype builds and lint reports zero errors (existing warnings
remain). Verification uses the unmodified 1080×2092, 420 dpi portrait emulator.
Samsung blur rendering, landscape/fold transitions, and a docked Samsung keyboard
still need device testing; the emulator uses a floating handwriting keyboard.

### Native settings spacing — SESL9.14

The SESL9 resource overlay aliases `settings_row_height` to
`sesl_list_preferred_item_height_small` and `settings_content_inset` to
`sesl_list_item_padding_horizontal`. This removes the prototype's effective
64dp/20dp settings override without copying replacement numeric values.
The installed AppCompat 1.8.0+1.0.38-sesl9+rev0 AAR defines a 52dp small row
(SESL8 used 56dp) and adaptive horizontal padding: 12dp fallback, 18dp at
widths of at least 251dp. The normal build keeps its existing settings dimensions.
Actual card content bounds and padding were checked in light and dark mode,
and the settings navigation/control tests passed. The native fade check now
adds temporary preference rows so its scroll coverage does not depend on how
many settings fit at the native row size and current display configuration.

Subpage coverage includes Accounts, Appearance, Data and sources, Scheduling,
Brief, Brief content, Debug, Buffer and all three source configuration pages,
as well as About and widget settings. Account headers now use the native regular
row minimum with natural height for their summaries. Widget delta controls and the shared opacity heading also resolve through
SESL dimensions in the prototype; normal-build defaults are preserved.
The visual sweep checks native card padding and preference theme dimensions
on entry and after scrolling, in both light and dark mode.

Verification: 16 subpage/spacing/navigation tests and three focused widget/fade
checks passed. Light/dark screenshots were reviewed; the resizable emulator
retained physical sizing (no size or density overrides).

### Tablet drawer status-bar inset — SESL9.15

Tablet NavDrawerLayout uses SlidingPaneLayout, which ignores the child drawer
panel's top margin. Apply the safe top inset plus the native toolbar top spacing
through seslSetDrawerMarginTop instead, so native layout and rounded corners
agree. The phone panel retains its existing margin path.
The regression test reproduced the collapsed tablet panel at y=0 below a 74px
status-bar inset before the fix, then passed collapsed/open/collapsed checks in
light and dark mode on the 2208×1840 tablet emulator. Screenshots were reviewed;
no display-size or density overrides were applied.

### Promotion to 1.3.0 staging

SESL9 is now the default in main sources for both distributions and every build
type. The optional isolated debug flag retains its separate app ID and disabled
OAuth/updater behavior. GitHub/Play debug builds, the GitHub debug bundle, both
lint tasks, and all 311 JVM tests pass.

Explanatory preference text uses the new `androidx.preference.SeslPreferenceCaption`,
including its native 12sp typography, adaptive side padding, 8dp top and 16dp
bottom padding, and light/dark subheader colours. It replaces the old wrapper's
DescriptionPreference; no caption style or dimension overrides are added. Current
values remain inside their rows. Phone screenshots in both themes and settings
interaction tests were checked before promotion.
