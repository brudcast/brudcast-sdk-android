#!/usr/bin/env bash
# Checks that sdkVersion in gradle.properties, the newest released section of CHANGELOG.md and the
# BOM version in the README's install section all name the same version, so a release cannot be
# tagged with a stale changelog or install line.
#
# Usage: scripts/check-version.sh
set -euo pipefail

version="$(grep '^sdkVersion=' gradle.properties | cut -d= -f2)"
changelog="$(sed -n 's/^## \[\([0-9][^]]*\)\] - .*/\1/p' CHANGELOG.md | head -n 1)"
readme="$(sed -n 's/.*com\.brudcast:sdk-bom:\([^")]*\).*/\1/p' README.md | head -n 1)"
mismatch=0

check() {
  if [ "$2" != "$version" ]; then
    echo "::error::$1 names ${2:-no version}, but sdkVersion in gradle.properties is $version"
    mismatch=1
  fi
}

check "The newest released section of CHANGELOG.md" "$changelog"
check "The README's install section" "$readme"

if [ "$mismatch" -ne 0 ]; then
  echo "Bump them together in the release pull request. See RELEASING.md."
  exit 1
fi
echo "sdkVersion, CHANGELOG.md and README.md all name $version"
