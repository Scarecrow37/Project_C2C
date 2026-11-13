#!/usr/bin/env bash
# 이전 이름과의 호환용. scripts/fetch_native.sh 가 whisper.cpp 와 llama.cpp 를 함께 받는다.
exec "$(dirname "$0")/fetch_native.sh" "$@"
