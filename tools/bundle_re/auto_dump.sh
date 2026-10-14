#!/bin/ash
OUT=/data/data/com.carlos.multiapp/files
GPID=19145
# 等待游戏可读(前台唤醒), 最多 40 次 x 15s = 10 分钟
READY=0
for t in $(seq 1 40); do
  NZ=$(dd if=/proc/$GPID/mem bs=4096 skip=$((1884000256 / 4096)) count=16 2>/dev/null | tr -d "\000" | wc -c)
  if [ "$NZ" -gt 1000 ]; then READY=1; break; fi
  sleep 15
done
if [ "$READY" != "1" ]; then echo "TIMEOUT_GAME_NOT_FOREGROUND"; exit 1; fi
echo "GAME_READY at $(date)"
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
done < $OUT/replist19.txt
rm -f $OUT/p6.bin
echo DUMP6_DONE
ls -la $OUT/gameheap6.bin
grep -a -bo "CAB-33567f" $OUT/gameheap6.bin | head -5
grep -a -bo "tonumber" $OUT/gameheap6.bin | head -3
