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
ssh -i <your-key>.pem <user>@<server> 'bash ~/bujirun/backend/deploy/provision.sh'
```

## Updating
Copy the changed files the same way, then `sudo systemctl restart bujirun-api` and check
`curl -fsS http://127.0.0.1:8100/health`. API changes must be additive only (old app builds
stay installed).

## Secrets
`/etc/systemd/system/bujirun-api.service.d/secrets.conf` (root-only, never in git) holds
`TMAP_APP_KEY` (walking routes) and `DURUNUBI_KEY` (data.go.kr, decoded form). Local copies:
`~/.secrets/bujirun_tmap_appkey.txt`, `~/.secrets/bujirun_durunubi_key.txt`. When editing it,
rewrite the whole file with every key - reading it needs sudo, and a failed read once wiped
the TMAP key.

## Official trails (Durunubi)
Copied into `~/bujirun/data/trails.db`, not fetched per request. Refresh occasionally:
```
cd ~/bujirun/backend && sudo sed -n "s/^Environment=DURUNUBI_KEY=//p" \
  /etc/systemd/system/bujirun-api.service.d/secrets.conf > /tmp/d && \
  DURUNUBI_KEY=$(cat /tmp/d) BJ_DATA_DIR=~/bujirun/data .venv/bin/python trails.py sync; rm /tmp/d
```
The app must keep crediting the source (한국관광공사 두루누비).

## Forgotten password
```
cd ~/bujirun/backend && BJ_DATA_DIR=~/bujirun/data .venv/bin/python reset_password.py someone@example.com
```
