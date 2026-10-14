#!/bin/ash
OUT=/data/data/com.carlos.multiapp/files
GPID=30243
: > $OUT/biglist.txt
awk '$2 + 0 >= 20000000 {print $1, $2}' $OUT/replist30.txt > $OUT/biglist.txt
wc -l $OUT/biglist.txt
while read -r S LEN; do
  dd if=/proc/$GPID/mem of=$OUT/big.bin bs=1048576 iflag=skip_bytes,count_bytes skip=$S count=$LEN 2>/dev/null
  U=$(grep -a -c "UnityEngine" $OUT/big.bin 2>/dev/null)
  I=$(grep -a -c "il2cpp" $OUT/big.bin 2>/dev/null)
  T=$(grep -a -c "tonumber" $OUT/big.bin 2>/dev/null)
  echo "S=$S LEN=$LEN UnityEng=$U il2cpp=$I tonumber=$T"
  rm -f $OUT/big.bin
done < $OUT/biglist.txt
echo FIND_DONE
