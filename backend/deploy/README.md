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
