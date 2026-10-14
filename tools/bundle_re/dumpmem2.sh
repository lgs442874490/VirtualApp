#!/bin/ash
OUT=/data/data/com.carlos.multiapp/files
GPID=19145
# pass1: 生成区段清单 (十进制 S LEN)
: > $OUT/replist.txt
grep "rw-p" /proc/$GPID/maps | while read -r line; do
  START=$(echo "$line" | cut -d- -f1)
  END=$(echo "$line" | cut -d- -f2 | cut -d' ' -f1)
  S=$((16#$START)); E=$((16#$END)); LEN=$((E - S))
  [ $LEN -lt 262144 ] && continue
  [ $LEN -gt 1500000000 ] && LEN=1500000000
  echo "$S $LEN" >> $OUT/replist.txt
done
wc -l $OUT/replist.txt
# pass2: 逐段 dd
: > $OUT/gameheap.bin
: > $OUT/memidx2.txt
OFF=0
while read -r S LEN; do
  dd if=/proc/$GPID/mem of=$OUT/part.bin bs=4096 skip=$((S / 4096)) count=$((LEN / 4096)) 2>/dev/null
  cat $OUT/part.bin >> $OUT/gameheap.bin
  echo "$OFF $S $LEN" >> $OUT/memidx2.txt
  OFF=$((OFF + LEN))
done < $OUT/replist.txt
rm -f $OUT/part.bin
echo DUMP2_DONE
ls -la $OUT/gameheap.bin
