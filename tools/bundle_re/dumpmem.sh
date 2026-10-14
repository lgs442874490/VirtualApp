#!/bin/ash
OUT=/data/data/com.carlos.multiapp/files
GPID=23237
: > $OUT/memidx.txt
: > $OUT/gameheap.bin
OFF=0
grep "rw-p" /proc/$GPID/maps | while read -r line; do
  START=$(echo "$line" | cut -d- -f1)
  END=$(echo "$line" | cut -d- -f2 | cut -d' ' -f1)
  S=$((16#$START)); E=$((16#$END)); LEN=$((E - S))
  [ $LEN -lt 1000000 ] && continue
  [ $LEN -gt 800000000 ] && LEN=800000000
  dd if=/proc/$GPID/mem of=$OUT/gameheap.bin bs=1048576 seek=$((OFF / 1048576)) skip=$S count=0 2>/dev/null >/dev/null
  dd if=/proc/$GPID/mem bs=1048576 skip=$((S / 1048576)) count=$((LEN / 1048576 + 1)) 2>/dev/null | head -c $LEN >> $OUT/gameheap.bin
  SZ=$LEN
  echo "$OFF $S $LEN" >> $OUT/memidx.txt
  OFF=$((OFF + LEN))
done
echo DUMP_DONE
ls -la $OUT/gameheap.bin
