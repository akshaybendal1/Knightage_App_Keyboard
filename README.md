# BT Keyboard

Android app that turns your phone into a **Bluetooth keyboard + touchpad** for another
phone, tablet, laptop or TV. No app is needed on the other device — it sees a standard
Bluetooth HID keyboard and mouse.

## Features

- Compact keyboard: letters, two symbol pages, F1–F12, arrows, Esc/Tab/Ctrl/Alt/Win,
  Home/End/PgUp/PgDn/Del, and media keys (volume, mute, play/pause, prev/next).
- Sticky modifiers: tap **Ctrl** then **C** = Ctrl+C. Double-tap a modifier to lock it.
- Keys are held while your finger is down, so holding ⌫ auto-repeats on the host and
  multi-finger chords work.
- Touchpad: drag to move, tap = left click, two-finger tap = right click, two-finger drag =
  scroll, double-tap-and-hold = drag/select. L / M / R buttons for press-and-hold.
- Modes: Keyboard, Touchpad, or Both.
- "Type a phrase" box: use your phone's own keyboard (voice, swipe) and send the text.
- Remembers the last host and reconnects automatically.

## Requirements and limits

- Android 9 (API 28) or newer, using the platform `BluetoothHidDevice` API.
- Some manufacturers ship ROMs with the HID Device profile disabled. On those phones the app
  shows "doesn't allow Bluetooth HID" and **cannot work** — this is a firmware limit, not
  an app bug.
- Only one app at a time can act as a HID device on a phone.
- Text sending uses the US keyboard layout; set the host's layout to US English for symbols
  to come out correctly. Non-ASCII characters (emoji, accented letters) are skipped.
- iPhone/iPad accept the keyboard; the mouse needs AssistiveTouch enabled on iOS.

## Build

Open this project in Android Studio and press Run, or:

```sh
./gradlew assembleDebug     # APK: app/build/outputs/apk/debug/app-debug.apk
```

The GitHub Actions workflow `.github/workflows/build.yml` builds the debug APK on every
push and uploads it as the `bt-keyboard-debug-apk` artifact.

## Pairing

1. Open the app and grant the Bluetooth permissions.
2. Tap **Connect → Pair a new device**; the phone becomes visible for 2 minutes.
3. On the laptop/phone, open Bluetooth settings and select this phone. Accept the code.
4. Next time, tap **Connect** and choose the device from the list.

If a host was paired with this phone *before* the app was installed, remove the pairing on
both sides and pair again, so the host picks up the keyboard/mouse profile.
