#!/usr/bin/env bash
# Claude Code PreToolUse hook: runs lint checks before git commit
# Reads tool input JSON from stdin. Exit 2 = block, exit 0 = allow.

set -euo pipefail

# Read the tool input from stdin
INPUT=$(cat)

# Only run on git commit commands - extract the command field
COMMAND=$(echo "$INPUT" | python -c "import sys,json; print(json.load(sys.stdin).get('input',{}).get('command',''))" 2>/dev/null || echo "")

# Skip if not a git commit
if ! echo "$COMMAND" | grep -qE '^git commit'; then
  exit 0
fi

PROJECT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
ERRORS=()

# Get list of staged files
STAGED=$(git -C "$PROJECT_DIR" diff --cached --name-only --diff-filter=ACM 2>/dev/null || true)

if [ -z "$STAGED" ]; then
  exit 0
fi

# Check for Python changes → run ruff
if echo "$STAGED" | grep -qE '\.py$'; then
  echo "Running ruff on staged Python files..." >&2
  PY_FILES=$(echo "$STAGED" | grep -E '\.py$' | sed "s|^|$PROJECT_DIR/|")
  if ! python -m ruff check $PY_FILES 2>&1 >&2; then
    ERRORS+=("ruff lint failed")
  fi
fi

# Check for site/ TypeScript changes → run eslint + typecheck
if echo "$STAGED" | grep -qE '^site/src/.*\.(ts|tsx|js|jsx)$'; then
  echo "Running ESLint on site/..." >&2
  if ! npx --prefix "$PROJECT_DIR/site" eslint --no-warn-ignored "$PROJECT_DIR/site/src" 2>&1 >&2; then
    ERRORS+=("site ESLint failed")
  fi

  echo "Running TypeScript check on site/..." >&2
  if ! npx --prefix "$PROJECT_DIR/site" tsc --noEmit 2>&1 >&2; then
    ERRORS+=("site TypeScript check failed")
  fi
fi

# Check for dj_client/ TypeScript changes → run typecheck
if echo "$STAGED" | grep -qE '^dj_client/src/.*\.(ts|tsx)$'; then
  echo "Running TypeScript check on dj_client/..." >&2
  if ! npx --prefix "$PROJECT_DIR/dj_client" tsc --noEmit 2>&1 >&2; then
    ERRORS+=("dj_client TypeScript check failed")
  fi
fi

# Report results
if [ ${#ERRORS[@]} -gt 0 ]; then
  echo "" >&2
  echo "Pre-commit checks FAILED:" >&2
  for err in "${ERRORS[@]}"; do
    echo "  - $err" >&2
  done
  echo "" >&2
  echo "Fix the issues above before committing." >&2
  exit 2
fi

echo "All pre-commit checks passed." >&2
exit 0
