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
| `videoSources` | Reels from places other than YouTube: Dailymotion, PeerTube or any video RSS feed (see below) |
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


## Reels from other sources (not only YouTube)
Add `videoSources` to `remote/config.json`. Each entry becomes cards in the tab named by its `category`
and is mixed into "For You" with the YouTube channels. Entries that fail to load are skipped silently.
```json
"videoSources": [
  { "kind": "dailymotion", "name": "Dailymotion Sport", "channel": "sport", "category": "sports" },
  { "kind": "dailymotion", "name": "Some account", "id": "USERNAME", "category": "comedy" },
  { "kind": "peertube", "name": "TILvids", "host": "tilvids.com", "category": "learn" },
  { "kind": "peertube", "name": "One channel", "host": "example.org", "id": "channel_name", "category": "music" },
  { "kind": "rss", "name": "My feed", "url": "https://example.com/videos.xml", "category": "news" }
]
```
- `dailymotion`: use `id` (a username) or `channel` (a topic channel such as `sport`, `news`). Videos that block embedding are skipped.
- `peertube`: `host` is the instance. Add `id` to follow one channel, or leave it out for the instance's latest videos.
- `rss`: any `https` RSS / Atom / Media RSS feed. Items are used when they carry a `.mp4` / `.webm` / `.m3u8` file
  (enclosure or media:content), or link to YouTube, Vimeo or Dailymotion.
- Not supported: TikTok and Instagram (no public feed to read). Single videos from any site can still go in `customVideos`.

## Troubleshooting config.json
- The file must be strict JSON: no ``` markdown fences, no trailing commas, and it must end with a closing `}`.
  If it is broken the app silently keeps the last good copy. Check it at https://jsonlint.com before committing.
- In the app, drag down on the home page to re-download config.json and rebuild the page.
- Custom videos from other hosts (Filemoon, Vimeo...) open in the in-app player directly; if a host refuses to play
  inside a WebView, the player shows an "Open in browser" link.

## Custom video `type` and landscape playback
- Add `"type": "sport"` or `"type": "movie"` to any entry in `customVideos`. If the video has no thumbnail
  (none in config.json and none provided by the player page), the card shows a green SPORT or a red/purple MOVIE
  picture instead of a blank card. Without a `type` a neutral play-button picture is used.
- Every video whose `category` is `deeprows` opens full screen in landscape; the phone returns to its normal
  orientation when the video is closed.
