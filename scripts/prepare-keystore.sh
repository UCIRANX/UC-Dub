#!/usr/bin/env bash
# Ensures a release keystore exists and exports signing variables for Gradle.
# - If KEYSTORE_BASE64 + KEYSTORE_PASSWORD secrets exist: reuse them (keeps updates installable).
# - Otherwise: generate a new keystore, and save it to repo secrets when GH_TOKEN (PAT) is provided.
set -euo pipefail

KS_DIR="$RUNNER_TEMP/keystore"
KS_PATH="$KS_DIR/release.jks"
ALIAS="ucdub"
mkdir -p "$KS_DIR"

if [ -n "${KS_B64:-}" ] && [ -n "${KS_PASS:-}" ]; then
  echo "$KS_B64" | base64 -d > "$KS_PATH"
  echo "Using keystore from repository secrets."
else
  echo "No stored keystore found. Generating a new one."
  KS_PASS="$(openssl rand -hex 24)"
  keytool -genkeypair -v \
    -keystore "$KS_PATH" -storetype PKCS12 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -alias "$ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=UC Dub, O=UC Dub, C=IR"

  if [ -n "${GH_TOKEN:-}" ]; then
    base64 -w0 "$KS_PATH" | gh secret set KEYSTORE_BASE64 --repo "$GITHUB_REPOSITORY"
    printf '%s' "$KS_PASS" | gh secret set KEYSTORE_PASSWORD --repo "$GITHUB_REPOSITORY"
    echo "Keystore and password saved to repository secrets."
  else
    echo "::warning::GH_PAT secret is not set, so the new keystore was NOT saved. It is attached as an artifact. Store it safely; without it, future updates cannot install over this version."
    mkdir -p "$RUNNER_TEMP/keystore-backup"
    cp "$KS_PATH" "$RUNNER_TEMP/keystore-backup/release.jks"
    echo "KEYSTORE_NEEDS_DOWNLOAD=true" >> "$GITHUB_ENV"
  fi
fi

echo "::add-mask::$KS_PASS"
{
  echo "KEYSTORE_PATH=$KS_PATH"
  echo "KEYSTORE_PASSWORD=$KS_PASS"
  echo "KEY_PASSWORD=$KS_PASS"
  echo "KEY_ALIAS=$ALIAS"
} >> "$GITHUB_ENV"
