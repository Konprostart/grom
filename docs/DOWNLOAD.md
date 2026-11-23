# Download page and invite links

One address for humans:

**https://sveatoken.github.io/grom/**

It is a static page in `web/`, deployed by GitHub Pages from
`.github/workflows/pages.yml`. There is no build step, no framework, no cookie
and no analytics: what is committed is what is served.

See D49 (static page, stable asset names), D50 (local referral identity) and
D51 (rewards are "coming soon") in `docs/000-decisions.md`.

## 1. What the page does

- **Download Grom for Android** is an ordinary `<a>` to
  `https://github.com/sveatoken/grom/releases/latest/download/grom-server29.apk`.
  It works with JavaScript disabled, offline from the API, or when GitHub rate
  limits the anonymous request.
- When the public releases API answers, the script shows the current tag and
  points the button at the exact asset URL. A failure leaves the static link in
  place and the status line says `Latest release` — never a spinner and never a
  false version.
- The three install steps name the Android prompt a sideload triggers
  ("Allow from this source") and say plainly that the app is not from the Play
  Store.
- It links the peripheral-only build (`grom-peripheral26.apk`, Android 8+).
- Light/dark/auto is a local preference (`localStorage`), matching the app's
  monochrome palette: white-on-black or black-on-white, hairline borders, one
  status colour.

## 2. Why release assets have version-free names

`releases/latest/download/<asset>` only resolves when the asset name is stable.
The release workflow therefore publishes:

| asset | role |
|---|---|
| `grom-server29.apk` | full server role, Android 10+ |
| `grom-peripheral26.apk` | peripheral role, Android 8+, never binds a socket and ships no Node runtime (D87) |
| `SHA256SUMS` | checksums (recommended with a direct download link) |

The version lives in the tag (`vX.Y.Z`), the release title, `BuildConfig` and
the app's update card. The in-app update checker selects assets by the
`server29` / `peripheral26` substring, so it is unaffected by the rename.

Users who want a pinned build can still open the release for a tag:
`https://github.com/sveatoken/grom/releases/tag/vX.Y.Z`.

## 3. Invite links (`?ref=`)

Every visitor gets a local code, and the page can carry someone else's:

- `https://sveatoken.github.io/grom/?ref=K7M2Q9XA` shows **"You were invited"**
  with the code in large type and a **Copy code** button.
- The code is normalized exactly as the app does it: upper-cased, `I`/`L` → `1`,
  `O` → `0`, `U` → `V`, 4–12 characters from
  `0-9A-HJKMNP-TV-Z`. An invalid `ref` is ignored silently.
- It is stored in `localStorage` (`grom.referrer`) so a reload, a trip to the
  download and a return to the page do not lose it.
- **Share / Invite friends** generates an 8-character code
  (`crypto.getRandomValues`, stored as `grom.myCode`), shows the matching
  `?ref=` link, and offers **Copy code**, **Copy link** and **Share invite**
  (`navigator.share`, with clipboard as the fallback).

The page cannot open the app or know whether the app was installed. It tells
the truth instead: *"Open Grom → Invite & Earn → Enter a friend's code, then
paste it."* Pasting is a deliberate action in the app; there is no deep link and
no attribution call.

## 4. Deploying and changing the page

Cloudflare-free, account-free, and free of charge because the repository is
public:

1. Commit changes under `web/` to `main`.
2. `.github/workflows/pages.yml` runs `tools/check-site`, uploads `web/` and
   deploys it. The workflow requests `pages: write` and `id-token: write`, and
   `configure-pages` can create the Pages site on its first run
   (`enablement: true`).
3. Pages must be set to **GitHub Actions** as its source. On this repository it
   already is (`build_type=workflow`, HTTPS enforced). To re-create it by hand:

   ```bash
   curl -X POST -H "Authorization: Bearer $TOKEN" \
     -H "Accept: application/vnd.github+json" \
     https://api.github.com/repos/sveatoken/grom/pages \
     -d '{"build_type":"workflow"}'
   ```

The site is a **project** page, so it lives under `/grom/`. Every asset
reference is relative (`./styles.css`); a root-absolute path would 404.
`tools/check-site` fails the build if one appears.

## 5. What the page must never become

- No third-party script, font, CDN or image host. The only allowed external
  hosts are `github.com`, `api.github.com`, `sveatoken.github.io` and the SVG
  namespace `www.w3.org`.
- No tracking, no fingerprinting, no cookie banner, no email capture.
- No invented numbers: no user count, no "earned" figure, no leaderboard.
- No `innerHTML` with a `?ref=` value. The gate greps for the injection APIs.

`tools/check-site` enforces these points locally and in CI.

## 6. Verify by hand

```bash
tools/check-site
python3 -m http.server 8177 --directory web
# open http://127.0.0.1:8177/ and http://127.0.0.1:8177/?ref=K7M2Q9XA
```

On the live site, check that the primary button resolves to the current release:

```bash
curl -sI https://sveatoken.github.io/grom/ | head -1
curl -sIL https://github.com/sveatoken/grom/releases/latest/download/grom-server29.apk | grep -i '^content-length'
```
