#!/usr/bin/env bash
# Uploads, checks, publishes and drops deployments on the Maven Central Portal's publisher API, one
# step per release stage. Reads the Portal user token from MAVEN_CENTRAL_USERNAME and
# MAVEN_CENTRAL_PASSWORD, and never prints it.
#
# Usage:
#   scripts/central-portal.sh upload <bundle.zip> <name>      prints the deployment id
#   scripts/central-portal.sh wait <id> <VALIDATED|PUBLISHED> [timeout seconds]
#   scripts/central-portal.sh publish <id>
#   scripts/central-portal.sh drop <id>
set -euo pipefail

api="${CENTRAL_PORTAL_API:-https://central.sonatype.com/api/v1/publisher}"
poll_seconds="${CENTRAL_PORTAL_POLL_SECONDS:-10}"

fail() {
  echo "::error::$1" >&2
  exit 1
}

[ -n "${MAVEN_CENTRAL_USERNAME:-}" ] || fail "MAVEN_CENTRAL_USERNAME is not set"
[ -n "${MAVEN_CENTRAL_PASSWORD:-}" ] || fail "MAVEN_CENTRAL_PASSWORD is not set"
token="$(printf '%s:%s' "$MAVEN_CENTRAL_USERNAME" "$MAVEN_CENTRAL_PASSWORD" | base64 | tr -d '\n')"

request() {
  curl --silent --show-error --fail-with-body --header "Authorization: Bearer $token" "$@"
}

upload() {
  local bundle="$1" name="$2"
  [ -s "$bundle" ] || fail "No bundle at $bundle"
  [[ "$name" =~ ^[A-Za-z0-9._-]+$ ]] || fail "Deployment names may only use letters, digits, '.', '_' and '-'"
  local id
  id="$(request --request POST --form "bundle=@$bundle" "$api/upload?name=$name&publishingType=USER_MANAGED")" \
    || fail "The Portal refused the upload: $id"
  [ -n "$id" ] || fail "The Portal accepted the upload but returned no deployment id"
  echo "$id"
}

wait_for() {
  local id="$1" target="$2" timeout="${3:-}"
  case "$target" in
    VALIDATED) timeout="${timeout:-900}" ;;
    PUBLISHED) timeout="${timeout:-1800}" ;;
    *) fail "Can only wait for VALIDATED or PUBLISHED, not $target" ;;
  esac

  local deadline=$((SECONDS + timeout)) status state
  while true; do
    status="$(request --request POST "$api/status?id=$id")" || fail "Could not read the status of deployment $id: $status"
    state="$(jq -r '.deploymentState' <<<"$status")"
    echo "Deployment $id is $state"

    if [ "$state" = "$target" ] || { [ "$target" = VALIDATED ] && [[ "$state" =~ ^(PUBLISHING|PUBLISHED)$ ]]; }; then
      return 0
    fi
    if [ "$state" = FAILED ]; then
      jq -r '.errors // {} | to_entries[] | "\(.key): \(.value | if type == "array" then join("; ") else tostring end)"' <<<"$status" >&2
      fail "Deployment $id failed validation"
    fi
    if [ "$SECONDS" -ge "$deadline" ]; then
      fail "Deployment $id was still $state after $timeout seconds"
    fi
    sleep "$poll_seconds"
  done
}

publish() {
  local output
  output="$(request --request POST "$api/deployment/$1")" || fail "The Portal would not publish deployment $1: $output"
  echo "Deployment $1 is publishing to Maven Central"
}

drop() {
  local output
  output="$(request --request DELETE "$api/deployment/$1")" || fail "The Portal would not drop deployment $1: $output"
  echo "Dropped deployment $1"
}

command="${1:-}"
shift || true
case "$command" in
  upload) [ $# -eq 2 ] || fail "Usage: central-portal.sh upload <bundle.zip> <name>"; upload "$@" ;;
  wait) [ $# -ge 2 ] || fail "Usage: central-portal.sh wait <id> <VALIDATED|PUBLISHED> [timeout seconds]"; wait_for "$@" ;;
  publish) [ $# -eq 1 ] || fail "Usage: central-portal.sh publish <id>"; publish "$1" ;;
  drop) [ $# -eq 1 ] || fail "Usage: central-portal.sh drop <id>"; drop "$1" ;;
  *) fail "Usage: central-portal.sh <upload|wait|publish|drop> ..." ;;
esac
