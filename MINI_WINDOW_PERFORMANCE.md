# J2ME Multi-Session: battery optimizations and mini window

This is an experimental implementation. **CI compilation is not a battery test**.
The host Android foreground service and CPU wake lock can still drain the
battery, especially with multiple online sessions and the screen turned off.

## Screen-off and minimized rendering

* Foreground game: retain the configured FPS limit.
* Hidden game while the screen is on: cap presentation-driven frame loops at
  about 12 FPS.
* Expanded mini window: cap at about 18 FPS, but sample no more than about
  6 input frames per second and paint the thumbnail about 4 times per second.
* Screen off: cap presentation-driven frame loops at about 8 FPS. Do not copy
  offscreen images to the presentation buffer or refresh the overlay preview.
* If the user's own FPS setting is lower, keep the lower setting.
* Use a monotonic clock for frame pacing.
* No change to arbitrary game threads, Java timers, sockets or native audio.
  A MIDlet that spins in its own loop can still consume considerable CPU.
* Background-game Eco mode avoids forcing the CPU awake while the screen is
  off, but Android may suspend the game and disconnect network sessions.

The limits above are **approximate loop caps, not power consumption metrics**.
They can slow gameplay or game logic that depends on the display frame rate.

## Draggable mini-game window

1. Grant Display over other apps for this app.
2. Run a game and choose Minimize.
3. Tap its round game-name bubble. An overlay shows a low-rate live thumbnail.
4. Use the displayed directions, OK, L/R, 7/9 or tap the thumbnail.
5. Drag the title to reposition, tap **−** to return to the round bubble.
6. Tap **⛶** to reopen the full Activity.

The mini window is a low-rate **preview and input remote**, not a second Android
SurfaceView. It does not show the J2ME virtual keyboard layout; games requiring
more buttons should return to full-screen. Rendering pauses when the display is
turned off. If permission or an OEM background-launch restriction blocks the
overlay, use the foreground notification to return to the game.

## Input safety

Virtual keys receive Key Released during surface destruction, before their
target is cleared. The menu key is not activated while cancelling input. This
prevents stuck GameCanvas.getKeyStates() after locking the screen.

## Compare device performance

For reproducible comparisons use the same device, games, accounts, network,
brightness, background apps, duration, charging state, temperature and Android
battery settings. Compare **screen off / bubble collapsed** between previous
and current APK. Sample at least 15–30 minutes per condition after a cool-down,
and make multiple trials. Measure battery percentage, battery voltage/current
when available, surface temperature and game connectivity/response latency.

Useful ADB commands (replace the package name if using another build variant):

```sh
adb shell dumpsys batterystats --reset
adb shell dumpsys battery
adb shell dumpsys power
adb shell dumpsys cpuinfo
adb shell dumpsys batterystats ru.playsoftware.j2meloader.debug
adb logcat -d -s J2ME-Background J2ME-Slots J2ME-Minimize
```

## Build variants and signing

The CI workflow always builds the installable **devDebug APK** and verifies
that all three emulator Activities and Services retain their isolated Android
process assignments. It also *attempts* an R8-minified **devRelease APK**.
CI signing is generated ephemerally: this artifact will NOT update the
official app, and its original application ID can conflict with the official
package. Do not uninstall the official app without backing up its data.

## Limitations

There is no guarantee of 24/7 uptime when Android's OEM power manager kills
the process. A PARTIAL_WAKE_LOCK prevents CPU suspension only during the
bounded, user-enabled screen-off period. No overlay remains visible on a
powered-off screen, and minimizing games does not magically stop networking
or simulation CPU activity. Repeated overheating is a reason to shorten the
session or enable Eco rather than increasing wake-lock duration.
