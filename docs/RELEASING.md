# Releasing Grom and shipping updates

Grom is distributed as APKs attached to GitHub releases. There is no store, no
account and no server: tagging the repository is the release process, and the
same release is what installed apps see when the operator taps **Check now**.

See D37 (update check) and D38 (release build) in `docs/000-decisions.md`.

## 1. Bump the version

`app/build.gradle.kts`:

```kotlin
versionCode = 5          // must increase for every installable build
versionName = "0.6.0"    // the flavour suffix is added by the flavour
```

The in-app comparison strips the flavour suffix (`0.6.0-server29` → `0.6.0`) and
compares numerically, so `0.10.0` is correctly newer than `0.9.0`.

## 2. Verify locally

```bash
tools/run-gates
```

That builds both debug APKs, runs both JVM suites, all six gate scripts
(including `tools/check-site`) and lint, in dependency order (nine steps in
total).

## 3. Release with the runtime included

```bash
git tag v0.6.0
git push origin v0.6.0
```

`.github/workflows/release.yml` then:

1. assembles the pinned arm64 bionic runtime (`scripts/fetch-node-android.sh --all`),
2. installs it into `app/src/main/jniLibs/arm64-v8a/` (D34),
3. builds `server29` and `peripheral26` release APKs,
4. asserts both APKs contain `assets/node/grom-agent.js` and pass
   `apksigner verify`, that **only** `server29` contains
   `lib/arm64-v8a/libnode.so`, and that `peripheral26` carries no native
   libraries at all (D87),
5. attaches **version-free** assets — `grom-server29.apk`,
   `grom-peripheral26.apk` and `SHA256SUMS` — so the permanent links
   `releases/latest/download/grom-server29.apk` keep working across releases
   (D49). The version is in the tag and the release title.

You can also run it manually (Actions → **release** → Run workflow) with an
existing tag.

### Local equivalent

```bash
scripts/fetch-node-android.sh --all
scripts/build-node-android.sh --install node-out/runtime
gradle :app:assembleServer29Release :app:assemblePeripheral26Release
```

The APKs appear under `app/build/outputs/apk/<flavour>/release/`. They are
signed with the committed test key (D4) — installable and self-updatable, but
not proof of publisher identity. Replace `keys/` with a production key before
distributing to anyone who is not testing.

## 4. Install on a phone

```bash
adb install -r grom-server29.apk
adb shell am start -n app.grom.server/.GromActivity
```

The app opens on the status card. With the bundled runtime the state becomes
`READY` once the agent announces its control port; if it cannot start, the card
says `Runtime missing` and names the missing file rather than pretending.

## 5. The download page

`.github/workflows/pages.yml` deploys `web/` to
**https://sveatoken.github.io/grom/** on every push that touches the page.
`tools/check-site` runs first, so a page with a root-absolute asset path, a
third-party script, an injection API or a broken stable download link is never
published. Pages must use **GitHub Actions** as its source
(`build_type=workflow`, HTTPS enforced); see `docs/DOWNLOAD.md` §4 for the
one-line API call that re-creates it.

The page and the release are independent: a tag publishes the APKs, a `web/`
push publishes the page. The page always resolves `latest`, so neither has to
run after the other.

## 6. What the update check actually does

- Only on an explicit tap on **Check now** (S1-R033).
- One HTTPS GET to `https://api.github.com/repos/sveatoken/grom/releases/latest`.
- No token is sent; the repository is public.
- No background work, no scheduler, no analytics.
- Newer release → the card shows the version and a **Download APK** button that
  opens the flavour-matching asset in the browser.
- Current → "You're up to date".
- Offline / rate-limited / malformed → "Update check failed" with the reason.

Grom never downloads or installs an APK itself.

## 7. After the release

- The release becomes `releases/latest`; installed builds will see it on their
  next explicit check.
- Keep `SHA256SUMS` attached so a download can be verified:

  ```bash
  sha256sum -c SHA256SUMS
  ```

- If the release workflow fails, nothing is published: the tag still exists and
  the workflow can be re-run from Actions without re-tagging.
