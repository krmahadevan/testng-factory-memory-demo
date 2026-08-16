#!/usr/bin/env bash
# juherr's #2 (retained memory AFTER the run): run the factory suite to completion, drop every
# reference to it, force GC, then count the reflective handles still alive. Compares pre-PR (7.12)
# vs the branch (7.13, interning off/on) across JDK 11 and 17.
#
# The number that matters is the ON-minus-OFF delta in live Methods: that is the cache's persistent
# cost (one handle per DISTINCT member, independent of how many instances the factory made). The
# large shared baseline is the JVM's own reflection cache and is present in every column.
set -euo pipefail
cd "$(dirname "$0")"

INSTANCES="${1:-2000}"
SDK=~/.sdkman/candidates/java
JDKS=(11.0.32-tem 17.0.20-tem)

CP_BRANCH="target/classes:target/dependency/*"
CP_PREPR="target/classes:target/dep-7120/*"
COMMON=(-Xmx2g -Ddemo.instances="${INSTANCES}")

run() { # $1=java  $2=cp  $3..=extra props ; prints "<methods>|<constructors>|<wrappers>|<heapMB>"
  local java="$1" cp="$2"; shift 2
  local out
  out="$("$java" "${COMMON[@]}" "$@" -cp "$cp" demo.RetentionProbe 2>/dev/null | grep 'RETENTION' || true)"
  local m c w h
  m="$(sed -n 's/.*liveMethods=\([0-9]*\).*/\1/p' <<<"$out")"
  c="$(sed -n 's/.*liveConstructors=\([0-9]*\).*/\1/p' <<<"$out")"
  w="$(sed -n 's/.*liveWrappers=\([0-9]*\).*/\1/p' <<<"$out")"
  h="$(sed -n 's/.*usedHeapMB=\([0-9.]*\).*/\1/p' <<<"$out")"
  printf '%s|%s|%s|%s' "${m:-?}" "${c:-?}" "${w:-?}" "${h:-?}"
}

fmt() { awk -F'|' '{printf "methods=%-4s ctors=%-4s wrappers=%-3s heap=%sMB", $1, $2, $3, $4}' <<<"$1"; }

printf '\n== Post-run retention @ %s instances (live handles after the suite is collected) ==\n\n' "$INSTANCES"
for v in "${JDKS[@]}"; do
  JAVA="$SDK/$v/bin/java"
  if [[ ! -x "$JAVA" ]]; then printf '%-14s (not installed)\n' "${v%-tem}"; continue; fi
  echo "--- JDK ${v%-tem} ---"
  printf '  %-12s %s\n' "7.12 prePR" "$(fmt "$(run "$JAVA" "$CP_PREPR")")"
  printf '  %-12s %s\n' "7.13 OFF"   "$(fmt "$(run "$JAVA" "$CP_BRANCH" -Dtestng.reflection.intern=false)")"
  printf '  %-12s %s\n' "7.13 ON"    "$(fmt "$(run "$JAVA" "$CP_BRANCH" -Dtestng.reflection.intern=true)")"
done
echo
echo "Read the ON-minus-OFF gap in live Methods: that is the cache persistent cost, about one"
echo "handle per distinct member. Wrappers collapse to 0 in every column once the suite is collected."
