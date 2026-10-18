# Bujirun API - deploy

Runs on the EnglishBite EC2 server (see that repo's backend/deploy/README.md for the server
itself: SSH key, security group, backups), as a separate service with its own data:

| | |
|---|---|
| address | `https://bujirun.184-193-203-68.sslip.io` |
| service | `bujirun-api` (systemd), uvicorn on 127.0.0.1:8100 |
| code | `~/bujirun/backend` |
| data | `~/bujirun/data/accounts.db` |
| nginx | `/etc/nginx/conf.d/bujirun.conf` (+ certbot) |

## First time
```
scp -i <your-key>.pem -r backend <user>@<server>:~/bujirun/
ssh -i <your-key>.pem <user>@<server> 'CERTBOT_EMAIL=you@example.com bash ~/bujirun/backend/deploy/provision.sh'
```

## Updating
Copy the changed files the same way, then `sudo systemctl restart bujirun-api` and check
`curl -fsS http://127.0.0.1:8100/health`. API changes must be additive only (old app builds
stay installed).

## Secrets
`/etc/systemd/system/bujirun-api.service.d/secrets.conf` (root-only, never in git) holds
`TMAP_APP_KEY` (walking routes), `DURUNUBI_KEY` (data.go.kr, decoded form) and
`BUJIRUN_ADMIN_EMAIL` (the admin account; unset = no admin). Keep a local copy of each. When
editing the file, rewrite it whole with every entry - reading it needs sudo, and a failed read
once wiped a key.

## Official trails (Durunubi)
Copied into `~/bujirun/data/trails.db`, not fetched per request. Refresh occasionally:
```
cd ~/bujirun/backend && sudo sed -n "s/^Environment=DURUNUBI_KEY=//p" \
  /etc/systemd/system/bujirun-api.service.d/secrets.conf > /tmp/d && \
  DURUNUBI_KEY=$(cat /tmp/d) BJ_DATA_DIR=~/bujirun/data .venv/bin/python trails.py sync; rm /tmp/d
```
The app must keep crediting the source (한국관광공사 두루누비).

## Places (parks, lakes, beaches, rivers)
`~/bujirun/data/places.db`, refreshed monthly from two sources; each sync replaces only its own
rows (run `places.py` first, `osm.py` skips the parks it already has):
```
cd ~/bujirun/backend && sudo sed -n "s/^Environment=DURUNUBI_KEY=//p" \
  /etc/systemd/system/bujirun-api.service.d/secrets.conf > /tmp/d && \
  DURUNUBI_KEY=$(cat /tmp/d) BJ_DATA_DIR=~/bujirun/data .venv/bin/python places.py sync; rm /tmp/d
BJ_DATA_DIR=~/bujirun/data .venv/bin/python osm.py sync
```
The park data uses the same data.go.kr key as Durunubi. OSM comes from the public Overpass
servers (no key; they're often busy, the script retries). Credits shown by the app:
공공데이터포털 전국도시공원정보표준데이터, © OpenStreetMap 기여자.

## Forgotten password
The app emails a 6-digit code (POST /auth/reset/request, /auth/reset/confirm) once the server
has an SMTP account: run `backend/deploy/set_smtp.ps1 -Key <key.pem> -Server <user@host>`
yourself (asks for a Gmail address and its app password, hidden; writes the root-only
`smtp.conf` drop-in and restarts). Without it, the operator resets by hand:
```
cd ~/bujirun/backend && BJ_DATA_DIR=~/bujirun/data .venv/bin/python reset_password.py someone@example.com
```

## Shade map
`places.db` table `shade_blocks`: tree cover from ESA WorldCover 2021 (CC BY 4.0), built on the
dev PC with `tools/shade/build_shade.py` (needs numpy + tifffile; the six 3-degree tiles
N33/N36 x E123/E126/E129 from the `esa-worldcover` S3 bucket, ~175 MB) into a copy of
places.db, which is then uploaded whole. Rebuild only after re-syncing places (it keeps blocks
that overlap a place). The app credits "ESA WorldCover 2021".

## Height map
`places.db` table `elevation_blocks`: Copernicus DEM GLO-30, built on the dev PC with
`tools/elevation/build_elevation.py` (numpy + tifffile; ~22 one-degree tiles from the public
`copernicus-dem-30m` S3 bucket, ~570 MB) into the same copy of places.db as the shade map (it
keeps the blocks that have shade blocks). It's a surface model (trees and buildings included),
so backend/elevation.py median-filters the profile. Credit: Copernicus DEM (ESA).

## What lives where
The service needs only the server: code, data and secrets are all on it, and nothing on the
server reads from a dev PC. A dev PC is needed only to build the app and to rebuild data.

| | where the real copy is | rebuildable? |
|---|---|---|
| `accounts.db` (users, sessions), `notices.db` | server `~/bujirun/data` | **no** - see Backups |
| `cache.db`, `landmarks.db` | server | yes, they refill themselves |
| `places.db` (+ shade/elevation tables), `trails.db`, `waterside.db` | server | yes, slowly (syncs, ~750 MB of map downloads, the waterside prebuild); fetch the server copy instead |
| secrets (TMAP, Durunubi, admin email) | server `secrets.conf`, local copies in `~/.secrets/bujirun_*` | re-issue from the providers |
| app signing key | dev PC `~/.android/debug.keystore` | **no** - see below |

To work on data on a dev PC, fetch the server copy, build into it, upload it whole:
```
scp -i <key>.pem ec2-user@<server>:~/bujirun/data/places.db .
```

**App signing key.** There's no release key yet: every APK, testers' included, is signed with
the dev PC's debug key (`~/.android/debug.keystore`). Its SHA-1 is registered for Google
sign-in (the Android OAuth client in Google Cloud Console). Building on another computer with
a different key breaks Google sign-in, and phones refuse to install it over the existing app -
testers would have to uninstall, losing their workouts. So copy this file to every dev computer
and keep a second copy somewhere safe. A real release key (and its SHA-1 added to the OAuth
client) is a job for before Play Store release.

## Backups
Not automated yet: one user (the admin) as of 2026-10-17. Take a copy before anything risky
(account schema changes, server moves), and set up a daily server-side copy before testers
grow - user data is the one thing that can't be rebuilt. Safe copy while the service runs
(sqlite's online backup, read-only on the live file):
```
python3 -c "import sqlite3; [sqlite3.connect(f'file:/home/ec2-user/bujirun/data/{n}.db?mode=ro', uri=True).backup(sqlite3.connect(f'/tmp/bj_{n}.db')) for n in ('accounts', 'notices')]"
scp -i <key>.pem ec2-user@<server>:/tmp/bj_*.db .      # then rm /tmp/bj_*.db on the server
```
Restore: stop `bujirun-api`, put the copy in place as `~/bujirun/data/accounts.db`, start it.
Last copy: 2026-10-17 in `%USERPROFILE%\BujirunBackups\` on the dev PC.

## Moving to another dev computer
Nothing on the server changes. On the new computer:
1. Android Studio (SDK; the AGP/Gradle versions come from the repo), Git, Python 3.11+.
   `git clone https://github.com/hwseol/bujirun.git`.
2. Copy from the old computer:
   - `~/.android/debug.keystore` - **required** (see "App signing key").
   - `~/.ssh/englishbite-key.pem` - server access. SSH is allowed from the home IP only: away
     from home the server times out until the security group gets the new IP.
   - `~/.aws/config` and `credentials` - the AWS CLI, which is how the security group gets a
     new IP (the original access-key csv needn't come).
   - `~/.secrets/bujirun_*` (admin email, TMAP and Durunubi keys) and, for NLU data generation,
     `~/.secrets/gemini_free_api_key.txt`.
   - `tools/nlu/data/` - only to retrain the NLU model (it's Gemini output; regenerating costs
     calls).
   - `%USERPROFILE%\BujirunBackups\` - the latest user DB copy.
   - GitHub sign-in: cloning the public repo needs none, pushing does (Git Credential Manager;
     check with `git push --dry-run`).
   - Not needed: `backend/*.db` (small local test DBs; a local server creates them), `dist/`
     (old APKs), `local.properties` (Android Studio writes it), the big data DBs (on the server).
3. Local server for testing: `python -m venv .venv` in backend/, `.venv\Scripts\pip install -r
   requirements.txt`, then `.venv\Scripts\python -m uvicorn server:app --port 8100` and build
   with `-PbujirunApi=http://10.0.2.2:8100/` (emulator) - or test against the real server.
4. Data tools, when needed: a venv with `numpy tifffile` (shade, elevation; each holds ~150 MB
   at a time, fine on 16 GB) and CPU torch (NLU training); run with `python -P`.
5. Check: `.\gradlew.bat testDebugUnitTest assembleDebug`; install over the existing app on the
   phone (works only with the same signing key); log in, including with Google; the server
   health check answers; `aws sts get-caller-identity` works; and run the safe backup above once
   from the new computer - that's when operations have really moved.

On a slow laptop, check screens on the phone over wireless debugging instead of an emulator:
Developer options -> Wireless debugging -> pair with a code, then `adb pair <ip>:<port>` and
`adb connect <ip>:<port>`; `adb install`, `adb shell screencap` work as with USB. Course runs
can be tested on the phone with the admin start button's fake GPS (1x/5x/10x). If an emulator
is needed: Windows Hypervisor Platform on, a light AVD (x86_64, no Play image, low resolution,
`-memory 2048`), `.\gradlew.bat --stop` after building to free memory.

Retiring the old computer: once the new one has built, installed over the phone's app,
logged in with Google and taken a backup, the old one's copies of the key, `.pem`, `.aws` and
`.secrets` can go - but only after EnglishBite has moved too: it uses the same `.pem`, `.aws`,
`.secrets` and debug key (its scheduled backup pull, deploys and debug builds).
