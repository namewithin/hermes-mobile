#!/bin/bash
# Hermes Mobile — Mac-side installer.
# Creates ~/.hermes-mobile (token + logs), installs two launchd agents:
#   io.github.hermesmobile.proxy  — tailnet-facing proxy (port 9120) to Hermes Desktop's backend
#   io.github.hermesmobile.serve  — standalone `hermes serve` fallback (127.0.0.1:9119)
# Then prints the link/QR to connect the Android app.
set -euo pipefail

SRC="$(cd "$(dirname "$0")" && pwd)"
STATE="${HERMES_MOBILE_HOME:-$HOME/.hermes-mobile}"
AGENTS="$HOME/Library/LaunchAgents"
UIDN="$(id -u)"

[[ "$(uname)" == "Darwin" ]] || { echo "macOS only (launchd)."; exit 1; }
NODE="$(command -v node || true)"
[[ -n "$NODE" ]] || { echo "Node.js 18+ is required (brew install node)."; exit 1; }
HERMES="$(command -v hermes || true)"
[[ -n "$HERMES" ]] || echo "warning: 'hermes' not on PATH — the standalone fallback won't start (Desktop mode still works)."

mkdir -p "$STATE/bin" "$STATE/logs" "$AGENTS"
chmod 700 "$STATE"
cp "$SRC/proxy.mjs" "$SRC/run-serve.sh" "$STATE/bin/"
chmod +x "$STATE/bin/run-serve.sh"
[[ -n "$HERMES" ]] && dirname "$HERMES" > "$STATE/path"

if [[ ! -s "$STATE/token" ]]; then
  ( umask 077; LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 43 > "$STATE/token" )
  echo "Generated a new access token."
fi
chmod 600 "$STATE/token"

plist() { # label program-args...
  local label="$1"; shift
  {
    echo '<?xml version="1.0" encoding="UTF-8"?>'
    echo '<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">'
    echo '<plist version="1.0"><dict>'
    echo "  <key>Label</key><string>$label</string>"
    echo '  <key>ProgramArguments</key><array>'
    for a in "$@"; do echo "    <string>$a</string>"; done
    echo '  </array>'
    echo '  <key>RunAtLoad</key><true/><key>KeepAlive</key><true/><key>ThrottleInterval</key><integer>10</integer>'
    echo "  <key>StandardOutPath</key><string>$STATE/logs/${label##*.}.log</string>"
    echo "  <key>StandardErrorPath</key><string>$STATE/logs/${label##*.}.log</string>"
    echo '</dict></plist>'
  } > "$AGENTS/$label.plist"
  launchctl bootout "gui/$UIDN/$label" 2>/dev/null || true
  launchctl bootstrap "gui/$UIDN" "$AGENTS/$label.plist"
}

plist io.github.hermesmobile.proxy "$NODE" "$STATE/bin/proxy.mjs"
plist io.github.hermesmobile.serve /bin/bash "$STATE/bin/run-serve.sh"

sleep 3
TS_IP="$("$NODE" -e "const i=require('os').networkInterfaces();for(const l of Object.values(i)){if(l.some(a=>a.address.toLowerCase().startsWith('fd7a:115c:a1e0'))){const v4=l.find(a=>a.family==='IPv4');if(v4){console.log(v4.address);break}}}")"
HOST="${TS_IP:-<your-mac-tailscale-ip>}"
TOKEN="$(cat "$STATE/token")"
LINK="hermesmobile://connect?url=http://$HOST:9120&token=$TOKEN"

echo
echo "Hermes Mobile is running."
echo "  Server URL : http://$HOST:9120"
echo "  Token      : stored in $STATE/token"
echo "  Logs       : $STATE/logs/"
echo
echo "Connect the phone: scan this QR with the phone camera (or paste the link into the app's setup screen)."
if command -v qrencode >/dev/null; then
  qrencode -t ANSIUTF8 "$LINK"
else
  echo "  (install 'qrencode' via Homebrew to get a QR code here)"
  echo "  $LINK"
fi
[[ -z "$TS_IP" ]] && echo "Tailscale doesn't look connected on this Mac yet — start it, then re-run this script."
exit 0
