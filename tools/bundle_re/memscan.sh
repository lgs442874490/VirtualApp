#!/bin/ash
# 用法: adb shell | 本脚本在设备上以 run-as 身份执行
# 前置: 号库壳 ▶ 启动 容器内游戏, 加载到登录界面后运行:
#   adb shell "run-as com.carlos.multiapp sh -c 'sh /data/data/com.carlos.multiapp/files/memscan.sh'"
# 或由 PC 端推送后执行。扫描容器内游戏进程内存, 提取解密后的 luapatch 容器。
PID=""
for p in /proc/[0-9]*/cmdline; do
  :  # 占位, 下面直接用 ps
done
GPID=$(ps -A 2>/dev/null | grep -E 'multiapp(:|$)' | awk '{print $2, $6}' | sort -k2 | awk '{print $1}' | tail -1)
# 取 RSS 最大的 multiapp 进程(游戏在容器里 RSS 最大)
GPID=""
MAXRSS=0
for line in $(ps -A 2>/dev/null | grep multiapp); do :; done
# toybox ps 列不定, 直接枚举两个已知进程
for pid in $(ps -A 2>/dev/null | grep multiapp | awk '{print $2}'); do
  RSS=$(grep VmRSS /proc/$pid/status 2>/dev/null | tr -dc '0-9')
  [ -n "$RSS" ] && [ "$RSS" -gt "$MAXRSS" ] && MAXRSS=$RSS && GPID=$pid
done
echo "目标进程: $GPID (RSS ${MAXRSS}KB)"
[ -z "$GPID" ] && exit 1
grep -c rw-p /proc/$GPID/maps
# 遍历 rw-p 区段, 找 CAB 名 (解密容器的铁证)
OUT=/data/data/com.carlos.multiapp/files
: > $OUT/hits.txt
grep 'rw-p' /proc/$GPID/maps | while read -r line; do
  START=$(echo "$line" | cut -d- -f1)
  END=$(echo "$line" | cut -d- -f2 | cut -d' ' -f1)
  S=$((16#$START)); E=$((16#$END)); LEN=$((E - S))
  [ $LEN -lt 3145728 ] && continue   # 容器 3.27MB, 只扫 ≥3MB 区段
  echo "扫 $line ($LEN B)"
  # 4MB 分块 grep CAB 名前缀
  CH=$((4194304))
  O=0
  while [ $O -lt $LEN ]; do
    C=$CH; [ $((O + C)) -gt $LEN ] && C=$((LEN - O))
    dd if=/proc/$GPID/mem bs=1 skip=$((S + O)) count=$((C + 64)) 2>/dev/null | \
      grep -a -bo "CAB-33567f2cc31d80ad4c44c1c7d4d435a7" | while read -r h; do
        HO=$(echo "$h" | cut -d: -f1)
        echo "$((S + O + HO))" >> $OUT/hits.txt
    done 2>/dev/null
    O=$((O + C))
  done
done
echo "命中:"; cat $OUT/hits.txt
# 第一命中: 回退 294B = 容器头, 提取 3.27MB
H=$(head -1 $OUT/hits.txt)
[ -z "$H" ] && echo "未命中(游戏未加载完?)" && exit 1
S0=$((H - 294))
[ $S0 -lt 0 ] && S0=0
dd if=/proc/$GPID/mem of=$OUT/container.bin bs=1 skip=$S0 count=3270180 2>/dev/null
echo "提取 $OUT/container.bin"
md5sum $OUT/container.bin
