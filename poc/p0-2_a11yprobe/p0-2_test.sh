#!/usr/bin/env bash
# P0-2 存活测试自动化脚本（需设备在线 + USB 调试授权）
# 用法:
#   bash p0-2_test.sh install     # 阶段1: 安装 + 追加启用 + 验证绑定
#   bash p0-2_test.sh probe       # 阶段2: 事件流/节点树探测(计算器+微信)
#   bash p0-2_test.sh kill        # 阶段3: 杀进程自恢复计时
#   bash p0-2_test.sh forcestop   # 阶段4: force-stop 后系统是否重绑(数据点)
#   bash p0-2_test.sh soak [分钟]  # 阶段5: 锁屏浸泡(默认2分钟)
#   bash p0-2_test.sh reboot      # 阶段6: 重启后是否自动恢复(需等待开机)
PKG=com.caibao.a11yprobe
COMP="$PKG/com.caibao.a11yprobe.ProbeService"
APK="$(dirname "$0")/build/a11yprobe.apk"
KEY="A11yProbe"

die() { echo "FAIL: $*"; exit 1; }
need_dev() { adb get-state >/dev/null 2>&1 || die "设备不在线，请插线并授权 USB 调试"; }

# 当前已启用的无障碍服务（追加用，不覆盖 —— P1-1 结论）
enabled_list() { adb shell settings get secure enabled_accessibility_services | tr -d '\r'; }
enable_probe() {
  local cur; cur="$(enabled_list)"
  case ",$cur," in *",$COMP,"*) echo "已在列表: $cur"; return;; esac
  if [ -z "$cur" ] || [ "$cur" = "null" ]; then cur="$COMP"; else cur="$cur:$COMP"; fi
  adb shell settings put secure enabled_accessibility_services "$cur" || die "写入失败"
  adb shell settings put secure accessibility_enabled 1
  echo "已追加写入: $cur"
}
wait_bind() {  # $1=超时秒
  local i=0
  while [ $i -lt "$1" ]; do
    adb logcat -d -s "$KEY" 2>/dev/null | tr -d '\r' | grep -q "onServiceConnected" && return 0
    sleep 1; i=$((i+1))
  done
  return 1
}
probe_log() { adb shell run-as "$PKG" cat files/probe.log 2>/dev/null | tr -d '\r'; }

case "$1" in
install)
  need_dev
  adb install -r "$APK" || die "安装失败（HyperOS 可能弹『USB 安装』确认，请在手机上允许）"
  adb logcat -c
  enable_probe
  echo "等待系统绑定（10s）..."
  if wait_bind 10; then
    echo "PASS: 服务已绑定（HyperOS 放行了 adb 追加写入的无障碍开关）"
  else
    echo "FAIL_CHECK: 10s 内未绑定。可能: ① MIUI 安全中心拦截 ② 需手动到 设置-无障碍 开启 ③ 弹窗待确认"
    adb shell dumpsys accessibility | grep -A2 -i "$PKG" || true
  fi
  ;;
probe)
  need_dev
  adb logcat -d -s "$KEY" | tr -d '\r' | grep HEARTBEAT | tail -3
  echo "--- 打开计算器观察节点数 ---"
  adb shell am start -n com.miui.calculator/.cal.CalculatorActivity 2>/dev/null || adb shell monkey -p com.miui.calculator -c android.intent.category.LAUNCHER 1
  sleep 4; probe_log | grep HEARTBEAT | tail -2
  echo "--- 打开微信观察节点数（对照 uiautomator nodes=1）---"
  adb shell monkey -p com.tencent.mm -c android.intent.category.LAUNCHER 1 2>/dev/null || echo "微信未安装"
  sleep 6; probe_log | grep HEARTBEAT | tail -2
  echo "--- 判读: nodes>50 且 clickable>10 即认为 A11y 可读且远优于 uiautomator dump ---"
  ;;
kill)
  need_dev
  adb logcat -c
  echo "杀掉探针进程..."
  adb shell run-as "$PKG" sh -c 'kill $(pidof com.caibao.a11yprobe)' 2>/dev/null \
    || { pid=$(adb shell pidof $PKG | tr -d '\r'); adb shell run-as "$PKG" kill "$pid"; }
  t0=$(date +%s%3N)
  if wait_bind 30; then
    t1=$(date +%s%3N)
    echo "PASS: 系统自动重绑耗时 $((t1-t0))ms（目标: <5s=优秀, <15s=可用, 不恢复=死路）"
  else
    echo "FAIL: 30s 未自动重绑 —— HyperOS 3 对 a11y 服务死亡不自动恢复，架构需加保活/自拉起"
    adb shell dumpsys accessibility | grep -A2 -i "$PKG" || true
  fi
  ;;
forcestop)
  need_dev
  adb logcat -c
  adb shell am force-stop "$PKG"
  echo "已 force-stop。等待 20s 观察..."
  if wait_bind 20; then echo "PASS: force-stop 后系统仍重绑（意外之喜）"; else
    echo "WARN: force-stop 后不重绑（预期行为）。重新启用开关即可恢复:"
    enable_probe; wait_bind 10 && echo "PASS: 重新写入后恢复" || echo "FAIL: 写入后仍未恢复，需手动开关"
  fi
  ;;
soak)
  need_dev
  mins="${2:-2}"
  adb logcat -c
  echo "锁屏 ${mins} 分钟（power 键），期间勿动手机..."
  adb shell input keyevent 26
  sleep $((mins * 60))
  adb shell input keyevent 26   # 亮屏
  sleep 2
  adb logcat -d -s "$KEY" | tr -d '\r' | grep -E "onUnbind|onDestroy|onServiceConnected" | tail -6
  if adb logcat -d -s "$KEY" | tr -d '\r' | grep -qE "onUnbind|onDestroy"; then
    echo "FAIL_CHECK: 锁屏期间服务被解绑/销毁 —— HyperOS 省电策略杀 a11y，需前台服务/电池白名单"
  else
    echo "PASS: 锁屏 ${mins} 分钟全程存活"
  fi
  ;;
reboot)
  need_dev
  adb logcat -c
  adb reboot
  echo "重启中，等待设备上线（最长 120s）..."
  adb wait-for-device
  sleep 20
  enable_probe
  if wait_bind 60; then echo "PASS: 重启后自动重绑"; else
    echo "FAIL_CHECK: 重启后未自动绑定，检查 dumpsys accessibility 与安全中心『自启动』权限"
  fi
  ;;
*)
  grep '^#' "$0" | head -8
  ;;
esac
