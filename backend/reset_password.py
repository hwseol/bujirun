"""Forgotten password (there's no email sender): run on the server, send the printed temporary
password to the user, who can log in with it.

    cd ~/bujirun/backend && .venv/bin/python reset_password.py someone@example.com
"""

import secrets
import sys

import accounts

if len(sys.argv) != 2:
    sys.exit("usage: reset_password.py <email>")
temp = secrets.token_urlsafe(8)
if not accounts.admin_reset_password(sys.argv[1], temp):
    sys.exit(f"no account for {sys.argv[1]}")
print(f"temporary password for {sys.argv[1]}: {temp}")
