#!/bin/bash
# Stop hook: refuse to finish a turn while the living docs trail the code.
#
# The rule already existed in EXECUTION_PLAN.md and was still forgotten, because
# nothing forced it to be read. This checks it mechanically instead.
#
# Fires only when commits touching app/ or .github/ are newer than the last
# commit touching PROGRESS.md or JARVIS_MEMORY.md — so a pure discussion turn,
# or a docs-only turn, never trips it.

input=$(cat)

# Recursion guard: if we already blocked once, let the turn end.
if [[ "$(echo "$input" | jq -r '.stop_hook_active' 2>/dev/null)" == "true" ]]; then
  exit 0
fi

cd "${CLAUDE_PROJECT_DIR:-.}" 2>/dev/null || exit 0
git rev-parse --git-dir >/dev/null 2>&1 || exit 0

code_ts=$(git log -1 --format=%ct -- app .github 2>/dev/null)
docs_ts=$(git log -1 --format=%ct -- PROGRESS.md JARVIS_MEMORY.md 2>/dev/null)

# No code history yet, or no docs yet — nothing to compare.
[[ -z "$code_ts" ]] && exit 0
[[ -z "$docs_ts" ]] && docs_ts=0

if (( code_ts > docs_ts )); then
  docs_commit=$(git log -1 --format=%H -- PROGRESS.md JARVIS_MEMORY.md 2>/dev/null)
  if [[ -n "$docs_commit" ]]; then
    behind=$(git log --oneline "$docs_commit"..HEAD -- app .github 2>/dev/null | wc -l | tr -d ' ')
    subjects=$(git log --format='  - %s' "$docs_commit"..HEAD -- app .github 2>/dev/null | head -10)
  else
    behind=$(git log --oneline -- app .github 2>/dev/null | wc -l | tr -d ' ')
    subjects=$(git log --format='  - %s' -- app .github 2>/dev/null | head -10)
  fi

  cat >&2 <<EOF
The project docs are behind the code by ${behind} commit(s). See CLAUDE.md Rule 1.

Code commits not yet reflected in the docs:
${subjects}

Before finishing, update:
  - PROGRESS.md      snapshot line (branch, main @ sha, last green build, date) + what shipped / what is still open
  - JARVIS_MEMORY.md a dated entry: what was built, WHY, and what the evidence showed
  - SESSION_HANDOFF.md  "Current position", plus any new hard-won gotcha

Then commit them. Do not disable or work around this check.
EOF
  exit 2
fi

exit 0
