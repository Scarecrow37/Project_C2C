cmake_minimum_required(VERSION 3.22.1)
project(c2c_llama CXX C)

set(CMAKE_CXX_STANDARD 17)

set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp)
if (NOT EXISTS ${LLAMA_DIR}/CMakeLists.txt)
    message(FATAL_ERROR "llama.cpp 소스가 없습니다. 프로젝트 루트에서 scripts/fetch_native.sh 를 실행하세요.")
endif ()

# llama.cpp 를 정적 라이브러리로 함께 빌드한다. 라이브러리·도구·서버·테스트는 빼고 엔진만.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
# 최신 기기(ARMv8.2 이상, 2019년 이후 대부분)에서 더 빠르게 하려면 아래 줄의 주석을 푼다.
# 단, dotprod 를 지원하지 않는 구형 기기에서는 앱이 종료된다.
# set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod" CACHE STRING "" FORCE)
add_subdirectory(${LLAMA_DIR} llama-build)

add_library(c2c_llama SHARED llama_jni.cpp)
target_link_libraries(c2c_llama PRIVATE llama android log)
# whisper.cpp(c2c_whisper)도 자체 ggml 을 정적으로 포함한다. 두 라이브러리의 ggml 심볼이 서로 섞이지 않도록 숨긴다.
target_link_options(c2c_llama PRIVATE "-Wl,--exclude-libs,ALL")
