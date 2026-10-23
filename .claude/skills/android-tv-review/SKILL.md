---
name: android-tv-review
description: Review and improve Joint Launcher (an Android TV home screen built from plain Android Views in Kotlin, no Compose, no Leanback) against Google's Android TV design guidelines - D-pad focus, 10-foot readability, overscan, cards, navigation, launcher behavior. Use when redesigning, polishing or code-reviewing any TV screen, tile, row or focus behavior in this repo.
argument-hint: "[file, screen or 'all']"
---

# Android TV review

Joint Launcher builds its UI in code (`app/src/main/java/com/mau/hub/MainActivity.kt`, `PlayerActivity.kt`):
`FrameLayout`/`LinearLayout`/`HorizontalScrollView`, `GradientDrawable` backgrounds, `setOnFocusChangeListener` for focus.
Review and fix in that idiom. Do not propose Compose, Leanback or Material libraries; the app has no UI dependencies and a
redesign must not become a rewrite.

## Steps

1. Read the screen code in scope. Note every focusable view, its focus listener, and its click handler.
2. Check it against the rules below. Record each finding with file:line.
3. Split findings into **Focus/navigation defects** (fix first) and **Visual polish**.
4. When changing code, keep behavior identical. Change looks, not logic.
5. Verify on the TV (below) before calling anything done.

## Rules (from developer.android.com/design/ui/tv)

**Focus — the most important thing on TV**
- Every actionable view is `isFocusable = true`; nothing actionable is touch-only.
- The focused item is unmistakable at a glance from the couch: scale up (about 1.05–1.1x), a bright outline or glow, and/or a raised surface. Color change alone is not enough.
- One focus look across the whole app (same scale, outline, animation duration). Animate focus changes briefly (~150 ms); never let focus jump without feedback.
- Initial focus lands on the most likely action when a screen opens, and returning to a screen restores focus to where the user was.
- D-pad order is predictable: left/right inside a row, up/down between rows, no dead ends, no focus escaping to invisible views. Use `nextFocus*Id` only where the default search gets it wrong.
- No control in a hard-to-reach spot (for example a search button that can only be reached by crossing a whole row).

**Layout**
- Design on a 960x540 dp canvas. Keep primary content inside a ~5% safe margin: 58 dp left/right, 28 dp top/bottom.
- Backgrounds and row content may bleed off-screen; do not clip them to the safe area. A partly visible next tile signals "more this way".
- Horizontal rows of cards scroll sideways; vertical stacks of rows scroll down. Keep hierarchy flat; avoid nested layers.
- Grid reference: 12 columns, 52 dp wide, 20 dp gutters.

**Typography**
- Legible from ~3 m: sans-serif, large counters, no thin weights, no decorative fonts for UI text.
- Use one type scale (display / headline / title / body / label) and stick to it. Body text no smaller than what reads comfortably from the couch; labels on tiles short.

**Cards / tiles**
- Image-first; text on top of images gets a scrim for contrast.
- Short titles; no long descriptions on stacked cards.
- Consistent aspect ratios per row; focused card is the only one that changes size.

**Color**
- Dark backgrounds suit the living room; avoid large pure-white areas. Keep enough contrast for text over artwork.

**Navigation**
- No on-screen Back button; the remote's Back does that. Back on the home screen should not exit to nowhere.
- Fewest possible steps to content. Home always returns to the launcher.

**Launcher specifics**
- Manifest keeps `CATEGORY_HOME` + `CATEGORY_LEANBACK_LAUNCHER` and a 320x180 px (xhdpi) banner.
- Home screen loads fast; show tiles immediately and fill artwork in afterwards.

## Verify on the TV

Build and install as in `README.md` (debug build). Then drive it with the remote keys over ADB:

```bash
/c/adb/adb.exe shell input keyevent KEYCODE_DPAD_RIGHT   # also _LEFT _UP _DOWN _CENTER, KEYCODE_BACK, KEYCODE_HOME
/c/adb/adb.exe exec-out screencap -p > shot.png
```

Take a screenshot before and after each change, and walk every row with the D-pad to confirm focus order and the focus look.

## Output

A short list: **Focus/navigation defects** then **Visual polish**, each with file:line, the rule it breaks, and the fix.
