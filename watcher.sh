#!/bin/ash
OUT=/data/data/com.carlos.multiapp/files
GPID=""
# 等待游戏启动, 最多 120 x 15s = 30 分钟
for t in $(seq 1 120); do
  GPID=""
  MAXRSS=0
  for pid in $(ps -A 2>/dev/null | grep "gof.china" | grep -v xg_vip | awk '{print $2}'); do
    RSS=$(grep VmRSS /proc/$pid/status 2>/dev/null | tr -dc '0-9')
    [ -n "$RSS" ] && [ "$RSS" -gt "$MAXRSS" ] && MAXRSS=$RSS && GPID=$pid
  done
  [ -n "$GPID" ] && [ "$MAXRSS" -gt 200000 ] && break
  sleep 15
done
if [ -z "$GPID" ]; then echo "WATCH_TIMEOUT_NO_GAME"; exit 1; fi
echo "GAME_PID=$GPID RSS=${MAXRSS}KB t=$t"
# 等待游戏可读(前台唤醒换页), 40 x 15s
READY=0
for t2 in $(seq 1 40); do
  P1=$(head -3 $OUT/replist19.txt 2>/dev/null | tail -1 | cut -d' ' -f1)
  NZ=$(dd if=/proc/$GPID/mem bs=4096 skip=$((P1 / 4096)) count=16 2>/dev/null | tr -d "\000" | wc -c)
  if [ "$NZ" -gt 1000 ]; then READY=1; break; fi
  sleep 15
done
echo "READY=$READY at $(date)"
# 生成新区段清单
: > $OUT/replist20.txt
grep "rw-p" /proc/$GPID/maps | while read -r line; do
  START=$(echo "$line" | cut -d- -f1)
  END=$(echo "$line" | cut -d- -f2 | cut -d' ' -f1)
  S=$((16#$START)); E=$((16#$END)); LEN=$((E - S))
  [ $LEN -lt 262144 ] && continue
  [ $LEN -gt 1500000000 ] && LEN=1500000000
  echo "$S $LEN" >> $OUT/replist20.txt
done
# dump
: > $OUT/gameheap6.bin
: > $OUT/memidx6.txt
OFF=0
while read -r S LEN; do
  [ $S -lt 4294967296 ] && continue
  dd if=/proc/$GPID/mem of=$OUT/p6.bin bs=1048576 iflag=skip_bytes,count_bytes skip=$S count=$LEN 2>/dev/null
  SZ=$(wc -c < $OUT/p6.bin)
  [ $SZ -lt $LEN ] && dd if=/dev/zero of=$OUT/p6.bin bs=4096 seek=$((SZ / 4096)) count=$(( (LEN - SZ + 4095) / 4096 )) 2>/dev/null
  cat $OUT/p6.bin >> $OUT/gameheap6.bin
  echo "$OFF $S $LEN" >> $OUT/memidx6.txt
  OFF=$((OFF + SZ))
done < $OUT/replist20.txt
echo DUMP_DONE
ls -la $OUT/gameheap6.bin
# 搜索标记
for p in "CAB-33567f" "tonumber" "AssetBundle" "fishing_guide" "value_exchange"; do
  echo "== $p"
  grep -a -bo "$p" $OUT/gameheap6.bin 2>/dev/null | head -5
done
