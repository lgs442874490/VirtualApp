#!/bin/ash
# 在容器(multiapp)进程内存中定位解压后的 luapatch 归档并提取
# 用法: adb shell "run-as com.carlos.multiapp sh -c 'sh /data/data/com.carlos.multiapp/files/scan_container.sh'"
OUT=/data/data/com.carlos.multiapp/files
GPID=""
MAXRSS=0
for pid in $(ps -A 2>/dev/null | grep multiapp | awk '{print $2}'); do
  RSS=$(grep VmRSS /proc/$pid/status 2>/dev/null | tr -dc '0-9')
  [ -n "$RSS" ] && [ "$RSS" -gt "$MAXRSS" ] && MAXRSS=$RSS && GPID=$pid
done
echo "目标进程: $GPID (RSS ${MAXRSS}KB)"
[ -z "$GPID" ] && exit 1
: > $OUT/gthits.txt
grep 'rw-p' /proc/$GPID/maps | while read -r line; do
  case "$line" in
    *'"'[A-Za-z]*'"'*) ;; # 只扫匿名/大区段
  esac
  START=$(echo "$line" | cut -d- -f1)
  END=$(echo "$line" | cut -d- -f2 | cut -d' ' -f1)
  S=$((16#$START)); E=$((16#$END)); LEN=$((E - S))
  [ $LEN -lt 3000000 ] && continue
  echo "扫 $line ($LEN B)"
  O=0
  while [ $O -lt $LEN ]; do
    C=$((4194304)); [ $((O + C)) -gt $LEN ] && C=$((LEN - O))
    dd if=/proc/$GPID/mem bs=$C skip=1 count=1 skip=$((0)) 2>/dev/null >/dev/null # noop防旧版兼容
    dd if=/proc/$GPID/mem bs=4096 skip=$(( (S + O) / 4096 )) count=$(( (C + 4095) / 4096 )) 2>/dev/null | \
      grep -a -bo "midautumn2026_valueexchange_bg_001" | while read -r h; do
        HO=$(echo "$h" | cut -d: -f1)
        echo "$((S + O + HO)) marker_vex" >> $OUT/gthits.txt
    done 2>/dev/null
    dd if=/proc/$GPID/mem bs=4096 skip=$(( (S + O) / 4096 )) count=$(( (C + 4095) / 4096 )) 2>/dev/null | \
      grep -a -bo "fishing_guide 21 : boolean" | while read -r h; do
        HO=$(echo "$h" | cut -d: -f1)
        echo "$((S + O + HO)) marker_lua" >> $OUT/gthits.txt
    done 2>/dev/null
    O=$((O + C))
  done
done
echo "命中:"; sort -u $OUT/gthits.txt | head -20
