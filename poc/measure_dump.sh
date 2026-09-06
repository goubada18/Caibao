#!/usr/bin/env bash
# P0-3 / P0-4 测量脚本：对指定前台窗口做 uiautomator dump 与 screencap 计时 + 节点统计
# 用法: bash measure_dump.sh <标签> [循环次数]
set -u
LABEL="${1:-unknown}"
N="${2:-10}"
TMP=/data/local/tmp/poc
OUT_DIR="$(cd "$(dirname "$0")" && pwd)/raw"
mkdir -p "$OUT_DIR"
adb shell "mkdir -p $TMP" >/dev/null 2>&1

focus() { adb shell dumpsys window 2>/dev/null | grep -m1 "mCurrentFocus" | sed -E 's/.*Window\{[^ ]+ u0 ([^}]*)\}.*/\1/'; }
pkg_of_focus() { focus | cut -d/ -f1; }

FOCUS_PKG="$(pkg_of_focus)"
if [ -z "$FOCUS_PKG" ]; then
  echo "[$LABEL] SKIP: 无法获取焦点窗口（可能锁屏）"
  exit 1
fi

dump_times=(); cap_times=()
for i in $(seq 1 "$N"); do
  s=$(date +%s%3N); adb shell "uiautomator dump $TMP/w.xml" >/dev/null 2>&1; e=$(date +%s%3N)
  dump_times+=($((e - s)))
  s=$(date +%s%3N); adb shell "screencap -p $TMP/s.png" >/dev/null 2>&1; e=$(date +%s%3N)
  cap_times+=($((e - s)))
done

adb shell "cat $TMP/w.xml" > "$OUT_DIR/${LABEL}.xml" 2>/dev/null
nodes=$(grep -o '<node' "$OUT_DIR/${LABEL}.xml" 2>/dev/null | wc -l | tr -d ' ')
clickable=$(grep -o 'clickable="true"' "$OUT_DIR/${LABEL}.xml" 2>/dev/null | wc -l | tr -d ' ')
withtext=$(grep -o 'text="[^"]\+"' "$OUT_DIR/${LABEL}.xml" 2>/dev/null | wc -l | tr -d ' ')
bytes=$(wc -c < "$OUT_DIR/${LABEL}.xml" 2>/dev/null | tr -d ' ')
pngbytes=$(adb shell "stat -c %s $TMP/s.png" 2>/dev/null | tr -d '\r')

pct() { # $1=array-name-as-string $2=pct
  local arr=($(echo "$1")) p=$2
  local sorted=($(printf '%s\n' "${arr[@]}" | sort -n))
  local idx=$(( ( ${#sorted[@]} * p ) / 100 ))
  [ $idx -ge ${#sorted[@]} ] && idx=$(( ${#sorted[@]} - 1 ))
  echo "${sorted[$idx]}"
}

printf '%-22s pkg=%-34s dump_ms[min=%s p50=%s p95=%s max=%s] cap_ms[p50=%s] nodes=%s clickable=%s text=%s xmlB=%s pngB=%s\n' \
  "$LABEL" "$FOCUS_PKG" \
  "$(pct "${dump_times[*]}" 0)" "$(pct "${dump_times[*]}" 50)" "$(pct "${dump_times[*]}" 95)" "$(pct "${dump_times[*]}" 100)" \
  "$(pct "${cap_times[*]}" 50)" \
  "$nodes" "$clickable" "$withtext" "$bytes" "$pngbytes"
