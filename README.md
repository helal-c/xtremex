# XtremeX TV Android

Native Android TV / TV Box client for the XtremeX live-TV service.

## TV experience

- App opens directly into live TV
- First install starts channel 1
- Later launches resume the last watched channel
- Native Media3 / ExoPlayer playback
- HTTP/BDIX + HTTPS HLS streams
- Automatic primary-to-backup stream failover
- Automatic retry after signal/network loss
- Playlist refresh on launch and every 30 minutes
- Cached playlist fallback if the server is temporarily unavailable
- Remote-first full-screen receiver UI
- Direct numeric channel tuning
- Favorites and recent channels
- Category filtering without leaving playback
- Fast channel zapping with D-pad / CH buttons
- Last-channel remote key support
- Color-key shortcuts
- In-app update notification, APK download, SHA-256 verification and Android installer handoff

## Remote map

| Remote key | Action |
| --- | --- |
| Up / CH+ / Media Next | Next channel |
| Down / CH- / Media Previous | Previous channel |
| OK / Enter / Menu / Guide | Open channel guide |
| 0-9 | Direct channel number |
| Last Channel | Return to previous channel |
| Info | Show receiver OSD |
| Red | Favorites |
| Green | Recent |
| Yellow | Refresh playlist |
| Blue | Check app update |
| Left / Right inside guide | Change category/filter |
| Long OK on guide item | Add/remove Favorite |
| Back | Close guide or confirm app exit |

## Playlist

The app loads:

https://xtremextv.vercel.app/channels.json

Backup entries such as `[Backup 1]` are grouped behind the main channel and used automatically if the primary stream fails.

## OTA updates

The app checks:

https://raw.githubusercontent.com/helal-c/xtremex/main/update.json

A production release workflow builds a signed APK, creates a GitHub Release, calculates SHA-256 and updates `update.json`.

Before the first production release, configure these GitHub Actions secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_PASSWORD`

The signing key must remain the same for every future app update. Never commit the private keystore to the repository.

A normal Android app can download its own update, but Android still requires the user to confirm the final install screen.
