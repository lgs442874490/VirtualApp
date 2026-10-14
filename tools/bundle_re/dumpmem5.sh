#!/bin/ash
OUT=/data/data/com.carlos.multiapp/files
GPID=19145
: > $OUT/gameheap5.bin
: > $OUT/memidx5.txt
OFF=0
while read -r S LEN; do
  dd if=/proc/$GPID/mem of=$OUT/p5.bin bs=1048576 iflag=skip_bytes,count_bytes skip=$S count=$LEN 2>/dev/null
  SZ=$(wc -c < $OUT/p5.bin)
  [ $SZ -lt $LEN ] && dd if=/dev/zero of=$OUT/p5.bin bs=1 seek=$SZ count=$((LEN - SZ)) 2>/dev/null
  cat $OUT/p5.bin >> $OUT/gameheap5.bin
  echo "$OFF $S $LEN" >> $OUT/memidx5.txt
  OFF=$((OFF + LEN))
done < $OUT/replist19.txt
rm -f $OUT/p5.bin
echo DUMP5_DONE
ls -la $OUT/gameheap5.bin
