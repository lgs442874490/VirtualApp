#!/bin/ash
GPID=19145
cd /data/data/com.carlos.multiapp/files
: > p5.txt
grep "rw-p" /proc/$GPID/maps | while read -r line; do
  START=$(echo "$line" | cut -d- -f1)
  END=$(echo "$line" | cut -d- -f2 | cut -d' ' -f1)
  S=$((16#$START)); E=$((16#$END)); LEN=$((E - S))
  [ $LEN -lt 1000000 ] && continue
  dd if=/proc/$GPID/mem bs=4096 skip=$((S / 4096)) count=$((LEN / 4096)) 2>/dev/null | grep -a -c "Whiteout" >> p5.txt
done
echo "Whiteout 总数: $(cat p5.txt | paste -sd+ | bc)"
