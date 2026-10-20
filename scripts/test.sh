#!/usr/bin/env bash
# Runs the tests and prints a short summary: the totals, then each failing
# test with its first message line and where in our code it failed (or the
# compiler errors). Gradle's full output goes to build/test-output.log. The
# Spring tests start their own Postgres in Docker (TestcontainersConfiguration),
# so Docker must be running; .env isn't needed.
#
# Usage:
#   scripts/test.sh                                  # all tests
#   scripts/test.sh --tests "*GameSessionTest*"      # any extra Gradle args
#   TASK=build scripts/test.sh                       # compile + test (default: test)

set -uo pipefail
cd "$(dirname "$0")/.."

# Without Docker every Spring test fails with a long Testcontainers error, so
# say why up front. Plain unit tests still run, so this doesn't stop.
if ! docker info >/dev/null 2>&1; then
    echo "WARNING: Docker isn't running, so the Spring tests (which start Postgres in Docker) will fail. Start Docker Desktop."
fi

mkdir -p build
log=build/test-output.log

./gradlew "${TASK:-test}" "$@" -q >"$log" 2>&1
status=$?

# Compilation failed: there are no fresh test results, so show the errors.
# Gradle repeats the errors indented in its summary, so only the unindented
# copy is shown, with paths shortened to src/...
if grep -qE '\.java:[0-9]+: error:' "$log"; then
    echo "COMPILATION FAILED"
    grep -E --no-group-separator -A2 '^[^[:space:]].*\.java:[0-9]+: error:' "$log" \
        | sed -E 's#^.*[\\/](src[\\/])#\1#; s#\\#/#g' \
        | head -40
    exit 1
fi

python=$(command -v python || command -v python3)

"$python" - "$status" <<'PY'
import glob, html, re, sys

status = int(sys.argv[1])
tests = skipped = failed = 0
failures = []

for path in sorted(glob.glob("build/test-results/test/*.xml")):
    text = open(path, encoding="utf-8").read()
    head = re.search(r'tests="(\d+)" skipped="(\d+)" failures="(\d+)" errors="(\d+)"', text)
    if not head:
        continue
    tests += int(head[1])
    skipped += int(head[2])
    failed += int(head[3]) + int(head[4])

    for case in re.finditer(
        r'<testcase name="([^"]+)" classname="([^"]+)"[^>]*>\s*<(?:failure|error) message="([^"]*)"[^>]*>([^<]*)',
        text,
    ):
        name, classname, message, trace = case.groups()
        # AssertJ messages start with a near-empty line, so show the first
        # few non-empty lines.
        lines = [line.strip() for line in html.unescape(message).splitlines() if line.strip()]
        summary = " | ".join(lines[:3])
        if len(summary) > 300:
            summary = summary[:300] + "..."
        location = next(
            (line.strip() for line in html.unescape(trace).splitlines()
             if line.strip().startswith("at com.nickneim.")),
            "",
        )
        failures.append((classname.rsplit(".", 1)[-1], name, summary, location))

if failed == 0 and status == 0:
    print(f"PASSED: {tests} tests ({skipped} skipped)")
    sys.exit(0)

print(f"FAILED: {failed} of {tests} tests ({skipped} skipped)")
for classname, name, message, location in failures:
    print(f"- {classname}.{name}")
    if message:
        print(f"    {message}")
    if location:
        print(f"    {location}")

if not failures:
    print("No failing test found in the results; see build/test-output.log")
sys.exit(1)
PY
