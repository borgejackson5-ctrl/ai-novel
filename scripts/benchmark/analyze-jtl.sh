#!/usr/bin/env bash
# JTL 结果统计分析：QPS / 平均 / P50 / P90 / P99 / 最大 / 错误率
# 用法: ./analyze-jtl.sh <file.jtl> [file2.jtl ...]
for f in "$@"; do
  [ -f "$f" ] || { echo "skip: $f not found"; continue; }
  awk -F',' -v file="$f" '
    NR==2 { first=$1 }
    NR>1 {
      n++; ms=int($2); cnt[ms]++; sum+=$2
      if (ms>max) max=ms
      end=$1+$2; if (end>lastEnd) lastEnd=end
      if ($8=="false") { err++; code[$4]++ }
    }
    END {
      total=0; p50=p90=p99=""
      for (b=0;b<=max;b++) {
        total+=cnt[b]
        if (p50=="" && total>=n*0.5) p50=b
        if (p90=="" && total>=n*0.9) p90=b
        if (p99=="" && total>=n*0.99) p99=b
      }
      dur=(lastEnd-first)/1000
      printf "%s: samples=%d dur=%.0fs QPS=%.0f avg=%.1fms p50=%dms p90=%dms p99=%dms max=%dms err=%d(%.2f%%)\n", \
        file, n, dur, n/dur, sum/n, p50, p90, p99, max, err, (n?err/n*100:0)
      for (k in code) printf "  err-detail: [%s] x%d\n", k, code[k]
    }' "$f"
done
