#!/usr/bin/env bash
# Full device regression: every suite in sequence, each from a fresh app reset, then one summary.
# 194 checks: library 41, theme 102, media 31, input 11, integration 9.
#
#   ./gradlew :app:installDebug :emulator-stub:installRetroarchDebug
#   tools/device-tests/run-all.sh                    # all suites (same as smoke.sh --all)
#   tools/device-tests/run-all.sh theme input        # only these, in this order
#
# Exit 0 when nothing failed (skipped checks don't fail the run); 1 when a check failed or a suite
# couldn't run (no device, app not installed: listed as "aborted"); 2 for an unknown suite name.
# Each suite cleans up after itself; this script also resets `dumpsys battery` at the end, even
# after Ctrl-C.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
ALL=(library theme media input integration)
TOTAL_CHECKS=194
if [ $# -gt 0 ]; then SUITES=("$@"); else SUITES=("${ALL[@]}"); fi
for s in "${SUITES[@]}"; do
  [ -x "$HERE/test-$s.sh" ] || { echo "unknown suite '$s' (${ALL[*]})"; exit 2; }
done

XROSS_RESULTS=$(mktemp "${TMPDIR:-/tmp}/xross-results.XXXXXX")
export XROSS_RESULTS
# shellcheck disable=SC2329 # invoked by the EXIT trap
finish() {
  adb shell dumpsys battery reset >/dev/null 2>&1
  rm -f "$XROSS_RESULTS"
}
trap finish EXIT
trap 'echo; echo "Interrupted"; exit 130' INT TERM

SECONDS=0
status=0
for s in "${SUITES[@]}"; do
  echo
  echo "######## $s"
  "$HERE/test-$s.sh"
  rc=$?
  [ "$rc" -eq 130 ] && exit 130
  [ "$rc" -ne 0 ] && status=1
  grep -q "^$s " "$XROSS_RESULTS" || { echo "$s 0 0 0 0 aborted" >> "$XROSS_RESULTS"; status=1; }
done

echo
echo "######## Summary ($((SECONDS / 60))m $((SECONDS % 60))s)"
printf '  %-12s %6s %6s %7s %7s\n' suite passed failed skipped checks
pass=0 fail=0 skipped=0 checks=0
while read -r name p f k n note; do
  printf '  %-12s %6d %6d %7d %7d %s\n' "$name" "$p" "$f" "$k" "$n" "${note:-}"
  pass=$((pass + p)) fail=$((fail + f)) skipped=$((skipped + k)) checks=$((checks + n))
done < "$XROSS_RESULTS"
printf '  %-12s %6d %6d %7d %7d\n' total "$pass" "$fail" "$skipped" "$checks"
if [ "${#SUITES[@]}" -eq "${#ALL[@]}" ] && [ "$checks" -ne "$TOTAL_CHECKS" ]; then
  echo "  note: the suites declare $checks checks, expected $TOTAL_CHECKS"
fi
echo
echo "Passed: $pass  Failed: $fail  Skipped: $skipped"
exit "$status"
