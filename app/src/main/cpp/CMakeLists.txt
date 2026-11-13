cmake_minimum_required(VERSION 3.22.1)
project(c2c_whisper CXX C)

set(CMAKE_CXX_STANDARD 17)

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)
if (NOT EXISTS ${WHISPER_DIR}/CMakeLists.txt)
    message(FATAL_ERROR "whisper.cpp 소스가 없습니다. 프로젝트 루트에서 scripts/fetch_whisper.sh 를 실행하세요.")
endif ()

# whisper.cpp 를 정적 라이브러리로 함께 빌드한다.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
add_subdirectory(${WHISPER_DIR} whisper-build)

add_library(c2c_whisper SHARED whisper_jni.cpp)
target_link_libraries(c2c_whisper PRIVATE whisper android log)
