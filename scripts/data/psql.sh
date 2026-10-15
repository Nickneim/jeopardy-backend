#!/usr/bin/env bash
# Runs psql against the database in .env (DATABASE_URL, DATABASE_USERNAME,
# DATABASE_PASSWORD). The session is read-only unless WRITE=1 is set, so a
# script can only change data when you ask for it.
#
# Usage:
#   scripts/data/psql.sh -f scripts/data/clean-clues.sql                  # dry run
#   WRITE=1 scripts/data/psql.sh -v commit=1 -f scripts/data/clean-clues.sql
#   ENV_FILE=../../.env scripts/data/psql.sh -c "select count(*) from clue"

set -euo pipefail
cd "$(dirname "$0")/../.."

# The nearest .env in the project root or a folder above it, so it's also
# found from a git worktree (.claude/worktrees/<name>) of the project.
env_file="${ENV_FILE:-}"
if [ -z "$env_file" ]; then
    dir="$(pwd)"
    while [ "$dir" != "/" ] && [ ! -f "$dir/.env" ]; do
        parent="$(dirname "$dir")"
        [ "$parent" = "$dir" ] && break
        dir="$parent"
    done
    env_file="$dir/.env"
fi
if [ ! -f "$env_file" ]; then
    echo "No .env found in the project or a folder above it; set ENV_FILE to the .env to use" >&2
    exit 1
fi

value() {
    grep -E "^$1=" "$env_file" | head -1 | cut -d= -f2- | tr -d '\r'
}

url="$(value DATABASE_URL)"
# jdbc:postgresql://host:port/database
if [[ ! "$url" =~ ^jdbc:postgresql://([^:/]+)(:([0-9]+))?/([^?]+) ]]; then
    echo "Can't read DATABASE_URL: $url" >&2
    exit 1
fi

export PGHOST="${BASH_REMATCH[1]}"
export PGPORT="${BASH_REMATCH[3]:-5432}"
export PGDATABASE="${BASH_REMATCH[4]}"
export PGUSER="$(value DATABASE_USERNAME)"
export PGPASSWORD="$(value DATABASE_PASSWORD)"

if [ "${WRITE:-}" != "1" ]; then
    export PGOPTIONS="-c default_transaction_read_only=on"
fi

exec psql -X -P pager=off -v ON_ERROR_STOP=1 "$@"
