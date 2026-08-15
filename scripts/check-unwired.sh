#!/usr/bin/env bash
#
# Fails when a NEW class or object is added that nothing outside its own file
# references. See docs/UNWIRED.md for what this found the first time it ran.
#
# Why a baseline rather than a hard zero: the repo already carries ~174 such
# classes, and blocking every commit until they are all resolved would just get
# the check disabled. The baseline freezes today's debt and stops it growing;
# entries are removed as each is wired or deleted.
#
# Known limits, stated so nobody re-learns them:
#
#   - Android components declared in AndroidManifest.xml (receivers, services,
#     activities, tile services) are invisible to a source scan. They are
#     allow-listed by reading the manifest, not by hand.
#   - A data class returned only by its own file's functions is reported, and
#     that is usually noise if the enclosing object is alive. Judge by the
#     enclosing object.
#   - Reflection and DI are invisible here. This repo uses neither for these
#     types, which is the only reason the check is worth running.
#
# Usage:
#   scripts/check-unwired.sh            # verify against the baseline
#   scripts/check-unwired.sh --update   # rewrite the baseline after wiring work
set -uo pipefail

cd "$(dirname "$0")/.."

BASELINE="scripts/unwired-baseline.txt"
MANIFEST="apps/android/src/main/AndroidManifest.xml"
TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT

sources=$(find shared apps platform-impl \
    \( -path '*/src/*Main/*' -o -path '*/src/main/*' \) -name '*.kt' 2>/dev/null \
    | grep -v '/build/' | sort)

for f in $sources; do
    grep -oE '^(object|class|data class|sealed class|interface|enum class) [A-Z][A-Za-z0-9_]*' "$f" 2>/dev/null \
        | awk '{print $NF}' | sort -u | while read -r cls; do
        [ -z "$cls" ] && continue

        # Declared in the manifest? Then it has a caller the compiler cannot see.
        if [ -f "$MANIFEST" ] && grep -q "\.$cls\"" "$MANIFEST" 2>/dev/null; then
            continue
        fi

        n=$(grep -rlw "$cls" --include='*.kt' shared apps platform-impl 2>/dev/null \
            | grep -v '/build/' | wc -l)
        if [ "$n" -le 1 ]; then
            echo "${f}|${cls}"
        fi
    done
done | sort > "$TMP"

if [ "${1:-}" = "--update" ]; then
    cp "$TMP" "$BASELINE"
    echo "Baseline updated: $(wc -l < "$BASELINE") entries."
    exit 0
fi

if [ ! -f "$BASELINE" ]; then
    echo "No baseline at $BASELINE — run: scripts/check-unwired.sh --update"
    exit 1
fi

# Only NEW entries fail. Entries that disappear are progress, and are reported
# so the baseline can be trimmed, but they never fail the build.
new=$(comm -23 "$TMP" "$BASELINE")
gone=$(comm -13 "$TMP" "$BASELINE")

if [ -n "$gone" ]; then
    echo "Wired or removed since the baseline ($(echo "$gone" | wc -l)):"
    echo "$gone" | sed 's/^/  - /'
    echo "  → run scripts/check-unwired.sh --update to trim the baseline."
    echo
fi

if [ -n "$new" ]; then
    echo "FAIL: new class(es) that nothing outside their own file references:"
    echo "$new" | sed 's/^/  + /'
    echo
    echo "Either wire it up, or do not add it. If it is reached in a way this"
    echo "check cannot see, say how in the PR and update the baseline."
    exit 1
fi

echo "OK — no newly unwired classes (baseline: $(wc -l < "$BASELINE") known)."
