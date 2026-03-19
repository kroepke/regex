#!/bin/bash
# Backfill benchmark data for historical stages with proper JMH parameters.
# Run this overnight — takes ~8 hours sequential.
#
# Usage: ./scripts/backfill-benchmarks.sh
#
# Output goes to docs/benchmarks/stage-XX-benchmarks.txt
#
# Strategy: We can't copy the current SearchBenchmark.java into old stages
# because benchmark methods were added over time (wordRepeat, multiline,
# literalMiss don't exist in early stages). Instead, we use JMH command-line
# overrides which take precedence over annotations.
#
# We run SearchBenchmark in two passes per stage:
#   Pass 1: slow benchmarks (charClass, alternation) with heavy warmup
#   Pass 2: all other benchmarks with standard warmup
# This ensures slow benchmarks get enough C2 warmup (≥10K invocations).

set -euo pipefail

STAGES="stage-6-three-phase-restored stage-10-allocation-cleanup stage-12-dense-dfa stage-14-multi-start stage-15-special-state-taxonomy"
RESULTS_DIR="docs/benchmarks"
WORKTREE_BASE=".worktrees"

mkdir -p "$RESULTS_DIR"

for stage in $STAGES; do
    echo ""
    echo "================================================================"
    echo "  Benchmarking: $stage"
    echo "  Started at: $(date)"
    echo "================================================================"
    echo ""

    worktree="$WORKTREE_BASE/bench-$stage"
    results_file="$RESULTS_DIR/$stage-benchmarks.txt"

    # Clean up any leftover worktree
    if [ -d "$worktree" ]; then
        git worktree remove --force "$worktree" 2>/dev/null || true
    fi

    # Create worktree at the stage tag
    git worktree add "$worktree" "$stage"
    (cd "$worktree" && git submodule update --init)

    # Build
    (cd "$worktree" && ./mvnw -P bench package -DskipTests -q)

    # Clear results file
    > "$results_file"

    # Pass 1: Slow benchmarks (charClass, alternation) — heavy warmup
    # ~100 ops/s needs 20s × 10 warmup = 200s ≈ 20K invocations for C2
    echo "--- Pass 1: slow benchmarks (charClass, alternation) ---" | tee -a "$results_file"
    (cd "$worktree" && java -jar regex-bench/target/benchmarks.jar \
        "SearchBenchmark.*(charClass|alternation)" \
        -f 3 -wi 10 -w 20s -i 5 -r 20s) \
        2>&1 | tee -a "$results_file"

    # Pass 2: Remaining SearchBenchmarks — standard warmup
    # Exclude charClass and alternation (already measured).
    # Use a regex that matches everything EXCEPT those two.
    # Medium (multiline ~350 ops/s): would benefit from longer warmup but
    # 5s × 5 = 25s ≈ 8.7K invocations is close enough with 3 forks.
    echo "" | tee -a "$results_file"
    echo "--- Pass 2: fast/medium benchmarks ---" | tee -a "$results_file"
    (cd "$worktree" && java -jar regex-bench/target/benchmarks.jar \
        "SearchBenchmark" \
        -e "SearchBenchmark.*(charClass|alternation)" \
        -f 3 -wi 5 -w 5s -i 5 -r 5s) \
        2>&1 | tee -a "$results_file"

    # Clean up worktree
    git worktree remove --force "$worktree"

    echo ""
    echo "  Finished $stage at $(date)"
    echo "  Results: $results_file"
    echo ""
done

echo ""
echo "================================================================"
echo "  All stages complete at $(date)"
echo "  Results in $RESULTS_DIR/"
echo "================================================================"
