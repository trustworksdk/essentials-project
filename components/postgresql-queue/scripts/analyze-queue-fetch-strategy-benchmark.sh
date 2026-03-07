#!/usr/bin/env bash
set -euo pipefail

CSV_FILE="${1:-components/postgresql-queue/target/queue-fetch-strategy-benchmark.csv}"
MIN_IMPROVEMENT="${MIN_IMPROVEMENT:-0.10}"
MAX_DEDUP_RATIO="${MAX_DEDUP_RATIO:-1.25}"
MIN_PASS_RATIO="${MIN_PASS_RATIO:-0.60}"

if [[ ! -f "$CSV_FILE" ]]; then
  echo "CSV file not found: $CSV_FILE" >&2
  echo "Run the benchmark first or pass a csv path as the first argument." >&2
  exit 1
fi

awk -F',' \
  -v min_improvement="$MIN_IMPROVEMENT" \
  -v max_dedup_ratio="$MAX_DEDUP_RATIO" \
  -v min_pass_ratio="$MIN_PASS_RATIO" '
BEGIN {
  print "Analyzing benchmark CSV: " ARGV[1]
  print "Rules:"
  print "- pass row when (avg improvement >= " min_improvement " OR p95 improvement >= " min_improvement ") AND dedup ratio <= " max_dedup_ratio
  print "- switch threshold N is the smallest queue_count where pass_ratio >= " min_pass_ratio " for N and all higher queue_count values"
  print ""
}
NR == 1 {
  for (i = 1; i <= NF; i++) {
    header[$i] = i
  }

  required[1] = "queue_count"
  required[2] = "per_queue_avg_ms"
  required[3] = "per_queue_p95_ms"
  required[4] = "batched_avg_ms"
  required[5] = "batched_p95_ms"
  required[6] = "batched_avg_dedup_ratio"

  for (r = 1; r <= 6; r++) {
    if (!(required[r] in header)) {
      print "Missing required CSV column: " required[r] > "/dev/stderr"
      exit 2
    }
  }

  q_col = header["queue_count"]
  p_avg_col = header["per_queue_avg_ms"]
  p95_col = header["per_queue_p95_ms"]
  b_avg_col = header["batched_avg_ms"]
  b95_col = header["batched_p95_ms"]
  dedup_col = header["batched_avg_dedup_ratio"]
  next
}
NR > 1 {
  q = $q_col + 0
  per_avg = $p_avg_col + 0
  per_p95 = $p95_col + 0
  bat_avg = $b_avg_col + 0
  bat_p95 = $b95_col + 0
  dedup = $dedup_col + 0

  if (per_avg <= 0 || per_p95 <= 0) {
    next
  }

  avg_improvement = (per_avg - bat_avg) / per_avg
  p95_improvement = (per_p95 - bat_p95) / per_p95

  pass = ((avg_improvement >= min_improvement || p95_improvement >= min_improvement) && dedup <= max_dedup_ratio) ? 1 : 0

  total[q] += 1
  passed[q] += pass

  sum_avg_imp[q] += avg_improvement
  sum_p95_imp[q] += p95_improvement
  sum_dedup[q] += dedup

  seen[q] = 1
}
END {
  count = 0
  for (q in seen) {
    count++
    queue_values[count] = q
  }

  if (count == 0) {
    print "No data rows found in CSV." > "/dev/stderr"
    exit 3
  }

  for (i = 1; i <= count; i++) {
    for (j = i + 1; j <= count; j++) {
      if (queue_values[i] + 0 > queue_values[j] + 0) {
        tmp = queue_values[i]
        queue_values[i] = queue_values[j]
        queue_values[j] = tmp
      }
    }
  }

  print "Per queue_count summary:"
  printf "%-12s %-8s %-10s %-12s %-12s %-12s\n", "queue_count", "rows", "pass_ratio", "avg_imp(%)", "p95_imp(%)", "avg_dedup"

  for (i = 1; i <= count; i++) {
    q = queue_values[i]
    pr = (total[q] > 0 ? passed[q] / total[q] : 0)
    avg_imp_pct = (total[q] > 0 ? (sum_avg_imp[q] / total[q]) * 100 : 0)
    p95_imp_pct = (total[q] > 0 ? (sum_p95_imp[q] / total[q]) * 100 : 0)
    avg_dedup = (total[q] > 0 ? sum_dedup[q] / total[q] : 0)

    pass_ratio[q] = pr

    printf "%-12d %-8d %-10.2f %-12.2f %-12.2f %-12.3f\n", q, total[q], pr, avg_imp_pct, p95_imp_pct, avg_dedup
  }

  threshold = -1
  for (i = 1; i <= count; i++) {
    q = queue_values[i]

    if (pass_ratio[q] < min_pass_ratio) {
      continue
    }

    stable = 1
    for (j = i; j <= count; j++) {
      q2 = queue_values[j]
      if (pass_ratio[q2] < min_pass_ratio) {
        stable = 0
        break
      }
    }

    if (stable == 1) {
      threshold = q
      break
    }
  }

  print ""
  if (threshold == -1) {
    print "Recommended switch threshold N: not found"
    print "Interpretation: no stable crossover based on current thresholds/data."
    print "Suggestion: run more repetitions or tune MIN_IMPROVEMENT / MAX_DEDUP_RATIO / MIN_PASS_RATIO."
  } else {
    print "Recommended switch threshold N: " threshold
    print "Interpretation: use per-queue for queue_count <= " threshold ", use batched for queue_count > " threshold
  }
}
' "$CSV_FILE"
