# Joint Launcher Phone (JLP)

How the phone version of Joint Launcher was built, what it does, and how to work on it. Written by the A.R.T. Phone
session (Android Redesign Team), 25/10/2026.

## What it is

The phone version replaces Pro Launcher (`app.prolauncher`, which the owner paid for) on his POCO F5 Pro. It keeps what he
liked about Pro Launcher and matches the rest of his setup (the black wood + M wallpaper, LineX icons, the flip clock,
Home Assistant):

- Home is a centred text list of his apps, A to Z.
- Swipe up opens every app, sorted into his emoji categories, with instant search.
- Swipe right opens a widget page that keeps growing; swipe left opens Beeper.
- The MauHome widget gives one-tap Home Assistant switches, lights and cameras.

| | |
|---|---|
| App name | Joint Launcher Phone |
| Package | `com.mau.hub.phone` |
| Device | POCO F5 Pro ("MauF5Pro", 23013PC75G), HyperOS, Android 15, portrait, 3-button navigation bar |
| Code | this repository: product flavor **`phone`**, worked on in branch `phone` (folder `C:\Users\mauri\joint-launcher-phone`), merged into `redesign` and `main` (PR #1) |
| Device notes | `~/.claude/agents/art/phone.md` and `network.md` (ADB access and house rules) |

It is the third flavor of the same app (see `docs/JLT.md`, "One codebase, separate apps"). At runtime `Hub.phone`
(`BuildConfig.FLAVOR == "phone"`) switches on the phone's Home. The phone is also a touch device (`Hub.touch`), so it
shares the tablet's widgets, settings panel, icon packs and backups.

## Building and installing

```bash
export JAVA_HOME="C:/Users/mauri/android-tools/jdk/jdk-17.0.20.1+1"
/c/Users/mauri/android-tools/gradle-8.9/bin/gradle -p /c/Users/mauri/joint-launcher-phone testPhoneReleaseUnitTest
/c/Users/mauri/android-tools/gradle-8.9/bin/gradle -p /c/Users/mauri/joint-launcher-phone assemblePhoneRelease
/c/adb/adb -s <phone-ip>:5555 install -r app/build/outputs/apk/phone/release/app-phone-release.apk
```

Lessons learned:
- **HyperOS refuses every first install over adb** with `INSTALL_FAILED_USER_RESTRICTED` and no prompt, whatever
  the settings say ("Install via USB" on, `pm install`, the wireless-debugging port: all refused). The first install
  works this way: copy the APK to `/sdcard/Download/`, make the media scanner see it (an adb push doesn't), and the owner
  taps it once:
  `am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/<apk>`.
  `am start -a android.intent.action.VIEW -d content://media/external/file/<id> -t application/vnd.android.package-archive`
  opens the installer for him. After that, `install -r` updates over adb work (same signing key).
- In Git Bash set `MSYS_NO_PATHCONV=1` for adb commands with phone paths, or `/sdcard/...` gets rewritten to a
  Windows path.
- R8 sometimes fails with a file lock; run it again.
- Default Home: `cmd package set-home-activity com.mau.hub.phone/com.mau.hub.MainActivity` (only when the owner asks).
- Never screenshot the phone to check the app: read only the app's own nodes from `uiautomator dump`, then delete
  the dump file. A screenshot once caught a private chat.

## What the phone has

### Home (`Phone.kt`, `PhoneHome`)
- The flip clock in the middle; tapping it opens the clock app (hold it to pick another). The weather is on the right,
  borrowed from the top bar (`TopBar.takeWeather`). The date is underneath and opens the calendar.
- A centred list of the Home apps, A to Z by the name shown. Category entries like `#` open All Apps on that
  category. Hold an entry for Rename, Change icon, Remove from Home and App info.
- Text or icons: Settings → Home switches between names only and LineX icon + name, both on Home and in the list.
- Bottom shortcuts: a row of icons. Hold one to move it or remove it.
- Hold empty space to open Settings, as in Pro Launcher.
- Android's status and navigation bars are hidden on Home (Settings → Screen); a swipe from the edge shows them.
- Background: the wallpaper as it is (`bg_mode` defaults to wallpaper, darkening 0 %).

### All Apps (`AllApps`), swipe up
- Category icons across the top: "all" first, then each emoji category. The four old Pro Launcher word categories
  were folded in: Productivity → ⚙️, Social → 🍥, Music → 🎵, Game → 🎮.
- Search box: filters as you type, and opens the app by itself when only one is left. Go opens the first match.
- The list is centred and A to Z. Settings → Home has a slider for how see-through it is (`drawer_dim`, default 94 %).
- Hold an app for: Add to / Remove from Home, Add to / Remove from the shortcuts, Category, Rename, Change icon, Hide,
  App info, Uninstall.
- Swipe down while the list is at its top to close it. Back and Home close it too.

### Gestures (`Gestures.kt`)
- **Swipe right** opens the widget page; **swipe left** opens Beeper (`com.beeper.android`), sliding in from the
  right. **Swipe down** on Home pulls down the notifications. **Double-tap** on empty space does nothing by default;
  it can lock the screen. Each one is set in Settings → Gestures.
- Back from Beeper: while Beeper is on screen, a swipe right from the very left edge goes back Home. It works through
  the accessibility service (`HomeButtonService`, shown on the phone as "Joint Launcher gestures"). The service puts
  a 16 dp invisible strip on the left edge, only over the app being watched. That edge is free because the phone uses
  the 3-button bar. The service runs in its own process (`:home`), so the app talks to it by broadcast:
  `HomeButtonService.watch()` and `global()` (lock). The owner switched it on in Accessibility on 25/10.
- Swipes that start on something that scrolls that way (the category row, the list) are left to it.

### Widget page (`WidgetPage` in `Gestures.kt`, `Widgets.kt`)
- It sits to the left of Home. When the widget page is on, the Widgets section moves off Home and onto the page.
- The grid grows downwards: a widget with no free spot goes in new rows under the rest. Settings → Gestures →
  "Widget page height" sets how many screens tall it starts (1-10, `widget_screens`). Dragging the grid's handle in
  edit mode still works; whichever you changed last wins.

### MauHome widget (`HaWidget.kt`, shared with the tablet)
- Tiles: the owner's own list lives in `app/src/main/assets/ha_tiles.json`, which git ignores; it is bundled into
  builds made on this PC. Without it the widget shows example tiles (`HaWidget.TILES`, same format as
  `app/ha_tiles.example.json`). Tiles edited in the tile editor are saved on the phone and win over both. Up to 5
  across on the phone, following the widget's width.
- Tiles with a guard (strips feeding servers, the servers themselves) ask before turning off.
- Camera tiles show the latest still from `/api/camera_proxy` (small, RGB_565, because a widget's pictures share one
  size-limited bundle). Tapping one opens it big, refreshing every 2 s. A camera that only streams over RTSP has a
  picture only while the stream is live; otherwise HA answers 500 and the tile says "Sin imagen".
- The token goes in Settings → Home Assistant on the phone: a hidden field, no autofill, spaces and a pasted "Bearer"
  stripped. The pref is `ha_token`, kept out of backups. Working since 25/10 with the owner's new token.

### Pro Launcher import (`ProLauncherImport` in `Phone.kt`)
- Settings → Import from Pro Launcher, or Restore from a file: both accept a .zip. Pick
  `Download/Pro Launcher/Pro_Launcher_Backup_<date>.zip` (made by Pro Launcher → Settings → Backup).
- It reads `apps.json` (renames, categories, hidden apps), `shared_prefs.json` (Home list, category order) and
  `datastore.json` (bottom shortcuts, whose list runs right to left; the swipe-left app). Nothing else from the
  backup is kept: it also holds purchase ids.
- Imported 25/10: 13 Home entries (Agenda, Buscar, Chat, Claves, Correo, Home, Internet, Mapas, Metro, Música,
  Tienda, Videos, `#`), 7 shortcuts, and 13 emoji categories, 🏠 House included.

## Settings on the phone
The tablet's panel at full width. The tablet-only parts are hidden on the phone: Accounts/Connect, the Home sections,
Favorite apps, sidebar opacity and the corner clock. The phone adds:
- **Home**: Text/Icons, and the keyboard opening with the list.
- **App list** see-through slider.
- **Home Assistant** token.
- **Pro Launcher** import.

## Tests
`ProLauncherImportTest` covers home order, renames, categories (the folding included), hidden apps, the shortcut
order, the swipe-left app, and a zip that isn't a backup. `HaWidgetTest` (in `LinksTest.kt`) covers states, single
lamps vs groups, which tiles ask first, and the 36-tile count.

## Not done / known gaps
- Which switch each MauHome tile controls: the owner will adjust some.
- Tile editing in the widget (add/remove/reorder tiles from HA) is not built yet.
- Pro Launcher extras not copied: the screen-time figure, the calendar line under the date, Notes, Interrupt.
- Accounts (Jellyfin, Stremio, …) are hidden on the phone. A.R.T. Watch would need them later to sign the watch in.
- Next merge of `redesign` into `phone` brings the TV code: check Settings → Screensaver and «Lock app» on the phone
  (hide them there if they don't fit).
- Pro Launcher is still installed (not the default Home).

## Decisions the owner made along the way
- Swipe right opens the widget page, swipe left opens Beeper. The left edge goes back from Beeper.
- Home apps can be text or LineX icons. App names are centred, and the list's transparency is adjustable.
- Fold Pro Launcher's old word categories into the emoji ones. 🏠 House (his home setup) matters most.
- Status and navigation bars hidden on Home.
- MauHome: his Google Home favourites, then everyday lights, plugs and modes, then the cameras.
- The widget page grows. Its starting height is a Setting, not only a drag.
- Phone work in its own worktree and branch, `phone`. Pushed, then merged into `redesign` and `main` on his say-so.
