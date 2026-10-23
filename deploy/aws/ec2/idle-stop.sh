#!/bin/bash
# Shuts the instance down (which stops it, as EC2 does for a shutdown from
# inside) once no game session has existed for IDLE_MINUTES. Run every minute
# by jeopardy-idle.timer. A backend that doesn't answer counts as idle too, so
# a broken start doesn't keep the instance running.
set -uo pipefail

idle_minutes=20
# Time after boot to start the backend and create the first session.
grace_minutes=15
state=/run/jeopardy-idle-since

uptime_seconds=$(cut -d. -f1 /proc/uptime)
if (( uptime_seconds < grace_minutes * 60 )); then
  rm -f "$state"
  exit 0
fi

# Unfinished sessions (lobbies and games in progress), from the lobby list.
sessions=$(curl -fsS --max-time 5 'http://localhost:8080/api/game-sessions?size=1' \
  | grep -o '"totalElements":[0-9]*' | cut -d: -f2) || sessions=""
if [[ -n "$sessions" && "$sessions" != 0 ]]; then
  rm -f "$state"
  exit 0
fi

now=$(date +%s)
[[ -f "$state" ]] || echo "$now" > "$state"
idle_since=$(cat "$state")
if (( now - idle_since >= idle_minutes * 60 )); then
  logger -t jeopardy-idle "No game sessions for ${idle_minutes} minutes, shutting down"
  systemctl poweroff
fi
