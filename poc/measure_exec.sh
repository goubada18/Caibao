#!/usr/bin/env bash
# P0 补充：执行层与感知链路耗时测量
# 1) input tap 端到端（肉包当前执行方式）
# 2) input text 端到端
# 3) screencap + base64 编码（肉包当前感知方式：截图→编码→上传）
# 4) 纯 adb shell 往返开销（作为基线，用于扣除传输成本）
set -u
N="${1:-20}"
TMP=/data/local/tmp/poc
adb shell "mkdir -p $TMP" >/dev/null 2>&1

run() { # $1=label $2=command $3=次数
  local label="$1" cmd="$2" n="${3:-$N}" i s e t
  local arr=()
  for ((i=0; i<n; i++)); do
    s=$(date +%s%3N); adb shell "$cmd" >/dev/null 2>&1; e=$(date +%s%3N)
    arr+=($((e - s)))
  done
  local sorted=($(printf '%s\n' "${arr[@]}" | sort -n))
  local len=${#sorted[@]}
  local p50=${sorted[$((len*50/100))]}
  local p95=${sorted[$(( (len*95/100 < len) ? len*95/100 : len-1 ))]}
  printf '%-28s min=%4dms  p50=%4dms  p95=%4dms  max=%4dms  (n=%d)\n' \
    "$label" "${sorted[0]}" "$p50" "$p95" "${sorted[$((len-1))]}" "$len"
}

# 先回到安全界面（计算器），避免误触
adb shell am force-stop com.miui.calculator >/dev/null 2>&1
adb shell monkey -p com.miui.calculator -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 3
echo "前台: $(adb shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus | sed -E 's/.*u0 ([^}]*)\}.*/\1/')"
echo

echo "── 基线 ──"
run "adb shell true(纯往返)"   "true"
echo
echo "── 执行层（肉包现状：input 命令）──"
run "input tap 540 400"        "input tap 540 400"
run "input text hello"         "input text hello"
run "input swipe(300ms)"       "input swipe 540 1500 540 800 300"
echo
echo "── 感知层（肉包现状：截图→编码）──"
run "screencap -p 写文件"      "screencap -p $TMP/s.png"
run "base64 编码截图"          "base64 $TMP/s.png > $TMP/s.b64"
echo
echo "── 参考：uiautomator dump（已在 POC-报告 中详测）──"
run "uiautomator dump"         "uiautomator dump $TMP/w.xml" 5
echo
echo "截图体积: $(adb shell "stat -c %s $TMP/s.png" 2>/dev/null | tr -d '\r') B；base64 后: $(adb shell "stat -c %s $TMP/s.b64" 2>/dev/null | tr -d '\r') B"
