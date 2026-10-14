#!/bin/ash
OUT=/data/data/com.carlos.multiapp/files
GPID=23237
: > $OUT/gthits.txt
grep "rw-p" /proc/$GPID/maps | while read -r line; do
  START=$(echo "$line" | cut -d- -f1)
  END=$(echo "$line" | cut -d- -f2 | cut -d' ' -f1)
  S=$((16#$START)); E=$((16#$END)); LEN=$((E - S))
  [ $LEN -lt 3000000 ] && continue
  O=0
  while [ $O -lt $LEN ]; do
    C=$((4194304)); [ $((O + C)) -gt $LEN ] && C=$((LEN - O))
    dd if=/proc/$GPID/mem bs=4096 skip=$(( (S + O) / 4096 )) count=$(( (C + 4095) / 4096 )) 2>/dev/null | grep -a -bo "midautumn2026_valueexchange_bg_001" | while read -r h; do
      HO=$(echo "$h" | cut -d: -f1)
      echo "$((S + O + HO)) vex" >> $OUT/gthits.txt
    done 2>/dev/null
    dd if=/proc/$GPID/mem bs=4096 skip=$(( (S + O) / 4096 )) count=$(( (C + 4095) / 4096 )) 2>/dev/null | grep -a -bo "fishing_guide 21 : boolean" | while read -r h; do
      HO=$(echo "$h" | cut -d: -f1)
      echo "$((S + O + HO)) lua" >> $OUT/gthits.txt
    done 2>/dev/null
    dd if=/proc/$GPID/mem bs=4096 skip=$(( (S + O) / 4096 )) count=$(( (C + 4095) / 4096 )) 2>/dev/null | grep -a -bo "CAB-33567f2cc31d80ad4c44c1c7d4d435a7" | while read -r h; do
      HO=$(echo "$h" | cut -d: -f1)
      echo "$((S + O + HO)) cab" >> $OUT/gthits.txt
    done 2>/dev/null
    O=$((O + C))
  done
done
echo SCAN_DONE
sort -k2 $OUT/gthits.txt | head -30
