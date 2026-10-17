#!/usr/bin/env bash
# Checks a local publication of the SDK: every file Maven Central needs, for every artifact, and
# that the artifacts depend on each other by coordinates rather than project paths. With --signed
# it also requires a signature and checksums for every file, as the Central Portal does.
#
# Usage: scripts/check-publication.sh [--signed] [repository, ~/.m2/repository by default]
set -euo pipefail

signed=false
if [ "${1:-}" = "--signed" ]; then
  signed=true
  shift
fi

repository="${1:-$HOME/.m2/repository}"
version="$(grep '^sdkVersion=' gradle.properties | cut -d= -f2)"
base="$repository/com/brudcast"
missing=0

expect() {
  if [ ! -s "$1" ]; then
    echo "::error::Missing ${1#"$repository"/}"
    missing=1
  fi
}

expect_file() {
  expect "$1"
  if [ "$signed" = true ]; then
    for suffix in .asc .md5 .sha1; do
      expect "$1$suffix"
    done
  fi
}

for module in sdk-core sdk-inapp sdk-push sdk-push-firebase; do
  for suffix in .aar .pom .module -sources.jar -javadoc.jar; do
    expect_file "$base/$module/$version/$module-$version$suffix"
  done
done

for suffix in .pom .module; do
  expect_file "$base/sdk-bom/$version/sdk-bom-$version$suffix"
done

if [ "$missing" -eq 0 ]; then
  for pair in sdk-inapp:sdk-core sdk-push:sdk-core sdk-push-firebase:sdk-push; do
    module="${pair%%:*}"
    dependency="${pair##*:}"
    if ! grep -q "<artifactId>$dependency</artifactId>" "$base/$module/$version/$module-$version.pom"; then
      echo "::error::$module $version does not depend on com.brudcast:$dependency"
      missing=1
    fi
  done
fi

if [ "$missing" -ne 0 ]; then
  exit 1
fi

if [ "$signed" = true ]; then
  echo "All five artifacts are complete, signed and checksummed at $version."
else
  echo "All five artifacts are complete at $version."
fi
