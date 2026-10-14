#!/bin/ash
GPID=19145
: > /data/data/com.carlos.multiapp/files/scan6.txt
grep "rw-p" /proc/$GPID/maps | while read -r line; do
  START=$(echo "$line" | cut -d- -f1)
  END=$(echo "$line" | cut -d- -f2 | cut -d' ' -f1)
  S=$((16#$START)); E=$((16#$END)); LEN=$((E - S))
  [ $LEN -lt 2000000 ] && continue
  O=0
  while [ $O -lt $LEN ]; do
    C=$((8388608)); [ $((O + C)) -gt $LEN ] && C=$((LEN - O))
    dd if=/proc/$GPID/mem bs=4096 skip=$(( (S + O) / 4096 )) count=$(( (C + 4095) / 4096 )) 2>/dev/null | grep -a -bo "CAB-33567f" | while read -r h; do
      HO=$(echo "$h" | cut -d: -f1)
      echo "$((S + O + HO)) CAB" >> /data/data/com.carlos.multiapp/files/scan6.txt
    done 2>/dev/null
    O=$((O + C))
  done
done
echo SCAN6_DONE
wc -l /data/data/com.carlos.multiapp/files/scan6.txt
