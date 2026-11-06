#!/usr/bin/env bash
# Fetches the contract files this repository tests against from the Content repository, the only home of
# every cross-repository contract (owner decision 2026-11-06). Read-only: the files land in a git-ignored
# folder and are never committed here.
#
#   scripts/fetch-contracts.sh     docs/contracts/domain-api-v1.json and docs/contracts/release-layout.json
#                                  from Content main into .contracts/ (DomainProviderContractTest,
#                                  ReleaseLayoutContractTest read them; -Dcontracts.dir=<folder> points elsewhere)
#
# Authentication, in this order: CONTENT_READ_TOKEN (CI secret: a fine-grained token with Contents: read on
# lookahead99ms/lookahead-learning-content alone), GH_TOKEN, or the developer's own `gh auth login`. The token is
# handed to curl through a config on stdin, so it is never on a command line and never printed.
#
#   CONTRACTS_DIR   where the files land (default: <repository>/.contracts)
#   CONTENT_REF     the Content ref to read (default: main); only for a local check against an unmerged Content branch
set -euo pipefail
set +x  # a traced shell (bash -x) would otherwise echo the token

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPOSITORY="lookahead99ms/lookahead-learning-content"
FOLDER="docs/contracts"
FILES="domain-api-v1.json release-layout.json"
CONTRACTS_DIR="${CONTRACTS_DIR:-$ROOT/.contracts}"
CONTENT_REF="${CONTENT_REF:-main}"
FIX="Fix: set CONTENT_READ_TOKEN (the CI secret: a fine-grained token with Contents: read on $REPOSITORY; a workflow that calls ci.yml passes it with 'secrets: inherit') or GH_TOKEN, or run 'gh auth login' as an account that can read $REPOSITORY, then run scripts/fetch-contracts.sh again."

fail() { echo "fetch-contracts: $1" >&2; echo "$FIX" >&2; exit 1; }

TOKEN="${CONTENT_READ_TOKEN:-${GH_TOKEN:-}}"
if [ -z "$TOKEN" ] && ! command -v gh >/dev/null 2>&1; then
  fail "neither CONTENT_READ_TOKEN nor GH_TOKEN is set and there is no 'gh' command for a developer login; nothing fetched."
fi
[ -n "$TOKEN" ] && how="token" || how="gh login"

mkdir -p "$CONTRACTS_DIR"
for file in $FILES; do
  path="$FOLDER/$file"
  target="$CONTRACTS_DIR/$file"
  partial="$target.part"
  rm -f "$partial"
  if [ -n "$TOKEN" ]; then
    status="$(printf 'header = "Authorization: Bearer %s"\n' "$TOKEN" | curl --silent --show-error --config - \
      --header 'Accept: application/vnd.github.raw+json' --header 'X-GitHub-Api-Version: 2022-11-28' \
      --output "$partial" --write-out '%{http_code}' \
      "https://api.github.com/repos/$REPOSITORY/contents/$path?ref=$CONTENT_REF")" && status="HTTP $status" || status="curl failed"
  elif gh api --header 'Accept: application/vnd.github.raw+json' "repos/$REPOSITORY/contents/$path?ref=$CONTENT_REF" > "$partial"; then
    status="HTTP 200"
  else
    status="gh api failed, see its message above"
  fi
  if [ "$status" != "HTTP 200" ]; then
    rm -f "$partial"
    fail "could not read $REPOSITORY/$path at ref '$CONTENT_REF' with $how ($status)."
  fi
  if [ ! -s "$partial" ] || [ "$(head -c 1 "$partial")" != "{" ]; then
    rm -f "$partial"
    fail "$REPOSITORY/$path at ref '$CONTENT_REF' did not come back as a JSON object; check the path on Content."
  fi
  mv "$partial" "$target"
  echo "fetch-contracts: $path@$CONTENT_REF -> $target ($(wc -c < "$target" | tr -d ' ') bytes)"
done
