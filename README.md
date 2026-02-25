# touchpadCZN

A dual-display Android controller that turns the **secondary screen of the AYN Thor** (or any Android device with two displays) into a **trackpad** that controls the primary screen with real touch gestures.

---

## What it does

You install this app on your AYN Thor (or similar dual-display Android handheld). The bottom display becomes a full touchpad that controls the top display — like a laptop trackpad, but it injects real Android touch events via the Accessibility Service API.

| Gesture on secondary screen | Effect on primary screen |
|-----------------------------|--------------------------|
| Slide finger                | Move cursor              |
| Quick tap (< 150 ms)        | Click at cursor position |
| Hold ~120 ms then slide     | Drag / scroll            |
| Hold ~700 ms then drag      | Long-press drag (drag & drop) |
| `[+] NAV-LOCK` button       | Block swipe-out navigation (keeps app open) |

---

## Screenshots

> *(secondary display — pixel art UI)*

```
╔══════════════════════════════╗
║  ·  ·  ·  ·  ·  ·  ·  ·    ║
║                              ║
║    > SLIDE     : move cursor ║
║                              ║
║    > TAP       : click       ║
║                              ║
║    > HOLD+DRAG : scroll/drag ║
║                              ║
║  ·  ·  ·  ·  ·  ·  ·  ·    ║
╠══════════════════════════════╣
║  SPEED:  [Normal [2x]] [Fast [3.5x]] ║
╠══════════════════════════════╣
║  [+] NAV-LOCK              ■ ║
╚══════════════════════════════╝
```

---

## Requirements

- Android **9.0 (API 28)** or higher
- A device with **two displays** (e.g. AYN Thor, Odin 2, foldable, or any Android with a secondary screen)
- The **Accessibility Service** must be manually enabled by the user (Android requirement — the app cannot enable it itself)

---

## Installation

### Option A — Build from source

1. **Clone the repo**
   ```bash
   git clone https://github.com/YOUR_USERNAME/touchpadCZN.git
   cd touchpadCZN
   ```

2. **Build the APK**
   ```bash
   ./gradlew assembleDebug
   ```
   Output: `app/build/outputs/apk/debug/app-debug.apk`

3. **Install on device**
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

### Option B — Install prebuilt APK

Download `touchpadCZN.apk` from the [Releases](../../releases) page and sideload it on your device.

---

## Setup (first time)

1. **Open the app** → you'll see the main setup screen.

2. **Enable the Accessibility Service**
   Tap *"Enable Accessibility Service"* → find **touchpadCZN Controller** in the list → toggle it on → confirm the permission prompt.

   > This is required so the app can inject touch gestures on the primary screen. It is only used for this purpose — no data is collected or transmitted.

3. **Plug in / activate your secondary display**
   The app detects it automatically and shows its resolution.

4. **Tap *"Launch Secondary Display Controller"***
   The pixel-art trackpad UI appears on the secondary display.

5. **Use it!**
   Slide to move the cursor. Tap to click. Hold ~120 ms then slide to scroll or drag.

---

## Nav Lock

The **`[+] NAV-LOCK`** button in the toolbar disables Android's side-swipe navigation gestures while you're using the trackpad. Without it, swiping from the screen edges can close the controller.

- `[+] NAV-LOCK` → enabled, swipe-out gestures are blocked
- `[-] NAV-FREE` → disabled, normal navigation restored

This uses `setSystemGestureExclusionRects` (Android 10+).

---

## Cursor speed

Two levels, selectable at any time from the secondary display:

| Button | Multiplier | Description |
|--------|-----------|-------------|
| Normal [2x] | 2.0× | Default — comfortable for everyday use |
| Fast [3.5x] | 3.5× | Faster — better for large screens |

An **edge acceleration** curve (cosine smoothing) automatically boosts sensitivity near the screen edges so you can reach corners easily without changing the center-screen speed.

---

## Architecture

```
┌─────────────────────────────┐     ┌────────────────────────────┐
│     Secondary Display        │     │      Primary Display        │
│                              │     │                             │
│  SecondaryDisplayPresentation│     │  ControllerAccessibility    │
│  ┌────────────────────────┐  │     │  Service                    │
│  │     TouchpadView        │─────▶│  ┌──────────────────────┐   │
│  │  (gesture recognition)  │  │     │  │  GestureDescription  │   │
│  └────────────────────────┘  │     │  │  continueStroke chain│   │
│  Sensitivity picker           │     │  └──────────────────────┘   │
│  Nav Lock toolbar             │     │                             │
└─────────────────────────────┘     │  CursorOverlayView          │
                                      │  (TYPE_ACCESSIBILITY_OVERLAY)│
                                      └────────────────────────────┘
```

### Key classes

| Class | Responsibility |
|-------|---------------|
| `MainActivity` | Setup UI — guides user through accessibility enable + display detection |
| `ControllerAccessibilityService` | Injects gestures on primary display via `dispatchGesture` + `continueStroke` chain |
| `CursorOverlayView` | Draws the cursor pointer on the primary display (accessibility overlay window) |
| `TouchpadView` | Full-screen touchpad on secondary display — gesture state machine (PENDING / HOVER / DRAG) |
| `SecondaryDisplayPresentation` | `android.app.Presentation` wrapper that hosts the secondary display UI |
| `SettingsManager` | Persists cursor sensitivity to `SharedPreferences` |

### Gesture injection mechanism

Android's Accessibility API provides `dispatchGesture(GestureDescription, callback, handler)`. To simulate a continuous hold/drag touch that can be updated in real time, the app uses **chained `continueStroke` segments**:

```
startTouch()
    │
    ▼
[initial stroke 50ms, isContinued=true]
    │ onCompleted callback
    ▼
[next stroke 40ms, isContinued=true] ◄── moveTouch() updates lastX/lastY
    │ onCompleted callback
    ▼
[next stroke 40ms, isContinued=true]
    │ ...
    │ endTouch() sets pendingRelease=true
    ▼
[final stroke 40ms, isContinued=false] → Android sends pointer-up event
```

While the finger is stationary, the app sends ±0.5 px oscillating stubs to keep the gesture chain alive without drifting position. 0.5 px is far below Android's long-press slop (~8–16 px), so long-press and drag-and-drop still trigger normally.

### Touchpad state machine

```
ACTION_DOWN
    │
    ▼
 PENDING ──── fast swipe (>15px/frame) ──────▶ HOVER (cursor only)
    │                                              │
    │ hold 120ms (slow/no movement)                │ any move
    ▼                                              ▼
  DRAG ◄─── restart if chain breaks ─── move + gestureActive=false
    │
    │ ACTION_UP
    ▼
  endTouch() → final release stroke
```

---

## Project structure

```
app/
└── src/main/
    ├── java/com/dualpad/controller/
    │   ├── MainActivity.kt                 — setup screen
    │   ├── ControllerAccessibilityService.kt — gesture injection
    │   ├── CursorOverlayView.kt            — cursor on primary
    │   ├── TouchpadView.kt                 — trackpad UI + gesture recognition
    │   ├── SecondaryDisplayPresentation.kt — secondary display container
    │   └── SettingsManager.kt              — SharedPreferences wrapper
    ├── res/
    │   ├── drawable/
    │   │   ├── ic_launcher_background.xml  — dark background
    │   │   └── ic_launcher_foreground.xml  — pixel art mouse icon
    │   ├── layout/
    │   │   └── activity_main.xml           — setup screen layout
    │   ├── values/
    │   │   ├── strings.xml
    │   │   └── colors.xml
    │   └── xml/
    │       └── accessibility_service_config.xml
    └── AndroidManifest.xml
```

---

## Build configuration

| Property | Value |
|----------|-------|
| `compileSdk` | 34 (Android 14) |
| `minSdk` | 28 (Android 9) |
| `targetSdk` | 34 |
| Language | Kotlin |
| Build system | Gradle 8 |

---

## Permissions

| Permission / feature | Reason |
|----------------------|--------|
| `BIND_ACCESSIBILITY_SERVICE` | Required to inject touch gestures on the primary display |
| `TYPE_ACCESSIBILITY_OVERLAY` window | Used to draw the cursor over any app on the primary display |
| `canPerformGestures="true"` | Enables `dispatchGesture` API |

**No internet permission. No data collection. No analytics.**

---

## Known limitations

- Requires manual Accessibility Service activation (Android does not allow apps to enable it programmatically — this is a security feature).
- On some custom Android builds, `GLOBAL_ACTION_HOME` may behave unexpectedly; the app uses an explicit `ACTION_MAIN` / `CATEGORY_HOME` intent as a workaround.
- The `continueStroke` chain may occasionally be cancelled by the Android gesture system (e.g., during system animations). The app auto-restarts the chain immediately on the next move event.

---

## License

MIT — free to use, modify, and distribute. See [LICENSE](LICENSE) for details.
