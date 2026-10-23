# Joint Launcher

> No, this is not a project to build a devil's lettuce projectile launcher.
> It's an app. An Android TV app. (Almost a launcher, but let's not get into that.)

One Android TV home screen for everything you watch and listen to: Jellyfin (including Live TV), Stremio,
Netflix / Prime Video / Disney+ / HBO Max / Filmin / Hulu / Peacock / Stan (catalogues via JustWatch),
YouTube (SmartTube), Spotify and GeeSports, with Jellyseerr requests for movies, series and music.

Built for Google TV / Android TV 11+ with plain Android Views (no Compose, no Leanback).

## Install

1. Download `joint-launcher.apk` from the [latest release](../../releases/latest).
2. Install it on the TV: `adb install -r joint-launcher.apk`, or copy it over and open it with a file manager (allow "unknown apps").
3. Optional: so the remote's Home button opens Joint Launcher instead of Google TV, go to
   Settings → Accessibility and turn on **Joint Launcher Home button**.

The release is a **debug** build on purpose: `tools/connect.ps1` uses `adb run-as` to hand over account tokens, and that only works on debuggable apps.

## Connecting accounts

| Service | How |
|---|---|
| Jellyfin | "Connect Jellyfin" tile on the TV → approve the code under Profile → Quick Connect (server address in the app's Settings) |
| Stremio | Settings on the TV → email + password (only the session key is kept) |
| YouTube | `tools/connect.ps1 -Service youtube` (setup below) |
| Spotify | `tools/connect.ps1 -Service spotify` (setup below) |
| Jellyseerr | `tools/connect.ps1 -Service jellyseerr` (address + API key from Settings → General, or your admin login) |
| Now Playing tile | `tools/connect.ps1 -GrantNowPlaying` (gives Joint Launcher notification access) |

Copy `tools/connect.config.example.json` to `tools/connect.config.json` (it's git-ignored) and fill it in:

**Spotify** (needs Premium on the account that owns the app): developer.spotify.com/dashboard → Create app →
Redirect URI `http://127.0.0.1:8888/callback`, API "Web API" → copy the Client ID into `spotifyClientId`.

**YouTube**: console.cloud.google.com → new project → enable "YouTube Data API v3" →
OAuth consent screen (External, add yourself as a user, then **Publish app**, otherwise the login expires after 7 days) →
Credentials → Create OAuth client ID → type **Desktop app** → copy Client ID / Client secret into `googleClientId` / `googleClientSecret`.

## Build

JDK 17 and the Android SDK (platform 35), Gradle 8.9. Optionally put your Jellyfin server in `local.properties`
(`jellyfin.server=http://your-server:8096`) so your builds point at it out of the box:

```bash
gradle testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Adding a streaming service

Add one line to `PROVIDERS` in `Model.kt` (JustWatch package codes + the TV app's package name) and the package to `<queries>` in the manifest.
Its rows appear automatically once the app is installed on the TV; until then its catalogue is still in the sidebar.
