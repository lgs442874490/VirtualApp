#!/bin/ash
cd /data/data/com.carlos.multiapp/files
printf -v L "\033Lua"
echo "== LuaSig"
grep -a -bo "$L" heap_native.bin 2>/dev/null | head -10 > luahits.txt
wc -l luahits.txt
head -10 luahits.txt
# 提取 value_exchange 三个区域 ±1.5MB
i=0
for OFF in 2640978420 2702662928 2768364727; do
  S=$((OFF - 1572864)); [ $S -lt 0 ] && S=0
  dd if=heap_native.bin of=vex_$i.bin bs=4096 iflag=skip_bytes,count_bytes skip=$S count=3145728 2>/dev/null
  echo "vex_$i.bin @$OFF"
  i=$((i + 1))
done
ls -la vex_*.bin
