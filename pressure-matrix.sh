#!/usr/bin/env bash
# Claim 2 (GC-friendly under memory pressure): retain a large population of ConstructorOrMethod
# (like a big @Factory suite), then squeeze the heap while using the handles. Compares pre-PR
# (7.12) vs branch (7.13, interning on/off) across JDK 11 and 17.
set -euo pipefail
cd "$(dirname "$0")"

SDK=~/.sdkman/candidates/java
JDKS=(11.0.32-tem 17.0.20-tem)
WRAPPERS="${WRAPPERS:-500000}"
SECONDS_RUN="${SECONDS_RUN:-15}"

CP_BRANCH="target/classes:target/dependency/*"
CP_PREPR="target/classes:target/dep-7120/*"

run() { # $1=java $2=xmx $3=retainMb $4=cp  $5..=extra props
  local java="$1" xmx="$2" retain="$3" cp="$4"; shift 4
  local out
  out="$("$java" -Xmx"$xmx" \
      -Ddemo.wrappers="$WRAPPERS" -Ddemo.pressure.seconds="$SECONDS_RUN" -Ddemo.pressure.retainMb="$retain" \
      "$@" -cp "$cp" demo.PressureProbe 2>&1 || true)"
  local line
  line="$(printf '%s\n' "$out" | grep 'PRESSURE |' | head -1 || true)"
  if [[ -z "$line" ]]; then
    echo "OOM/died (no result)"
  else
    # strip the leading "PRESSURE | <label> | " so columns line up
    echo "${line#PRESSURE | * | }"
  fi
}

config() { # $1=title $2=xmx $3=retainMb
  local title="$1" xmx="$2" retain="$3"
  echo
  echo "############ ${title}: -Xmx${xmx}, retained=${retain}MB, wrappers=${WRAPPERS} ############"
  for v in "${JDKS[@]}"; do
    local JAVA="$SDK/$v/bin/java"
    echo "--- JDK ${v%-tem} ---"
    printf '  %-12s %s\n' "7.12 prePR" "$(run "$JAVA" "$xmx" "$retain" "$CP_PREPR")"
    printf '  %-12s %s\n' "7.13 OFF"   "$(run "$JAVA" "$xmx" "$retain" "$CP_BRANCH" -Dtestng.reflection.intern=false)"
    printf '  %-12s %s\n' "7.13 ON"    "$(run "$JAVA" "$xmx" "$retain" "$CP_BRANCH" -Dtestng.reflection.intern=true)"
  done
}

# HEADROOM: everyone survives — compare GC overhead and throughput (touches).
config "HEADROOM" "260m" "120"
# TIGHT: heap sized to fit the small (soft) live set but not the pinned one — survival test.
config "TIGHT" "170m" "120"
