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
