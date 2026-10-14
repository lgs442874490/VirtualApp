#!/bin/ash
OUT=/data/data/com.carlos.multiapp/files
GPID=19145
: > $OUT/gameheap3.bin
: > $OUT/memidx4.txt
OFF=0
while read -r S LEN; do
  P=0
  while [ $P -lt $LEN ]; do
    C=$((2097152)); [ $((P + C)) -gt $LEN ] && C=$((LEN - P))
    dd if=/proc/$GPID/mem of=$OUT/c.bin bs=4096 skip=$(( (S + P) / 4096 )) count=$((C / 4096)) 2>/dev/null
    SZ=$(wc -c < $OUT/c.bin)
    if [ $SZ -lt $C ]; then
      dd if=/dev/zero of=$OUT/zpad.bin bs=4096 count=$(( (C - SZ + 4095) / 4096 )) 2>/dev/null
      head -c $((C - SZ)) $OUT/zpad.bin >> $OUT/c.bin
    fi
    cat $OUT/c.bin >> $OUT/gameheap3.bin
    P=$((P + C))
  done
  echo "$OFF $S $LEN" >> $OUT/memidx4.txt
  OFF=$((OFF + LEN))
done < $OUT/replist.txt
rm -f $OUT/c.bin $OUT/zpad.bin
echo DUMP4_DONE
ls -la $OUT/gameheap3.bin
