#!/bin/bash
# Builds LeamhApp and installs it as the SINGLE canonical /Applications/Layuv.app.
# Always removes any previous install first, so Launch Services never sees two
# bundles claiming com.afluffywaffle.layuv at once (which caused duplicate Dock
# icons / duplicate-instance file opens). Use this instead of running the app
# straight from Xcode DerivedData.
#
# Usage: macos_native/build_and_install.sh [Debug|Release]

set -euo pipefail

CONFIG="${1:-Release}"
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$PROJECT_DIR/.." && pwd)"
DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode-beta.app/Contents/Developer}"

# Only install to /Applications on a clean, committed tree — mid-work-in-progress
# builds should stay in DerivedData (use xcodebuild directly to just check that
# it compiles). This keeps /Applications/Layuv.app always pointing at a real
# commit, never at uncommitted or half-finished work.
if [[ -n "$(git -C "$REPO_DIR" status --porcelain --untracked-files=no)" ]]; then
  echo "error: working tree has uncommitted changes — commit first, then install." >&2
  echo "(to just check that it compiles without installing, run xcodebuild directly)" >&2
  exit 1
fi

pkill -f "/Applications/Layuv.app/Contents/MacOS/Layuv" 2>/dev/null || true
sleep 1

DEVELOPER_DIR="$DEVELOPER_DIR" xcodebuild \
  -project "$PROJECT_DIR/LeamhApp/LeamhApp.xcodeproj" \
  -scheme LeamhApp -configuration "$CONFIG" -sdk macosx build

DERIVED_APP=$(DEVELOPER_DIR="$DEVELOPER_DIR" xcodebuild \
  -project "$PROJECT_DIR/LeamhApp/LeamhApp.xcodeproj" \
  -scheme LeamhApp -configuration "$CONFIG" -sdk macosx \
  -showBuildSettings 2>/dev/null \
  | awk -F'= ' '/ BUILT_PRODUCTS_DIR /{print $2; exit}')/Layuv.app

rm -rf /Applications/Layuv.app
cp -R "$DERIVED_APP" /Applications/Layuv.app

# xcodebuild always leaves its own copy in DerivedData, which Launch Services
# also registers under the same bundle ID. LSMultipleInstancesProhibited is
# scoped per bundle PATH, not per bundle ID — so two registered paths with the
# same ID can still each get launched, causing duplicate-instance file opens
# even with that key set. Delete the DerivedData copy so /Applications is the
# only registration left.
rm -rf "$DERIVED_APP"

/System/Library/Frameworks/CoreServices.framework/Versions/A/Frameworks/LaunchServices.framework/Versions/A/Support/lsregister \
  -f -R -trusted /Applications/Layuv.app

echo "Installed $CONFIG build to /Applications/Layuv.app"
open /Applications/Layuv.app
