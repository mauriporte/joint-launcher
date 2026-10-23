# Joint Launcher

> No, this is not a project to build a devil's lettuce projectile launcher.
> It's an app. An Android TV app. (Almost a launcher, but let's not get into that.)

One Android TV home screen for everything you watch and listen to: Jellyfin (including Live TV), Stremio,
Netflix / Prime Video / Disney+ / HBO Max / Filmin / Hulu / Peacock / Stan (catalogues via JustWatch),
YouTube (SmartTube), Spotify and GeeSports, with Jellyseerr requests for movies, series and music.

Built for Google TV / Android TV 11+ with plain Android Views (no Compose, no Leanback).

> **This is the redesign branch.** It installs as a separate app, "Joint Launcher (redesign)" (`com.mau.hub.redesign`),
> next to the regular Joint Launcher, so you can try it without losing the one you use.

## Install

1. Download `joint-launcher.apk` from the [latest release](../../releases/latest).
2. Install it on the TV: `adb install -r joint-launcher.apk`, or copy it over and open it with a file manager (allow "unknown apps").
3. **Install VLC** from the Play Store if you watch Live TV. Channels in MPEG-2 (many DVB/IPTV channels) open in VLC,
   because some TVs' MPEG-2 decoders can't be used by apps. Everything else plays in Joint Launcher's own player.
4. Open Joint Launcher → sidebar → Settings → **Connect accounts**, and sign in from your phone (see below).

### Home button

Google TV ignores the "default home app" setting, so Joint Launcher catches the remote's Home button itself:
Settings → Accessibility → **Joint Launcher (redesign) Home button** → On.

Android switches that off whenever the app is updated. Run this once and Joint Launcher turns it back on by itself
after every update (it only ever touches its own Home button entry, and only if you had it on):

```bash
adb shell pm grant com.mau.hub.redesign android.permission.WRITE_SECURE_SETTINGS
```

### Now Playing tile

Shows what Spotify (or any app) is playing. It needs notification access, which Google TV hides in its menus:

```bash
adb shell cmd notification allow_listener com.mau.hub.redesign/com.mau.hub.NowPlayingListener
```

## Connecting accounts

Settings → **Connect accounts** shows a QR code. Scan it with your phone (on the same Wi-Fi) to open a page served by
the TV itself, and sign in there. The page only exists while that screen is open; passwords are used once and never stored.

| Service | How |
|---|---|
| Jellyfin | **Connect Jellyfin** on the TV → approve the code under Profile → Quick Connect. The server address can be set on the phone page. |
| Jellyseerr | Address + your Jellyfin login (a Jellyseerr admin), or its API key (Settings → General) |
| Stremio | Email + password (only the session is kept) |
| Spotify | Your Spotify app's Client ID → Sign in with Spotify (setup below) |
| YouTube | A Google "TV" client's ID + secret → the TV shows a code to enter at google.com/device (setup below) |

**Spotify** (needs Premium on the account that owns the app): developer.spotify.com/dashboard → Create app → API "Web API",
Redirect URI `https://mauriporte.github.io/joint-launcher/spotify.html`. That page only passes the sign-in back to your TV
([docs/spotify.html](https://github.com/mauriporte/joint-launcher/blob/main/docs/spotify.html) on `main`).

**YouTube**: console.cloud.google.com → new project → enable "YouTube Data API v3" → OAuth consent screen (External, add
yourself as a test user, then **Publish app**, otherwise the login expires after 7 days) → Credentials → Create OAuth client
ID → type **TVs and Limited Input devices**.

## Build

JDK 17, Android SDK (platform 35), Gradle 8.9. Optional settings in `local.properties` (git-ignored):

```properties
# your Jellyfin server, so your builds point at it out of the box
jellyfin.server=http://your-server:8096
# signing key for release builds (without it they stay unsigned)
release.storeFile=C\:/path/to/release.jks
release.storePassword=...
release.keyAlias=...
release.keyPassword=...
```

```bash
gradle testReleaseUnitTest assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Keep a backup of the signing key: Android only installs updates signed with the same key.

## Code map

`MainActivity` lays the screen out and handles Back/Home; each part lives in its own file: `TopBar`, `Sidebar`,
`Rows` (RecyclerView rows, caching, retries), `Cards`, `DetailScreen`, `SearchBar`, `NowPlaying`, `MoveMode`,
`Catalog` (which rows exist), `Apps`, `Images`, `Meta`, `Actions`, `ConnectScreen` + `PhonePage` + `SignIn` (accounts),
`PlayerActivity` (Media3), `Sources` (all the APIs), `Hub` (shared settings and threads).

## Adding a streaming service

Add one line to `PROVIDERS` in `Model.kt` (JustWatch package codes + the TV app's package name) and the package to `<queries>` in the manifest.
Its rows appear automatically once the app is installed on the TV; until then its catalogue is still in the sidebar.
