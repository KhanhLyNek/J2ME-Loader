# Multi-session J2ME experimental branch

Branch: `feature/multi-session-bubbles`. Based on `feature/background-game`.
**No modifications to game JARs and no APK cloning.** The loader chooses one of
three statically declared Android emulator processes (`:midlet`, `:midlet_1`,
`:midlet_2`). Each has its own Java statics, MIDlet lifecycle, foreground
service, notification, and optional draggable overlay bubble.

## Try it (sideload debug build)

1. Build the `devDebug` APK, or download the artifact from the branch's Actions workflow after a successful run.
2. For screen-off runtime, enable **Keep game running with screen off** under Settings.
   Pick Eco or continuous mode and a bounded 15–240 minute screen-off limit.
   For **screen-on** floating sessions, choose **Thu nhỏ game / chạy nền**
   from the game's options menu while the Activity is visible.
3. Approve the overlay permission if you want floating bubbles; otherwise
   select **Chỉ dùng thông báo**. Notification-only fallback never needs overlays.
4. When minimized, the library opens. Launch a different installed game; it
   gets a free process slot. Repeat up to three concurrent games total.
5. Tap the matching bubble or ongoing notification to return to the existing
   game. Drag bubbles separately to move them. Stop a session with its
   notification's Stop action or from the game's exit menu.

## Design and known limits

- **Three fixed process slots, not unlimited cloning.** Android activities,
  service process names, and task affinities must be declared in the manifest.
  When all slots are running, new launches are rejected instead of resetting a
  game in the same Java process.
- **Different games first (for example NRO and HSO).** Each installed title
  has its own converted folder, but this prototype **does not yet isolate
  record stores or filesystem files for two instances of the same title**.
  Do not run duplicate accounts in the same installed game simultaneously.
- Android can terminate emulator processes for memory pressure, Doze, vendor
  battery management, or task removal. Foreground notifications improve
  process priority but **cannot guarantee 24/7 execution or network access**.
- The existing bounded partial wake lock remains active only while the screen
  is off and Eco mode is disabled. Screen-on minimized sessions deliberately
  avoid wake locks and skip display copies where possible. Continuous CPU and
  network activity drains battery; this cannot be made free.
- If an overlay permission is denied, the foreground service's notification
  is the reliable way back. Some vendors block activity starts from overlays.
- Session choice uses the user's own running emulator process list. If Android
  does not expose that list, the loader fails closed rather than overwrite an
  active MIDlet. Process selection is best-effort, not a transactional lock.
- Older devices that cannot grant the relevant foreground service permissions
  will revert to the ordinary pause behavior.
- Android's background activity-start and foreground-service launch rules
  apply. This branch is intended for user-initiated sideloaded use; `specialUse`
  service declarations are subject to Google Play review.
- Existing background mode's **screen-off timer** and notification stop action
  remain enabled. Do not interpret the bubble as an unlimited 24-hour session.

## Validation checklist

- Open NRO; minimize via notification-only; open HSO; open a third MIDlet.
- Inspect Android process list for `:midlet`, `:midlet_1`, `:midlet_2`.
- From each bubble, return to the correct task without recreating MIDlet state.
- Stop second game; opening another title should reuse the freed slot.
- Minimize with overlay permission denied; notification still returns.
- Screen off/on while minimized; check CPU, network, notification and timeout.
- Clear the tasks / revoke permission / enable battery saver; test graceful
  degradation rather than claiming persistent uptime.
- Repeatedly enter and exit different games; check for cross-session shared
  cache deletion, frame output, RMS corruption, memory spikes and heat.
- Build all flavors and run actual NRO + HSO tests on Android 16/HyperOS.
  **No successful device validation is claimed by this document.**
