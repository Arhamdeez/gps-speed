# Speed

A Wear OS speedometer. It keeps the watch GPS running and shows live speed in whole km/h, including on the always-on display.

The app is standalone. It does not need a phone, though it can use the phone's GPS when the watch loses its own fix.

## What you see

- A large speed in **km/h**, updated on each GPS fix.
- The clock, the fix age, and how many satellites were used.
- **Red** digits while braking, **cyan** while accelerating, and white while the speed is steady. Small GPS jitter does not change the color.
- Amber digits when the position fix is weak.
- `--` while GPS is still searching, or after the fix has gone stale.

Speeds under about 5 km/h read as 0, so a stopped watch does not flicker.

## Controls

- Allow location when the watch asks. Speed needs a fine location permission, and the GPS chip has to be on.
- Long-press the screen to switch location source:
  - **Watch only** uses the watch GPS.
  - **Watch + phone** keeps the watch GPS, and uses the phone only after the watch has been quiet for a few seconds.

The reading is the chip's Doppler speed. A single impossible jump is ignored until the next fix agrees with it. Real acceleration and braking show up on the same fix.

## Run it

Wear OS 3 or newer (Android 11, API 30). A watch with GPS is required for watch-only mode.

Connect the watch, then install and launch the debug build:

```bash
./gradlew :app:installDebug
adb shell am start -n com.watchspeed.live/.MainActivity
```

If a phone is also connected, target the watch:

```bash
adb devices -l
ANDROID_SERIAL=<watch-serial> ./gradlew :app:installDebug
adb -s <watch-serial> shell am start -S -n com.watchspeed.live/.MainActivity
```

Android Studio can deploy the `app` run configuration to the watch directly.

## Tests

```bash
./gradlew test
```

The unit tests cover speed formatting, spike rejection, and the braking / acceleration color signal.
