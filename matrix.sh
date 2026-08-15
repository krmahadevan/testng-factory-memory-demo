#!/usr/bin/env bash
# Compares pre-PR (testng 7.12.0) vs the branch (7.13.0-SNAPSHOT, feature off/on)
# across every installed JDK. Reports "used heap after GC" and the live
# java.lang.reflect.Method instance count for each cell.
set -euo pipefail
cd "$(dirname "$0")"

INSTANCES="${1:-50000}"
SDK=~/.sdkman/candidates/java
JDKS=(11.0.32-tem 17.0.20-tem)

CP_BRANCH="target/classes:target/dependency/*"
CP_PREPR="target/classes:target/dep-7120/*"
COMMON=(-Xmx2g -Ddemo.mode=snapshot -Ddemo.instances="${INSTANCES}" -Ddemo.dwell.seconds=0)

run() { # $1=java  $2=cp  $3..=extra props ; prints "<MB>|<methodCount>"
  local java="$1" cp="$2"; shift 2
  local out
  out="$("$java" "${COMMON[@]}" "$@" -cp "$cp" demo.Runner 2>/dev/null || true)"
  local mb methods
  mb="$(printf '%s\n' "$out" | sed -n 's/.*used heap after GC.*(\([0-9.]*\) MB).*/\1/p' | head -1)"
  methods="$(printf '%s\n' "$out" | awk '/java\.lang\.reflect\.Method \(/{print $2; exit}')"
  printf '%s|%s' "${mb:-?}" "${methods:-?}"
}

printf '\n== Factory memory matrix @ %s instances (used heap MB after GC / live java.lang.reflect.Method count) ==\n\n' "$INSTANCES"
printf '%-14s | %-18s | %-18s | %-18s\n' "JDK" "7.12.0 (pre-PR)" "7.13 feature OFF" "7.13 feature ON"
printf -- '---------------+--------------------+--------------------+--------------------\n'
for v in "${JDKS[@]}"; do
  JAVA="$SDK/$v/bin/java"
  if [[ ! -x "$JAVA" ]]; then printf '%-14s | (not installed)\n' "$v"; continue; fi
  pre="$(run "$JAVA" "$CP_PREPR")"
  off="$(run "$JAVA" "$CP_BRANCH" -Dtestng.reflection.intern=false)"
  on="$(run "$JAVA" "$CP_BRANCH" -Dtestng.reflection.intern=true)"
  printf '%-14s | %-18s | %-18s | %-18s\n' \
    "${v%-tem}" \
    "$(echo "$pre" | awk -F'|' '{printf "%s MB / %s", $1, $2}')" \
    "$(echo "$off" | awk -F'|' '{printf "%s MB / %s", $1, $2}')" \
    "$(echo "$on"  | awk -F'|' '{printf "%s MB / %s", $1, $2}')"
done
printf '\nNote: MB = live set after 2x System.gc(); Method count = interned handles still alive.\n'
