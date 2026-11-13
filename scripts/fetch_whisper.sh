#!/usr/bin/env bash
# whisper.cpp 소스를 app/src/main/cpp/whisper.cpp 에 받는다. (Android 앱 빌드 전 1회 실행)
set -euo pipefail
VERSION="${WHISPER_CPP_VERSION:-v1.8.7}"
DEST="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/cpp/whisper.cpp"

if [ -f "$DEST/CMakeLists.txt" ]; then
  echo "이미 존재합니다: $DEST"
  exit 0
fi
git clone --depth 1 --branch "$VERSION" https://github.com/ggml-org/whisper.cpp "$DEST"
echo "whisper.cpp $VERSION -> $DEST"
