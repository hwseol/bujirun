#!/bin/bash
# One-time setup of the Bujirun API on the (shared) EnglishBite EC2 server. Idempotent.
# Run on the server after copying backend/ to ~/bujirun/backend:
#     bash ~/bujirun/backend/deploy/provision.sh
#
#   API: 127.0.0.1:8100 -> https://bujirun.184-193-203-68.sslip.io (nginx + Let's Encrypt)
#   data: ~/bujirun/data/accounts.db  (nothing shared with EnglishBite)
#
# Reuses the http-level `auth` limit_req zone EnglishBite already defines
# (/etc/nginx/conf.d/00-englishbite-http.conf); touches no EnglishBite file.
set -eu
HOST=bujirun.184-193-203-68.sslip.io
APP=~/bujirun/backend
DATA=~/bujirun/data
mkdir -p "$DATA"

cd "$APP"
[ -d .venv ] || python3 -m venv .venv
.venv/bin/pip install -q -r requirements.txt

sudo tee /etc/systemd/system/bujirun-api.service >/dev/null <<EOF
[Unit]
Description=Bujirun API
After=network.target

[Service]
User=$USER
WorkingDirectory=$APP
Environment=BJ_DATA_DIR=$DATA
ExecStart=$APP/.venv/bin/python -m uvicorn server:app --host 127.0.0.1 --port 8100
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF
sudo systemctl daemon-reload
sudo systemctl enable --now bujirun-api
sudo systemctl restart bujirun-api

if [ ! -f /etc/nginx/conf.d/bujirun.conf ]; then
  sudo tee /etc/nginx/conf.d/bujirun.conf >/dev/null <<EOF
server {
    listen 80;
    server_name $HOST;
    client_max_body_size 1m;
    location /auth/ {
        limit_req zone=auth burst=10 nodelay;
        proxy_pass http://127.0.0.1:8100;
        proxy_set_header Host \$host;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
    }
    location / {
        proxy_pass http://127.0.0.1:8100;
        proxy_set_header Host \$host;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
    }
}
EOF
  sudo nginx -t
  sudo systemctl reload nginx
  sudo certbot --nginx -d "$HOST" --non-interactive --agree-tos -m admin@example.com --redirect
fi

sleep 2
systemctl is-active --quiet bujirun-api || { sudo journalctl -u bujirun-api -n 30 --no-pager; exit 1; }
curl -fsS http://127.0.0.1:8100/health && echo
curl -fsS "https://$HOST/health" && echo
