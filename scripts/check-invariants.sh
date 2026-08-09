#!/usr/bin/env bash
# check-invariants.sh — enforces the AGENTS.md architecture invariants, statically.
#
# Run locally:  bash scripts/check-invariants.sh
# Run in CI:    .github/workflows/invariants.yml (static-invariants job)
#
# Checks:
#   1. No platform imports in shared/*/src/commonMain (invariant 5: shared code is platform-free).
#   2. Expect/actual sanity per shared module — any commonMain `expect` must have at least
#      one `actual` somewhere in the module (catches the "no actuals at all" regression;
#      per-target balance is enforced authoritatively by the per-target compiles in the
#      build-gates CI job, not here).
#   3. No `kspCommonMainMetadata` in Gradle build files (known Room KMP footgun — it causes
#      [MissingType] errors in Room KMP; see shared/database/build.gradle.kts).
#
# Exit code 0 = clean, 1 = violation. Prints the violating lines.

set -u

violations=0

report() {
  echo "INVARIANT VIOLATION: $1"
  violations=$((violations + 1))
}

echo "== Check 1: no platform imports in shared/*/src/commonMain =="
# android.*, java.*/javax.*/sun.* (covers java.awt), kotlinx.cinterop.*, platform.* (cinterop)
hits=$(grep -rnE '^import (android\.|java\.|javax\.|sun\.|kotlinx\.cinterop\.|platform\.)' \
  shared/*/src/commonMain --include='*.kt' 2>/dev/null || true)
if [ -n "$hits" ]; then
  report "shared code is platform-free (AGENTS.md invariant 5) — platform import(s) in commonMain:"
  echo "$hits"
else
  echo "OK — no platform imports in shared commonMain"
fi

echo "== Check 2: expect/actual sanity per shared module =="
for mod in shared/*; do
  [ -d "$mod/src" ] || continue
  expects=$(grep -rhE '^[[:space:]]*expect ' "$mod/src/commonMain" --include='*.kt' 2>/dev/null | wc -l)
  actuals=$(grep -rhE '^[[:space:]]*actual ' "$mod/src" --include='*.kt' 2>/dev/null | wc -l)
  if [ "$expects" -gt 0 ] && [ "$actuals" -lt "$expects" ]; then
    report "$mod declares $expects expect(s) in commonMain but only $actuals actual(s) in the module — every expect needs an actual in every compiled target source set (AGENTS.md R4)"
  else
    echo "OK — $mod: expects=$expects actuals=$actuals"
  fi
done

echo "== Check 3: no kspCommonMainMetadata in Gradle build files =="
ksp_hits=$(grep -rnE 'add\("kspCommonMainMetadata"|kspCommonMainMetadata\(' \
  --include='*.gradle.kts' --include='*.gradle' . 2>/dev/null | grep -v '^\./\.git/' || true)
if [ -n "$ksp_hits" ]; then
  report "kspCommonMainMetadata causes [MissingType] errors in Room KMP — remove it (see shared/database/build.gradle.kts):"
  echo "$ksp_hits"
else
  echo "OK — no kspCommonMainMetadata in build files"
fi

if [ "$violations" -gt 0 ]; then
  echo
  echo "FAILED: $violations invariant violation(s)."
  exit 1
fi

echo
echo "All invariant checks passed."
