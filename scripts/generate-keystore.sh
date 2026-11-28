#!/usr/bin/env bash
set -euo pipefail

alias_name="${1:-tavernite}"
out="${2:-release.jks}"

if [[ -e "$out" ]]; then
    echo "Refusing to overwrite existing $out" >&2
    exit 1
fi

read -r -s -p "Keystore password: " store_pass
echo

keytool -genkeypair -v -keystore "$out" -alias "$alias_name" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$store_pass" -keypass "$store_pass" \
    -dname "CN=Tavernite, OU=Mobile, O=Tavernite, C=CN"

echo
echo "Keystore: $out (alias: $alias_name)"
echo "Base64 for the RELEASE_KEYSTORE_BASE64 secret:"
base64 < "$out" | tr -d '\n'
echo
echo
echo "Add these GitHub secrets:"
echo "  RELEASE_KEYSTORE_BASE64=<base64 above>"
echo "  RELEASE_STORE_PASSWORD=<keystore password>"
echo "  RELEASE_KEY_ALIAS=$alias_name"
echo "  RELEASE_KEY_PASSWORD=$store_pass"
echo
echo "Back up $out and the password. Losing them prevents in-place updates."
