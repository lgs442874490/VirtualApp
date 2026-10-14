#!/bin/ash
GPID=19145
: > /data/data/com.carlos.multiapp/files/scan4.txt
grep "rw-p" /proc/$GPID/maps | while read -r line; do
  START=$(echo "$line" | cut -d- -f1)
  END=$(echo "$line" | cut -d- -f2 | cut -d' ' -f1)
  S=$((16#$START)); E=$((16#$END)); LEN=$((E - S))
  [ $LEN -lt 100000 ] && continue
  O=0
  while [ $O -lt $LEN ]; do
    C=$((4194304)); [ $((O + C)) -gt $LEN ] && C=$((LEN - O))
    dd if=/proc/$GPID/mem bs=4096 skip=$(( (S + O) / 4096 )) count=$(( (C + 4095) / 4096 )) 2>/dev/null | grep -a -bo "CAB-33567f2cc31d80ad4c44c1c7d4d435a7" | while read -r h; do
      HO=$(echo "$h" | cut -d: -f1)
      echo "$((S + O + HO)) cab" >> /data/data/com.carlos.multiapp/files/scan4.txt
    done 2>/dev/null
    dd if=/proc/$GPID/mem bs=4096 skip=$(( (S + O) / 4096 )) count=$(( (C + 4095) / 4096 )) 2>/dev/null | grep -a -bo "2022.3.52f1" | while read -r h; do
      HO=$(echo "$h" | cut -d: -f1)
      echo "$((S + O + HO)) ver" >> /data/data/com.carlos.multiapp/files/scan4.txt
    done 2>/dev/null
    O=$((O + C))
  done
done
echo SCAN4_DONE
wc -l /data/data/com.carlos.multiapp/files/scan4.txt
