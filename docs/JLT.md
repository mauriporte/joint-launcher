# Joint Launcher Tablet (JLT)

How the tablet version of Joint Launcher was built, what it does, and how to work on it. Written by the A.R.T. Tablet
session (Android Redesign Team), 25/10/2026.

## What it is

Joint Launcher started as an Android TV launcher: one home screen that brings together what's on Jellyfin, Stremio,
YouTube (SmartTube), Spotify, the streaming services and GeeSports, with a sidebar of apps. The **tablet version** is
the same app adapted to a touchscreen and turned into a full launcher for the Redmi Pad SE: apps grid, widgets,
backgrounds, a flip clock, Home Assistant and a settings panel for all of it.

| | |
|---|---|
| App name | Joint Launcher Tablet |
| Package | `com.mau.hub.tablet` |
| Device | Redmi Pad SE ("MauRedmiPad"), HyperOS, Android 15, used in landscape |
| Code | this repository, branch `redesign`, product flavor **`tablet`** |
| Pull request | mauriporte/joint-launcher#1 (`redesign` → `main`) |
| Device notes | `~/.claude/agents/art/tablet.md` and `network.md` (ADB access and house rules) |

### One codebase, separate apps

The TV, tablet and phone apps are product flavors of one app (`app/build.gradle.kts`, dimension `device`):

- `tv`: `com.mau.hub.redesign` ("Joint Launcher"), what the TV runs. Now owned by the A.R.T. TV session; the TV
  launcher has moved to its own repository, mauriporte/joint-launcher-tv (`C:\Users\mauri\joint-launcher-tv`).
- `tablet`: `com.mau.hub.tablet` ("Joint Launcher Tablet").
- `phone`: owned by the A.R.T. Phone session.

They are separate installs, so the devices never share settings or sign-ins by accident. At runtime the code tells
the two worlds apart with `Hub.touch` (true when the device has no Android TV "leanback" feature): everything
touch-specific is behind it.

The tablet started as `com.mau.hub.redesign` (same package as the TV). When the apps were split into flavors, the
tablet moved to `com.mau.hub.tablet`: its settings came over with Backup/Restore, its background pictures through the
app's inbox folder, and its sign-ins through the Connect page's export/import. The old app was then uninstalled.

## Building and installing

Tools live in `C:\Users\mauri\android-tools` (JDK 17, Gradle 8.9). Machine-specific values are in the git-ignored
`local.properties`: the Jellyfin server, the release signing key and its passwords, and the two Home Assistant
addresses (`ha.local`, `ha.remote`). None of them are in the repository.

```bash
export JAVA_HOME="C:/Users/mauri/android-tools/jdk/jdk-17.0.20.1+1"
/c/Users/mauri/android-tools/gradle-8.9/bin/gradle -p /c/Users/mauri/joint-launcher-redesign testTabletReleaseUnitTest
/c/Users/mauri/android-tools/gradle-8.9/bin/gradle -p /c/Users/mauri/joint-launcher-redesign assembleTabletRelease
```

The APK is `app/build/outputs/apk/tablet/release/app-tablet-release.apk`, signed with the release key and shrunk by R8.

Lessons learned:
- Run the unit tests and the release build as **separate** Gradle runs. R8 sometimes fails with a file lock when they
  share a run; running it again fixes it.
- An adb install can stop at "Performing Streamed Install" without finishing. Check
  `dumpsys package com.mau.hub.tablet | grep lastUpdateTime` and install again if it's old.
- Ask Mau before installing (house rule, see `network.md`). After installing, press Home to restart the launcher
  rather than `am start`/`am force-stop`: starting it as a normal app gives it HyperOS's split-screen handle.

## What the tablet has

### Home screen

Home is made of **sections**, in an order you choose (Settings → Home):

- **Widgets**: a launcher-style grid (see below).
- **Apps**: every installed app, A to Z.
- **To Watch**: all the video rows (Continue watching, Live TV, the streaming services, GeeSports...) folded under
  one button. Which rows show and their order: Settings → Home → To Watch.
- **Home Assistant**: the HA dashboard in the page, last by default.

Each section has a header with its title (or its icon, see below), its buttons and a fold arrow (▲/▼). Each section
can be renamed and given its own icon. A "Tt" button in the top bar (and a switch in Settings) swaps the titles for
the icons: Widgets, a ⌘ app-drawer icon for Apps, a house for Home Assistant, a clapperboard for To Watch. The icons
come from the selected icon pack, with built-in ones as a fallback.

### Apps

- A to Z grid (accents sort correctly: "Cámara" with the C's), spread so the outer icons line up with the section
  icons and arrows.
- **Heart** button: All apps ↔ Favorites. With the heart on, the section is called Favorites and the grid shows only
  your favorites, in an order you set by **drag and drop** (hold an app and move it; hold without moving for its menu).
- **Press and hold an app** for: Open, Mark as favorite / Remove from favorites, Hide app, Uninstall, Change icon,
  Rename, App info.
- **Rename** changes only the name shown in Joint Launcher.
- **Hidden apps** are listed in Settings → Hidden apps, with tick boxes to bring them back.

### Icons

- An **icon pack** gives every icon (LineX by default; any installed pack, or "the apps' own icons"). The two are
  never mixed.
- When the pack has no icon for an app, one is picked: the pack's icon for the app's package or its phone/TV twin,
  then one whose name matches the app's, then the pack's letter icon. Guessed icons take the app's own colour (so
  GeeSports' G stays yellow). Services that aren't installed take their brand colour (Filmin's F is green).
- **Change icon** picks any icon from any installed pack, searchable, with an option to recolour it in the app's
  colour. Hand-picked icons were reviewed one by one (GeeSports = G, WayCar = car, etc.).
- **The remote's icon** (Mau's standard, used everywhere): LineX's `ir_remote` D-pad keypad in the accent orange
  `#E8650A`. Joint Remote (`com.mau.remote`) gets it built in (`IconPacks.OWN`); Change icon still overrides it.
- The sidebar uses the same icons: pack icons and Joint Launcher's own icon have no grey tile behind them.
- The Connect page's export also carries each app's icon by app name, so another device (the TV) can show the
  tablet's icons for apps whose package names differ there.

### Widgets

- A grid like a launcher's home page: cells of about 120 dp, as wide as the screen, up to three screens tall.
- **+** adds a widget from a searchable list of every widget your apps offer; it asks how many columns and rows it
  should take. Widgets with a setup screen are remembered even if Android closes Joint Launcher during setup.
- **Pencil** (a green tick while editing): drag a widget to move it, drag its orange corner to resize it, and drag
  the grid's grey corner to make the whole grid wider/narrower or taller/shorter. Everything snaps to cells, widgets
  never overlap (a drop on another widget goes to the nearest free spot), and positions are kept separately for
  landscape and portrait. The red ✕ removes a widget.
- Widgets are tied to the app they were added to: after the move to `com.mau.hub.tablet` they have to be added again.

### Top bar

Left to right:
- **Joint Launcher's icon**: opens the sidebar (a swipe from the left edge does too).
- **Padlock**: locks the desktop. Section headers, arrows, +, pencil and heart disappear; folded sections disappear
  completely and everything moves up; nothing can be moved or edited. The top bar keeps its folded state. Tap again
  to unlock; nothing else changes.
- **Search**: searches content, and the results start with the **apps** whose names match (accents and case ignored).
- **Fold arrow**, three steps: everything shown → only the weather and clock → nothing → back.
- Tabs (Home, Movies, Series, Sports, Apps...), **Tt** (section titles on/off), the **gear** (the tablet's own
  Android settings).
- **Weather**: temperature (half the clock's size) and an icon; tap to open a weather app (chosen once, hold to
  change). The location is automatic or a town typed in Settings.
- **Flip clock**: in the look of the Xperia's flip clock: two dark rounded cards, light grey condensed digits, a thin
  black fold line, transparent around. When the time changes, the top leaf folds down onto the new number. Always
  24-hour, with the timezone from Settings. Tap to open a clock app. Settings → Clock → Corner clock can replace it
  with any widget (for example the Flip Clock app's).

### Sidebar

The services with content here, the streaming catalogues, an Apps shortcut, Rows and **Settings** (a pen-and-paper
icon, so it isn't confused with the gear). It's wide enough to cover the section icons, solid by default, with an
adjustable see-through level (Settings → Background). Its app entries have the same hold menu (Change icon, Rename).

### Settings panel

Opened from the sidebar's Settings; a panel down the right side so Home stays visible while you change things:
- **Accounts**: Jellyfin address and key, and **Connect accounts** (sign in from your phone through a QR code).
- **Background**: black, the tablet's wallpaper, or **your own pictures**: a gallery to switch between them, each
  with an optional portrait version so a corner logo stays in its corner when the tablet turns. "+ Add" takes
  several at once; pictures dropped in the app's `Android/data/com.mau.hub.tablet/files/inbox` folder join the gallery
  too. A darken slider and a sidebar see-through slider.
- **Apps**: icon pack, favorite apps, hidden apps.
- **Home**: section titles on/off, section icons, names and order, To Watch rows.
- **Screen**: hide the status bar and the navigation bar (a swipe from the edge shows them for a moment).
- **Backup**: save every layout setting to a file and restore it (sign-ins, placed widgets and picture files are not
  included).
- **Clock**: corner clock (built-in or a widget) and timezone.
- **Weather**: location.
- Storage line at the bottom.

### Home Assistant section

The HA dashboard inside Home. It uses the home address when it answers (on the home Wi-Fi) and the public one
otherwise; both come from `local.properties`. You sign in once inside the section.

### MauHome widget

Mau's Google Home widget, button for button, run by Home Assistant (`HaWidget.kt`): see-through tiles with coloured
outlines, padlocked server plugs ask before turning off, columns follow the widget's width. It reads HA's REST API
with a long-lived token saved on the Connect page (Home Assistant section). Tiles without a sure HA match are grey
"Sin asignar" placeholders. A.R.T. Phone extended it to 36 tiles with cameras.

**Tile editor** (`HaTiles.kt`): the widget's pencil, or Settings → Home → MauHome widget. Add a tile by picking any HA
device from a searchable list (accents ignored), rename it, change its icon and what a tap does, ask before turning it
off, reorder, remove, or go back to the original tiles. Saved in prefs `ha_tiles`; the widget redraws at once.

### Home Herald widget

The house's daily paper from HA's `sensor.home_herald` (`HeraldWidget.kt`), read-only with MauHome's token, laid
out like its HA page and in the same font (Gelasio, Georgia's free twin, also embedded in the page): masthead with
the round logo, night/day and EN/ES switches, numbers 4 across, watch box, briefs. Keeps the last edition when HA is
away; a tap opens it in the HA app. Mau approved the look (no blackletter masthead).

### Other details

- Settings → Background → **Lock screen** puts the current background picture on the lock screen too.
- **Lock app** (hold menu) and Settings → **App lock** (PIN), from the TV merge; the phone doesn't have them.

- The launcher is the tablet's default Home app and is declared non-resizable (no split-screen handle on it).
- More football leagues in the GeeSports row: Premier League, Ligue 1, Bundesliga, Champions League, Europa League,
  Euros and qualifiers, Libertadores.
- Gestures (added later, commit 5006c95): finger right opens the widget page, finger left opens Beeper, double-tap on
  empty space; each set in Settings → Gestures.

## Design

- Colours (`Ui.kt`): background `#050505`, accent `#E8650A` (dark orange), soft text `#C9CBD3`, muted text `#9A9DA8`.
- Buttons on the tablet are bare icons (no circles or outlines); the clock and weather have no cards behind them.
- Background picture in use: Mau's black vertical-wood floor photo with the MauCasaOS "M" logo in the bottom-right
  corner, darkened 25 %, with a landscape and a portrait version.
- No fading at the page edges (it was tried and removed).

## Tests

`app/src/test/java/com/mau/hub/LinksTest.kt` holds the unit tests: backup round trip (and that secrets and widgets
are left out), app search, widget placement (nearest free spot), the weather and geocoding parsers, icon colour,
row order and the content parsers.

## Not done / known gaps

- 25/10/2026: Mau called the tablet **a success**. Everything below is optional.
- Not yet tried on screen: the remote cards upright (one at a time), and press-and-hold on a remote button.
- MauHome tiles: to set up together with Mau in the tile editor.
- The pull request into `main` is open, waiting for Mau to merge it; the version is still 2.0 (build 6).
- New apps installed later don't get icons on the TV until another export/import (no in-app button).

## Day log, 25/10/2026

- Joint Remote's icon: LineX `ir_remote` in the launcher orange, the remote's icon everywhere.
- MauHome widget (Google Home buttons through HA), then A.R.T. Phone's 36 tiles with cameras; HA token on the Connect page.
- Phone flavor and the TV work merged into `redesign`; the tablet checked after each merge.
- Lock screen picture from the background; Lock app and App lock on the tablet.
- Home Herald widget, matched to its HA page in the same font; HA admin made the page use the same fonts.
- MauHome tile editor. The corner flip clock now flips even with the tablet's animations switched off (it times
  itself instead of relying on Android's animation setting, which is at 0 on the tablet).
- Joint Remote keeps its own (v0.6) icon in the launcher, any pack.
- Joint Remote's icon read from its plain drawing (no HyperOS white or black box); the tile editor hides the bars too.
- Opened as an app while it's Home, the launcher hands over to Home (no stray copy with HyperOS's "•••" handle).
- Wallpaper top-edge line cleaned; the same picture on the lock screen.
- Two remote cards (Joint Remote's widget each) from top-bar buttons named after their device: side by side when
  turned, one at a time upright; pin, outer-edge close arrow, own % solid; press and hold a button to change device.
- Home button service restore made safe right after updates (shared code; matters on the TV).

## Decisions Mau made along the way

- The TV and the tablet are different devices with different jobs: tablet builds never go on the TV.
- 24-hour time everywhere, always.
- No IP addresses, tokens or passwords in the repository or in chat.
- Each A.R.T. session works on one device: A.R.T. Tablet = the tablet only.
- Ask before installs, reboots and settings changes on a device.
