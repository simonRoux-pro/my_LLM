cmake_minimum_required(VERSION 3.22.1)
project(myllm_llama CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# --- llama.cpp ------------------------------------------------------------
# Only the library is wanted. Everything that pulls in a network stack, a
# filesystem walker or a test harness stays off: it would not link against the
# NDK sysroot and it would bloat the APK.
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL        OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI       OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)

# The build host is x86_64 CI, the target is the phone, so feature detection
# from the host must be off and the ARM baseline set explicitly.
set(GGML_NATIVE          OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL      OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)

if (ANDROID_ABI STREQUAL "arm64-v8a")
    # Cortex-A715/A510 (Dimensity 8300) and every other ARMv9 core expose
    # dotprod, i8mm and fp16 arithmetic. These are what make Q4_K matmul fast.
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+i8mm+fp16" CACHE STRING "" FORCE)
endif()

add_subdirectory(llama.cpp EXCLUDE_FROM_ALL)

# --- JNI bridge -----------------------------------------------------------
add_library(myllm_llama SHARED llama_bridge.cpp)

target_link_libraries(myllm_llama
    PRIVATE
        llama
        ggml
        android
        log)

target_compile_options(myllm_llama PRIVATE -O3 -fvisibility=hidden)

# Shrink the shipped binary: llama.cpp exports a lot that the bridge never uses.
target_link_options(myllm_llama PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
