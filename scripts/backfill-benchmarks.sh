#!/bin/bash
# Backfill benchmark data for historical stages with proper JMH parameters.
# Run this overnight — takes ~8 hours sequential.
#
# Usage: ./scripts/backfill-benchmarks.sh
#
# Output goes to docs/benchmarks/stage-XX-benchmarks.txt

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

    # Clean up any leftover worktree
    if [ -d "$worktree" ]; then
        git worktree remove --force "$worktree" 2>/dev/null || true
    fi

    # Create worktree at the stage tag
    git worktree add "$worktree" "$stage"
    (cd "$worktree" && git submodule update --init)

    # Copy the updated benchmark class with proper parameters
    # (historical stages have the old @Fork(1) / 2s iteration settings)
    cp regex-bench/src/main/java/lol/ohai/regex/bench/SearchBenchmark.java \
       "$worktree/regex-bench/src/main/java/lol/ohai/regex/bench/SearchBenchmark.java"

    # Build and run
    (cd "$worktree" && ./mvnw -P bench package -DskipTests -q)
    (cd "$worktree" && java -jar regex-bench/target/benchmarks.jar) \
        | tee "$RESULTS_DIR/$stage-benchmarks.txt"

    # Clean up worktree
    git worktree remove --force "$worktree"

    echo ""
    echo "  Finished $stage at $(date)"
    echo ""
done

echo ""
echo "================================================================"
echo "  All stages complete at $(date)"
echo "  Results in $RESULTS_DIR/"
echo "================================================================"
