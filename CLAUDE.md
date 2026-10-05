# Bujirun (부지런)

Korea-only running/walking app: phone + earphones, voice coaching, NAVER map. Product plan in
PLAN.md, Phase 1 design and milestones (M1-M6) in docs/PHASE1.md.

## Build and test APKs
- Debug builds are split per CPU type (the NAVER map SDK's native code is ~23 MB per ABI):
  - phone: `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` (~28 MB)
  - emulator: `app/build/outputs/apk/debug/app-x86_64-debug.apk`
- Sending to the phone through chat has a 30 MiB limit. Copy the arm64 APK to `dist/` with a
  descriptive name (e.g. `dist/bujirun-m6.apk`, git-ignored) and send that. If it grows past
  the limit, look at what was added before reaching for other delivery routes.
- `.\gradlew.bat testDebugUnitTest assembleDebug` from PowerShell. Pure logic (tracking/, coach/,
  data/Gpx) has JVM unit tests; keep new decision logic pure and tested the same way.

## Emulator testing
- Fake a run by feeding `adb emu geo fix <lon> <lat>` once a second (Korea coordinates, e.g.
  126.996, 37.51 near Banpo). The emulator reports speed 0 with a confident accuracy while
  moving; SpeedMeter takes the higher of reported and displacement speed for that reason.
- `adb shell am crash com.mhmh2.bujirun` mid-run tests crash recovery (workouts are flushed
  every 10 s and recovered on next launch).

## Accounts and admin test runs
- Login is required but local-only: email + nickname, no verification, no server (yet). Anything
  that isn't an email address is rejected. Admin = `admin@example.com` (AccountRules.ADMIN_EMAIL).
- Admin's start button offers a real workout or a fake-GPS test run at 1x/5x/10x (FakeRun +
  a scaled clock in WorkoutService, everything reads `now()`). Test runs are saved with
  `simulated = 1`, titled "(테스트)", and never exported to Health Connect.
- The DB is at version 2 (added `simulated`); testers hold real data, so add migrations, never
  destructive fallbacks.

## Pinned versions
- `connect-client` is on 1.1.0-alpha12 because 1.1.0 stable needs AGP 8.9.1 (we're on 8.7.2).
- NAVER map client ID lives in app/build.gradle.kts manifestPlaceholders; it's public by design.
  Never add the client secret.
