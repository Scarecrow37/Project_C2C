#!/usr/bin/env bash
# 네이티브 의존성(whisper.cpp, llama.cpp) 소스를 받는다. Android 앱 빌드 전 1회 실행.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

fetch() { # <repo> <tag> <dest>
  if [ -f "$3/CMakeLists.txt" ]; then
    echo "이미 존재합니다: $3"
  else
    git clone --depth 1 --branch "$2" "https://github.com/ggml-org/$1" "$3"
    echo "$1 $2 -> $3"
  fi
}

fetch whisper.cpp "${WHISPER_CPP_VERSION:-v1.8.7}" "$ROOT/app/src/main/cpp/whisper.cpp"
# llama.cpp 는 C API 가 자주 바뀌므로 llama_jni.cpp 를 확인한 버전으로 고정한다.
fetch llama.cpp "${LLAMA_CPP_VERSION:-b12352}" "$ROOT/llm/src/main/cpp/llama.cpp"
