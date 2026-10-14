#!/bin/ash
cd /data/data/com.carlos.multiapp/files
: > replist19c.txt
while read -r S LEN; do
  N=${#S}
  [ $N -lt 13 ] && [ $LEN -gt 0 ] && echo "$S $LEN" >> replist19c.txt
done < replist19.txt
wc -l replist19c.txt
./memdump_native 19145 replist19c.txt gh19.bin | tail -2
ls -la gh19.bin
for p in "fishing_guide" "tonumber" "CAB-33567f" "m_PathID"; do
  echo "== $p"
  grep -a -bo "$p" gh19.bin 2>/dev/null | head -3
done
