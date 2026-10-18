# Turns on password-reset emails: stores a Gmail address and its app password on the server
# (/etc/systemd/system/bujirun-api.service.d/smtp.conf, root-only) and restarts the API.
# Run it yourself - the password is typed here, hidden, and goes to the server over ssh stdin
# (never on a command line, never in this repo).
#
#   powershell -ExecutionPolicy Bypass -File backend\deploy\set_smtp.ps1 -Key <key.pem> -Server <user@host>
#
# The app password: Google account -> Security -> 2-Step Verification on -> App passwords.
param(
    [Parameter(Mandatory = $true)][string]$Key,
    [Parameter(Mandatory = $true)][string]$Server
)
$ErrorActionPreference = "Stop"

$address = Read-Host "Gmail address that sends the codes"
$secure = Read-Host "App password (16 letters; hidden)" -AsSecureString
$password = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
    [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)) -replace "\s", ""
if ($password.Length -ne 16) { throw "An app password has 16 letters (spaces don't count)." }

$conf = @"
[Service]
Environment=BUJIRUN_SMTP_HOST=smtp.gmail.com
Environment=BUJIRUN_SMTP_PORT=587
Environment=BUJIRUN_SMTP_USER=$address
Environment=BUJIRUN_SMTP_PASSWORD=$password
Environment="BUJIRUN_SMTP_FROM=Buji Run <$address>"
"@

# Through stdin; carriage returns stripped (systemd would keep them in the values).
$remote = "tr -d '\r' | sudo tee /etc/systemd/system/bujirun-api.service.d/smtp.conf >/dev/null && " +
          "sudo chmod 600 /etc/systemd/system/bujirun-api.service.d/smtp.conf && " +
          "sudo systemctl daemon-reload && sudo systemctl restart bujirun-api && sleep 3 && " +
          "curl -fsS http://127.0.0.1:8100/auth/reset/available"
$conf | ssh -i $Key $Server $remote
$password = $null
Write-Host ""
Write-Host "Done if it says {""email"":true}. Test: the app's 'Forgot your password?' with your own email."
