#!/bin/ash
OUT=/data/data/com.carlos.multiapp/files
GPID=30243
# 生成区段清单
: > $OUT/replist30.txt
grep "rw-p" /proc/$GPID/maps | while read -r line; do
  START=$(echo "$line" | cut -d- -f1)
  END=$(echo "$line" | cut -d- -f2 | cut -d' ' -f1)
  S=$((16#$START)); E=$((16#$END)); LEN=$((E - S))
  [ $LEN -lt 262144 ] && continue
  [ $LEN -gt 1500000000 ] && LEN=1500000000
  echo "$S $LEN" >> $OUT/replist30.txt
done
wc -l $OUT/replist30.txt
# dump (iflag=skip_bytes 修复版)
: > $OUT/heap30.bin
OFF=0
while read -r S LEN; do
  dd if=/proc/$GPID/mem of=$OUT/p30.bin bs=1048576 iflag=skip_bytes,count_bytes skip=$S count=$LEN 2>/dev/null
  SZ=$(wc -c < $OUT/p30.bin)
  [ $SZ -lt $LEN ] && dd if=/dev/zero of=$OUT/p30.bin bs=4096 seek=$((SZ / 4096)) count=$(( (LEN - SZ + 4095) / 4096 )) 2>/dev/null
  cat $OUT/p30.bin >> $OUT/heap30.bin
  OFF=$((OFF + LEN))
done < $OUT/replist30.txt
rm -f $OUT/p30.bin
echo DUMP_DONE
ls -la $OUT/heap30.bin
# 标记搜索
cd $OUT
for p in "CAB-33567f" "tonumber" "m_PathID" "fishing_guide" "value_exchange"; do
  echo "== $p"
  grep -a -bo "$p" heap30.bin 2>/dev/null | head -4
done
