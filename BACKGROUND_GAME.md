# Screen-off background MIDlet mode (experimental)

This branch is based on the last successful `feature/external-storage` DX Stack Fix.
**Glass UI is not included.** Game files stay in `/storage/emulated/0/J2ME/`.
This is a **sideload-only, user-initiated** feature; the Google Play Store may
reject the declared `specialUse` foreground service unless it qualifies for
Play policy review.

## Enable on Android 16 / HyperOS 3

1. Install the **devDebug** build on this branch and open **Settings**.
2. Enable **Keep game running with screen off**. On Android 13+, grant the
   notification permission when requested, so the Stop action stays visible.
3. Pick a session limit (15 / 30 / 60 / 120 / 240 minutes). Default is 60.
4. Leave **Eco mode** off if the game must process data continuously. Eco mode
   deliberately does not hold a wake lock and **cannot guarantee** continuous
   game or network operation.
5. Launch a MIDlet, confirm the *Background MIDlet* notification is present,
   then turn off the display. Tap **Stop background mode** from the
   notification to end the session.
6. If HyperOS disconnects the game, inspect the app's Battery / background
   activity settings manually. The Settings page provides a shortcut.

## Battery and compatibility rules

- The foreground service starts from the **foreground game Activity** in the
  same `:midlet` process and is **not restarted automatically** by Android.
- A non-reference-counted `PARTIAL_WAKE_LOCK` is acquired **only on screen
  off** in continuous mode. It is released on screen on, stop, timeout or
  destruction of the service. Its timeout is bounded to the chosen session.
- The MIDlet stays logically active and receives its paint callbacks, but
  presentation-frame copies and screen/GPU flush requests are skipped where
  possible while the display is off. JSR game logic is not intentionally
  slowed by imposing a fake FPS limit.
- Switching to another app with the display **on** pauses the MIDlet. The
  opted-in game resumes when the screen turns off, and pauses again after
  the screen turns on while its Activity remains hidden.
- Android Doze / HyperOS policies may **still suspend network connections**
  or restrict execution. CPU wake locks do not guarantee network access in
  Doze. No root, Shizuku, or hidden Android APIs are used.
- A foreground service, notifications, CPU wake locks and networking do cost
  battery. Long sessions will use substantially more power than normal
  standby. Do not enable this while you do not need it.

## Manual validation

- Normal play, pause, settings and JAR installation with mode OFF (regression).
- Screen off 2 minutes with CPU mode ON: game remains active; surface not
  rendered; notification visible; screen-on unlock resumes display.
- While app is hidden with screen ON: game pauses.
- Stop action with screen OFF: wake lock released; MIDlet pauses.
- Expiration at 15 minutes: background stops and feature disables for safety.
- Eco mode with screen OFF: reduced battery draw but runtime may suspend.
- Exiting the MIDlet: service and wake lock disappear.
- Android 16 notification permission denied: enabling is blocked until granted.
- HyperOS battery saver / Doze: test actual network reconnect behavior.

The GitHub Actions workflow `.github/workflows/external-storage-debug.yml`
builds the APK artifact `J2ME-Background-Game-debug` from this branch.
