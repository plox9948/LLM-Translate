#!/system/bin/sh
# Профилирование RAM модели: внешний замер PSS процесса во время теста.
# Использование: sh ramprof.sh <model_id> [hold_ms] [hold2_ms]
MODEL=$1
HOLD=${2:-15000}
HOLD2=${3:-8000}
MD=/storage/emulated/0/Android/data/com.plox.llmtranslate/files/prof
OUT=/data/local/tmp/ramprof_$MODEL
mkdir -p $OUT
rm -f $MD/hold.marker $MD/rel.marker $OUT/* 2>/dev/null

am instrument -w \
  -e class com.plox.llmtranslate.ModelRamProfileTest#ramProfile \
  -e model "$MODEL" -e hold "$HOLD" -e hold2 "$HOLD2" \
  com.plox.llmtranslate.test/androidx.test.runner.AndroidJUnitRunner \
  > $OUT/instr.txt 2>&1 &
IP=$!

echo "t_ms,pid,pss_kb" > $OUT/pss.csv
CAP=0
CAP2=0
while kill -0 $IP 2>/dev/null; do
  NOW=$(date +%s%3N)
  P=$(pidof com.plox.llmtranslate)
  if [ -n "$P" ]; then
    dumpsys meminfo $P 2>/dev/null | grep -w TOTAL | head -1 | \
      awk -v t=$NOW -v p=$P '{print t","p","$2}' >> $OUT/pss.csv
    if [ $CAP -eq 0 ] && [ -f "$MD/hold.marker" ]; then
      dumpsys meminfo $P > $OUT/meminfo_loaded.txt
      echo "CAP_LOADED $NOW" >> $OUT/ev.txt
      CAP=1
    fi
    if [ $CAP2 -eq 0 ] && [ -f "$MD/rel.marker" ]; then
      dumpsys meminfo $P > $OUT/meminfo_released.txt
      echo "CAP_REL $NOW" >> $OUT/ev.txt
      CAP2=1
    fi
  fi
  sleep 0.15
done

echo "=== INSTR ==="
cat $OUT/instr.txt
echo "=== PSS_CSV ==="
cat $OUT/pss.csv
echo "=== DONE $MODEL ==="
