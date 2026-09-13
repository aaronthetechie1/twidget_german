# Maintainer Release Process

This process keeps production signing and hosted-service credentials outside
the Git repository.

## Credential boundaries

- The release keystore and `keystore.properties` live in
  `~/.config/twidget/` with owner-only permissions.
- GitHub Package credentials live in
  `~/.config/twidget/github.properties` with owner-only permissions.
- GitHub Actions stores the base64-encoded release keystore, signing passwords,
  and package-registry credentials as encrypted repository secrets.
- Railway stores the server-only Top Followers provider credential as the
  sealed `TWITTERAPIS_API_KEY` service variable. It is never injected into an
  APK or GitHub Actions build.
- GitHub Actions stores the Discord release-channel webhook as the encrypted
  repository secret `DISCORD_RELEASE_WEBHOOK_URL`. Stable and beta workflows
  post through it only after their GitHub Release is published; the Debug Build
  workflow never uses it.
- Railway stores its database, Redis, X API, administration, and service tokens
  in Railway variables. Never copy their values into a repository file,
  workflow, issue, log, or release note.

`keystore.properties` uses the standard Android signing keys:

```properties
storeFile=/absolute/path/to/twidget-release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

For a one-off alternative location, pass
`-PtwidgetSigningProperties=/absolute/path/to/keystore.properties`.

Set or rotate the bridge provider key without putting it in shell history:

```bash
printf "%s" "$TWITTERAPIS_API_KEY" | railway variable set TWITTERAPIS_API_KEY --stdin --service twidget-bridge --environment production
```

For local bridge development, set `TWITTERAPIS_API_KEY` only in the bridge
process environment. Android builds deliberately contain no included provider
credential; users who do not opt into shared history need their own provider
credentials for Top Followers scans.

## Stable release checklist

### Distribution flavors

`github` retains the APK updater. `play` delegates updates to Google Play and
omits the sideload permission, file provider and reminder receiver. Both use
`com.tjg.twidget` and the same version codes. To preserve upgrades for existing
installs, configure Play App Signing with the existing app signing identity;
the upload key alone does not determine the certificate delivered to devices.

Build a signed Play release locally with:

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew testPlayReleaseUnitTest bundlePlayRelease lintPlayRelease
python3 scripts/verify-play-bundle.py app/build/outputs/bundle/playRelease/app-play-release.aab
```

The **Release** workflow also builds and verifies this flavor and uploads a
`twidget-play-<version>` workflow artifact. Upload that AAB to Play Console;
GitHub release assets remain the GitHub distribution. The **Play Build**
workflow verifies an unsigned Play release on pushes and pull requests.
Do not upload a GitHub-flavor AAB or a debuggable build to Google Play.

### Publishing

1. Ensure `main` is clean, current with `origin/main`, and green in GitHub
   Actions.
2. Set the intended stable semantic version in `version.properties`.
3. Add the matching dated section and comparison link to `CHANGELOG.md`.
4. Run the Android, bridge, and secret-boundary checks documented below.
5. Confirm the shared bridge health endpoint and one representative public
   profile lookup succeed without exposing operator credentials.
6. Dispatch the **Release** workflow with the plain version, for example
   `1.0.0`. The workflow runs bridge checks first, then verifies the version,
   builds and signs the APK and AAB, creates `twidget-v<version>`, and publishes
   the GitHub release.
7. Verify the workflow conclusion, Discord announcement, release notes, APK and
   AAB filenames, versions, signing certificates, and updater visibility from
   a logged-out client.

For a public release, the release repository itself must be public. GitHub
release assets in a private repository are not anonymously downloadable, and
the app updater deliberately does not embed a GitHub credential.

Do not create the stable tag manually unless recovering a failed workflow; the
workflow owns the tag and published asset.

Each semantic version reserves Play Store version-code slots in release order:
beta builds use 80–98, trusted debug builds use 98, and the stable build uses
99. This lets a production-signed debug APK install over any beta of the same
version while keeping the stable release as the final upgrade. Do not publish
debug AABs to Play; the debug slot is for local and workflow testing only.

## Discord release notifications

In the target Discord channel, create a webhook under **Edit Channel >
Integrations > Webhooks**. Copy its URL directly into the GitHub repository
secret without committing it:

```bash
gh secret set DISCORD_RELEASE_WEBHOOK_URL --repo thatjoshguy67/twidget
```

The command securely prompts for the webhook URL. To prefix each announcement
with a release-role mention, set the optional repository variable to the
Discord role ID in mention syntax:

```bash
gh variable set DISCORD_RELEASE_MENTION \
  --body '<@&ROLE_ID>' \
  --repo thatjoshguy67/twidget
```

Use `@everyone` instead only when a server-wide release ping is intentional and
the webhook has permission to mention everyone. Leave the variable unset for a
plain channel notification. Rotating or deleting the Discord webhook requires
updating the GitHub secret before the next release.

## Local verification

Trusted debug builds from `main` and manual runs of the **Debug Build** workflow
are signed with the production certificate. Pull-request builds retain the
checked-in debug certificate so pull-request code never receives production
signing credentials. To build an interchangeable debug APK locally, use:

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew assembleGithubDebug -PsignDebugWithRelease=true
```

This keeps the `-debug.N` version name but makes the APK signature compatible
with beta and stable builds. It is a debuggable production-signed artifact and
must not be published or shared.

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew testGithubDebugUnitTest assembleGithubDebug lintGithubDebug \
  bundleGithubDebug testGithubReleaseUnitTest assembleGithubRelease bundleGithubRelease lintVitalGithubRelease \
  testGithubBetaUnitTest assembleGithubBeta bundleGithubBeta lintVitalGithubBeta

cd bridge
npm ci
npm run check
npm test
npm audit --omit=dev
```

Release and Pre-release workflows run the bridge checks automatically before
building the Android APK and AAB. The local checklist above mirrors both jobs.

Before making the repository public, scan the complete Git history—not merely
the working tree—for credentials and sensitive signing files. If a real secret
ever entered Git, remove it from history and rotate it before changing
visibility; history rewriting alone does not make a credential safe again.

Also review author email addresses, commit messages, screenshots, and asset
provenance because all of those become public with the Git history. Enable
GitHub private vulnerability reporting before announcing the repository.
