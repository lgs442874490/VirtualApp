#!/bin/ash
OUT=/data/data/com.carlos.multiapp/files
GPID=19145
: > $OUT/gameheap6.bin
: > $OUT/memidx6.txt
OFF=0
while read -r S LEN; do
  [ $S -lt 4294967296 ] && continue
  dd if=/proc/$GPID/mem of=$OUT/p6.bin bs=1048576 iflag=skip_bytes,count_bytes skip=$S count=$LEN 2>/dev/null
  SZ=$(wc -c < $OUT/p6.bin)
  [ $SZ -lt $LEN ] && dd if=/dev/zero of=$OUT/p6.bin bs=1 seek=$SZ count=$((LEN - SZ)) 2>/dev/null
  cat $OUT/p6.bin >> $OUT/gameheap6.bin
  echo "$OFF $S $LEN" >> $OUT/memidx6.txt
  OFF=$((OFF + SZ))
done < $OUT/replist19.txt
rm -f $OUT/p6.bin
echo DUMP6_DONE
ls -la $OUT/gameheap6.bin
