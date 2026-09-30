# Controlling the app from GitHub

1. In `RemoteConfig.kt` set `CONFIG_URL` to your repo's raw URL of `remote/config.json`
   (repo must be public, or the file must be hosted somewhere public, e.g. GitHub Pages).
2. Edit `remote/config.json` on GitHub -> users get it next time they open the app.
   Delete a key (e.g. `homeCategories`, `newsFeeds`) to fall back to the built-in defaults.

| Key | Effect |
|---|---|
| `vpn.message` | Text shown when users tap the VPN card |
| `announcement` | Popup shown once per `id` (change the id to show a new one) |
| `update` | If `latestVersionCode` > installed: "Update available" popup; if `minVersionCode` > installed: forced update |
| `newsFeeds`, `sportFeeds` | RSS sources for News / Sport |
| `reels` | `enabled` (true/false) shows or hides Deeprows Reels; `title` renames it |
| `videoChannels` | YouTube channels for Reels: `name`, channel `id` (starts with `UC`) and `category` (sports, comedy, lifestyle, gist, news, music, learn or your own). Each category becomes a tab |
| `customVideos` | Your own videos, shown first in Deeprows Reels (see below) |
| `homeCategories` | Replaces the whole home grid (see format below) |

`homeCategories` format:
```json
"homeCategories": [
  { "title": "MESSAGING", "subCategories": [
    { "title": "💬", "sites": [ { "name": "WhatsApp", "url": "https://web.whatsapp.com/" } ] }
  ] }
]
```

## Shipping a real update (new code)
Remote config can't change app code. For that: bump `versionCode`/`versionName` in `app/build.gradle.kts`,
push a tag like `v0.2.0` (the Release workflow builds and attaches a signed APK), then raise
`update.latestVersionCode` in config.json and point `apkUrl` at the release.

## Signing (required for updates to install over the old app)
Android only installs an update if it is signed with the SAME key as the installed app.
Create one keystore once, keep it safe forever, and add these GitHub repo secrets:
`KEYSTORE_BASE64` (`base64 -w0 release.jks`), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.


## Adding your own videos to Deeprows Reels
Put entries in `customVideos` in `remote/config.json`. Users see them next time they open the app.
```json
"customVideos": [
  { "title": "Welcome to Deeprows", "url": "https://www.youtube.com/watch?v=VIDEO_ID", "category": "deeprows" },
  { "title": "Promo", "url": "https://player.vimeo.com/video/123456", "thumbnail": "https://example.com/promo.jpg" },
  { "title": "My clip", "url": "https://example.com/clip.mp4", "thumbnail": "https://example.com/clip.jpg" }
]
```
- `title` and `url` (must start with `https://`) are required.
- `category` is optional (default `deeprows`, shown as the "Deeprows" tab). Use `sports`, `comedy`... to put it in those tabs.
- `channel` is optional (default "Deeprows"). `thumbnail` is optional for YouTube links (found automatically) but needed for others.
- Works with: YouTube links (watch, youtu.be, shorts, embed), sites that give an embed/player URL (Vimeo, Dailymotion...), and direct `.mp4` / `.webm` / `.m3u8` files.
- Best way to host your own videos: upload them to YouTube as "Unlisted" and paste the link.
