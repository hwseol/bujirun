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
- Login is required. Accounts live on the Bujirun API (backend/, deploy notes in
  backend/deploy/README.md): unique email and nickname, password (PBKDF2), opaque session token
  stored on the phone. No email verification; forgotten passwords go through
  backend/reset_password.py on the server. The admin email is the server secret
  `BUJIRUN_ADMIN_EMAIL` (not in this public repo); the app trusts the server's `is_admin`.
- The repo is public on GitHub (hwseol/bujirun): never commit keys, the admin email, or SSH
  details. Server access notes live outside the repo.
- Test the app against a local server: run `uvicorn server:app --port 8100` in backend/ (the
  EnglishBite venv has fastapi) and build with `-PbujirunApi=http://10.0.2.2:8100/`.
  Backend tests: `python -m unittest test_accounts` in backend/.
- Admin's start button offers a real workout or a fake-GPS test run at 1x/5x/10x (FakeRun +
  a scaled clock in WorkoutService, everything reads `now()`). Test runs are saved with
  `simulated = 1`, titled "(테스트)", and never exported to Health Connect.
- The DB is at version 7 (v2 `simulated`, v3 course tables, v4 `workouts.courseId`, v5
  `workouts.steps`, v6 `auto_activities` + `day_steps`, v7 `workout_photos`); testers hold
  real data, so add migrations, never destructive fallbacks.
- Workout photos (data/WorkoutPhotos): phone photos found by DATE_TAKEN within the workout
  (+-3 min), placed on the route by time (PhotoPlaces), no photo location needed; cloud-only
  ones are added through the system photo picker and kept in `workout_photos`.
- 자동 기록 (tracking/PassiveRecorder, logic in Passive.kt): activity-recognition transitions
  + the step counter, no GPS; walks >= 15 min / runs >= 5 min become `auto_activities`, steps
  go to `day_steps` (read on transitions, app open, and every 3 h). Can't fire transitions on
  the emulator: test by calling `PassiveRecorder.onTransitions` from a temporary hook.
- Waterside paths are prebuilt on the PC (`BJ_DATA_DIR=<dir> python waterside.py prebuild
  <places.db> S W N E [tile]`, resumable; dense tiles that time out: rerun with tile 0.01) into
  waterside.db `cells`, then merged into the server's waterside.db (ATTACH + INSERT OR REPLACE).
  Capital region (36.9-38.0, 126.5-127.6) is done; elsewhere falls back to live Overpass.
- Calendar/Health auto export runs in data/AutoExport (on save and on app open), not only
  from the summary screen.

- Notices to all users: `.venv/bin/python notices.py add "제목" "내용" [days]` in backend/ on the
  server (BJ_DATA_DIR set, as for the service); also `list` / `remove <id>`. The app polls
  GET /notices on launch (dialog) and every 6 h via WorkManager (phone notification).

## Languages
- Korean (default) and English, picked in Settings: every text is `tr("한국어", "English")`
  (Lang.kt); enums with labels keep both and expose a `label`/`spoken` getter, top-level lists
  of texts must be getters. CueText speaks English with an English TTS voice. Course requests
  (IntentParser, NLU model) and server texts stay Korean.

## Natural-language courses
- Design in docs/NL_COURSES.md. The phone reads the request (course/IntentParser rules for numbers
  + course/NluModel, a 94 KB char BiGRU in assets/nlu.bin); the server plans the course
  (backend/planner.py, POST /routes/plan) from places.db (parks, lakes, beaches, rivers).
- Retraining the model: tools/nlu (generate.py writes data with Gemini, free-tier key in
  ~/.secrets/gemini_free_api_key.txt; train.py exports nlu.bin plus the Kotlin parity fixtures).
  Never run generation on a key whose project has billing without asking.
- places.db is built on the PC (places.py sync, osm.py sync) and copied to the server, so the
  shared server does no heavy work. Keep server usage minimal: the user pays for it.
- Shade (docs/SHADE.md): tree cover bitmap in places.db (tools/shade/build_shade.py, run with
  `python -P` so the download folder can't shadow imports), backend/shade.py scores routes and
  computes the sun; courses prefer shade when asked or when the sun is above 35 degrees.
- Hills: Copernicus DEM in places.db (tools/elevation/build_elevation.py, same `python -P`);
  backend/elevation.py gives climb, steepest grade and a terrain label per course; 평지 requests
  prefer flat options. The DEM includes trees/buildings, hence the median filter.
## Pinned versions
- `connect-client` is on 1.1.0-alpha12 because 1.1.0 stable needs AGP 8.9.1 (we're on 8.7.2).
- NAVER map client ID lives in app/build.gradle.kts manifestPlaceholders; it's public by design.
  Never add the client secret.
