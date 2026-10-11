#!/bin/bash
# Standalone Hermes backend used when Hermes Desktop isn't running.
# Loopback only; the mobile proxy exposes it on the tailnet.
STATE_DIR="${HERMES_MOBILE_HOME:-$HOME/.hermes-mobile}"
export PATH="$(cat "$STATE_DIR/path" 2>/dev/null):$HOME/.local/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin"
export HERMES_DASHBOARD_SESSION_TOKEN="$(cat "$STATE_DIR/token")"
exec hermes serve --host 127.0.0.1 --port "${HERMES_MOBILE_SERVE_PORT:-9119}" --skip-build
