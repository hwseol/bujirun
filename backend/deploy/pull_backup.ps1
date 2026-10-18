# Copies the server's Bujirun backups to this computer (run daily by the scheduled task
# BujirunBackupPull, see backend/deploy/README.md "Backups"). Keeps KeepDays daily folders; the
# big built databases are fetched only when the server's copy is newer.
#
#   powershell -ExecutionPolicy Bypass -File pull_backup.ps1 -Key <key.pem> -Server <user@host>
param(
    [Parameter(Mandatory = $true)][string]$Key,
    [Parameter(Mandatory = $true)][string]$Server,
    [string]$Dest = "$env:USERPROFILE\BujirunBackups",
    [int]$KeepDays = 30
)
$ErrorActionPreference = "Stop"
$log = Join-Path $Dest "pull.log"
New-Item -ItemType Directory -Force (Join-Path $Dest "built") | Out-Null
function Say($m) { "$(Get-Date -Format s) $m" | Tee-Object -FilePath $log -Append }

try {
    # The newest daily folder, and the built files with their times.
    $listing = ssh -i $Key -o BatchMode=yes -o ConnectTimeout=20 $Server `
        "cd ~/bujirun/backups && ls -1d 20??-??-?? | tail -1 && (cd built && stat -c '%Y %n' *.gz) 2>/dev/null; exit 0"
    if ($LASTEXITCODE -ne 0 -or -not $listing) { throw "ssh failed ($LASTEXITCODE)" }
    $lines = @($listing)
    $day = $lines[0].Trim()
    $local = Join-Path $Dest $day
    if (-not (Test-Path (Join-Path $local "accounts.db"))) {
        New-Item -ItemType Directory -Force $local | Out-Null
        scp -q -i $Key -o BatchMode=yes "${Server}:~/bujirun/backups/$day/*.db" "$local\"
        if ($LASTEXITCODE -ne 0) { throw "scp of $day failed" }
        Say "pulled $day"
    }
    foreach ($l in $lines | Select-Object -Skip 1) {
        $t, $name = $l.Trim() -split " ", 2
        $file = Join-Path $Dest "built\$name"
        $remoteTime = [DateTimeOffset]::FromUnixTimeSeconds([int64]$t).UtcDateTime
        if ((Test-Path $file) -and ((Get-Item $file).LastWriteTimeUtc -ge $remoteTime)) { continue }
        scp -q -i $Key -o BatchMode=yes "${Server}:~/bujirun/backups/built/$name" "$file"
        if ($LASTEXITCODE -ne 0) { throw "scp of $name failed" }
        (Get-Item $file).LastWriteTimeUtc = $remoteTime
        Say "pulled built\$name"
    }
    $days = Get-ChildItem $Dest -Directory | Where-Object Name -match '^\d{4}-\d{2}-\d{2}$' | Sort-Object Name
    $days | Select-Object -SkipLast $KeepDays | ForEach-Object { Remove-Item -Recurse -Force $_.FullName; Say "removed $($_.Name)" }
} catch {
    Say "FAILED: $_"
    exit 1
}
