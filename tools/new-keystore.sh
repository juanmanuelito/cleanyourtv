#!/usr/bin/env bash
# Generate this fork's release signing keystore and keystore.properties, once, out of git.
# Run from the repo root. The keystore and properties file are the only copy: back them up.
# Losing them means a full uninstall/reinstall of the launcher (see docs/SECURITY-SIGNING.md).
set -euo pipefail
umask 077
cd "$(dirname "$0")/.."
KS=launcher/cleanyourtv-release.keystore
PROPS=launcher/keystore.properties
if [ -e "$KS" ] || [ -e "$PROPS" ]; then
  echo "refusing: $KS or $PROPS already exists (delete them deliberately to rotate the key)" >&2
  exit 1
fi
command -v keytool >/dev/null 2>&1 || { echo "keytool not found: install a JDK" >&2; exit 1; }
PASS="$(head -c 32 /dev/urandom | base64 | tr -d '\n/+=' | cut -c1-32)"
ALIAS=cleanyourtv
keytool -genkeypair -v \
  -keystore "$KS" -storepass "$PASS" -keypass "$PASS" \
  -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=cleanyourtv, OU=cleanyourtv, O=cleanyourtv, C=US" >/dev/null
cat > "$PROPS" <<EOF
storeFile=cleanyourtv-release.keystore
storePassword=$PASS
keyAlias=$ALIAS
keyPassword=$PASS
EOF
chmod 600 "$KS" "$PROPS"
echo "wrote $KS and $PROPS (both gitignored). Back them up now; losing them means a full launcher reinstall."
