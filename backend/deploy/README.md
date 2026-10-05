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

## Forgotten password
```
cd ~/bujirun/backend && BJ_DATA_DIR=~/bujirun/data .venv/bin/python reset_password.py someone@example.com
```
