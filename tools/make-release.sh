#!/usr/bin/env bash
# Build the release APK and write its SHA-256 next to it, for release verification.
# See SECURITY.md and docs/SECURITY-SIGNING.md.
set -euo pipefail
cd "$(dirname "$0")/../launcher"
./gradlew --no-daemon assembleRelease -q --console=plain
APK=app/build/outputs/apk/release/app-release.apk
SUMS=app/build/outputs/apk/release/sha256sums.txt
( cd "$(dirname "$APK")" && sha256sum "$(basename "$APK")" > sha256sums.txt )
cat "$SUMS"
echo "checksums written to $SUMS"
