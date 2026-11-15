#!/usr/bin/env bash
# Downloads the public ATAK-CIV SDK from GitHub (no tak.gov account needed) and
# prepares this project to build against it. Used by CI and for local builds:
#
#   ./setup-sdk.sh && ./gradlew assembleCivDebug
#
# Needs: curl, unzip, JDK 11, and an Android SDK (ANDROID_HOME or ANDROID_SDK_ROOT).
set -euo pipefail
cd "$(dirname "$0")"

ATAK_SDK_VERSION="${ATAK_SDK_VERSION:-4.6.0.5}"
URL="https://github.com/deptofdefense/AndroidTacticalAssaultKit-CIV/releases/download/${ATAK_SDK_VERSION}/atak-civ-sdk-${ATAK_SDK_VERSION}.zip"

mkdir -p sdk
if [ ! -f sdk/main.jar ] || [ ! -f sdk/atak-gradle-takdev.jar ] || [ ! -f sdk/atak.apk ]; then
  echo ">> Downloading ATAK-CIV SDK ${ATAK_SDK_VERSION} (about 215 MB)..."
  curl -fSL --retry 3 -o sdk/sdk.zip "$URL"
  unzip -q -o -j sdk/sdk.zip \
    "atak-civ/main.jar" "atak-civ/atak-gradle-takdev.jar" "atak-civ/atak.apk" "atak-civ/VERSION.txt" -d sdk
  rm -f sdk/sdk.zip
fi
# The public SDK ships no signing key. The SDK build of ATAK does not check plugin
# signatures, so the project's own debug key is used (also where takdev looks for one).
cp debug-signing.jks sdk/android_keystore

ANDROID="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$ANDROID" ] && [ ! -f local.properties ]; then
  echo "!! Set ANDROID_HOME to your Android SDK directory and run again." >&2
  exit 1
fi

if [ ! -f local.properties ]; then
  KS="$(pwd)/debug-signing.jks"
  cat > local.properties <<PROPS
sdk.dir=${ANDROID}
sdk.path=$(pwd)/sdk
takdev.plugin=$(pwd)/sdk/atak-gradle-takdev.jar
takDebugKeyFile=${KS}
takDebugKeyFilePassword=tactical
takDebugKeyAlias=tacticalcomms
takDebugKeyPassword=tactical
takReleaseKeyFile=${KS}
takReleaseKeyFilePassword=tactical
takReleaseKeyAlias=tacticalcomms
takReleaseKeyPassword=tactical
PROPS
  echo ">> Wrote local.properties"
fi
echo ">> SDK ready: $(cat sdk/VERSION.txt 2>/dev/null || echo "$ATAK_SDK_VERSION")"
echo ">> ATAK developer build for the phones: $(pwd)/sdk/atak.apk"
