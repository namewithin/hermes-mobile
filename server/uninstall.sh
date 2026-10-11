#!/bin/bash
# Removes the Hermes Mobile launchd agents. Keeps ~/.hermes-mobile (token) unless --purge.
UIDN="$(id -u)"
for l in io.github.hermesmobile.proxy io.github.hermesmobile.serve; do
  launchctl bootout "gui/$UIDN/$l" 2>/dev/null || true
  rm -f "$HOME/Library/LaunchAgents/$l.plist"
done
[[ "${1:-}" == "--purge" ]] && rm -rf "${HERMES_MOBILE_HOME:-$HOME/.hermes-mobile}"
echo "Hermes Mobile agents removed."
