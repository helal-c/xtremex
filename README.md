# XtremeX TV Android

Native Android TV / TV Box app for the XtremeX live-TV playlist.

Implemented foundation:
- Native Media3 / ExoPlayer HLS playback
- HTTP/BDIX + HTTPS streams
- Auto playlist refresh from https://xtremextv.vercel.app/channels.json
- Cached playlist fallback
- Channel 1 on first install
- Last watched channel on later launches
- Immediate autoplay
- D-pad / CH+ / CH- / media next/previous
- Numeric channel tuning
- OK/Menu channel guide
- Info OSD and automatic retry
- GitHub Release update notification
- In-app APK download and Android installer handoff
- GitHub Actions debug APK build

Production OTA releases must always use the same private signing key. Keep it in GitHub Actions secrets, never commit it to the repository.
