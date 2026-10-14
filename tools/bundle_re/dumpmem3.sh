#!/bin/ash
OUT=/data/data/com.carlos.multiapp/files
GPID=19145
: > $OUT/gameheap2.bin
: > $OUT/memidx3.txt
OFF=0
while read -r S LEN; do
  dd if=/proc/$GPID/mem of=$OUT/part2.bin bs=4096 skip=$((S / 4096)) count=$((LEN / 4096)) conv=noerror,sync 2>/dev/null
  SZ=$(wc -c < $OUT/part2.bin)
  cat $OUT/part2.bin >> $OUT/gameheap2.bin
  echo "$OFF $S $LEN $SZ" >> $OUT/memidx3.txt
  OFF=$((OFF + SZ))
done < $OUT/replist.txt
rm -f $OUT/part2.bin
echo DUMP3_DONE
ls -la $OUT/gameheap2.bin
