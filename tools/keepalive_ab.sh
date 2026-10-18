#!/bin/bash
# 保活 A/B 采集脚本（P2-4）。
#
# 用法：
#   tools/keepalive_ab.sh <serial> <scenario> [output.md] [duration_sec] [interval_sec]
#
# 场景建议（与 qa/修复计划-20261018.md §6.4 对应）：
#   native_absent_container_bg   原生游戏不运行，容器游戏后台，屏幕熄灭，未充电
#   native_fg_container_bg       原生游戏前台，容器游戏后台
#   native_bg_container_bg       原生游戏后台，容器游戏后台
#   server_mode                  开启 vault-server 模式，本机不跑自动化
#
# 采集：电量/温度、Doze/唤醒锁、进程与 oom_score_adj、前台服务、exit-info、
#       daily_log 尾部。输出 Markdown，便于直接贴进验收报告。
# 采集命令允许单项无输出/进程不存在（grep 无匹配、tail 文件缺失、grep|head 的
# SIGPIPE 都属正常），不能因为某一行采集失败就中止整轮——因此这里不用 set -e，
# 只保留未定义变量报错与管道错误可见。
set -uo pipefail

SERIAL="${1:?用法: tools/keepalive_ab.sh <serial> <scenario> [output.md] [duration_sec] [interval_sec]}"
SCENARIO="${2:-unknown}"
OUT="${3:-qa/keepalive-${SCENARIO}-$(date +%Y%m%d-%H%M%S).md}"
DURATION="${4:-60}"
INTERVAL="${5:-30}"
PKG="com.carlos.multiapp"

ADB=(adb -s "$SERIAL")

mkdir -p "$(dirname "$OUT")"
{
  echo "# 保活 A/B 采集"
  echo
  echo "- 时间：$(date '+%Y-%m-%d %H:%M:%S')"
  echo "- 设备：$("${ADB[@]}" shell getprop ro.product.model 2>/dev/null | tr -d '\r') / Android $("${ADB[@]}" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r') (SDK $("${ADB[@]}" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r'))"
  echo "- 场景：$SCENARIO"
  echo "- 时长/间隔：${DURATION}s / ${INTERVAL}s"
  echo
} > "$OUT"

END=$(( $(date +%s) + DURATION ))
while [ "$(date +%s)" -lt "$END" ]; do
  {
    echo "## $(date '+%H:%M:%S')"
    echo '```'
    echo "--- battery ---"
    "${ADB[@]}" shell dumpsys battery 2>/dev/null | grep -E "level|temperature|AC powered|USB powered|status" | tr -d '\r'
    echo "--- power ---"
    "${ADB[@]}" shell dumpsys power 2>/dev/null | grep -E "mWakefulness=|mDeviceIdleMode=|Wake Locks: size=" | tr -d '\r'
    echo "--- deviceidle ---"
    "${ADB[@]}" shell dumpsys deviceidle 2>/dev/null | grep -E "mState=|mLightState=|mScreenOn=" | head -8 | tr -d '\r'
    echo "--- processes ---"
    "${ADB[@]}" shell "ps -A -o PID,NAME,ARGS | grep -E 'com.carlos.multiapp|com.gof.china'" 2>/dev/null | tr -d '\r'
    echo "--- oom_score_adj ---"
    for pid in $("${ADB[@]}" shell "ps -A -o PID,NAME | grep -E 'com.carlos.multiapp|com.gof.china' | awk '{print \$1}'" 2>/dev/null | tr -d '\r'); do
      adj=$("${ADB[@]}" shell "cat /proc/$pid/oom_score_adj 2>/dev/null" | tr -d '\r')
      name=$("${ADB[@]}" shell "cat /proc/$pid/cmdline 2>/dev/null | tr '\0' ' '" | tr -d '\r')
      echo "$pid $adj $name"
    done
    echo "--- foreground services ---"
    "${ADB[@]}" shell dumpsys activity services "$PKG" 2>/dev/null | grep -E "ServiceRecord|isForeground|foregroundId" | head -20 | tr -d '\r'
    echo "--- exit-info (head) ---"
    "${ADB[@]}" shell dumpsys activity exit-info "$PKG" 2>/dev/null | head -20 | tr -d '\r'
    echo "--- daily_log tail ---"
    "${ADB[@]}" shell "tail -5 /sdcard/Android/data/$PKG/files/daily_log.txt 2>/dev/null" | tr -d '\r'
    echo '```'
    echo
  } >> "$OUT"
  sleep "$INTERVAL"
done

echo "[keepalive] 完成：$OUT"
