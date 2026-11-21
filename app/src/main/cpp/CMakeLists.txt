cmake_minimum_required(VERSION 3.22.1)
project(ume_llama C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

# Debug builds of llama.cpp are far too slow for on-device inference; keep native optimized.
set(CMAKE_C_FLAGS_DEBUG "-O2 -g -DNDEBUG")
set(CMAKE_CXX_FLAGS_DEBUG "-O2 -g -DNDEBUG")

if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "Ume ships llama.cpp for arm64-v8a only")
endif()

# Follow the M3 build rules: no server/tools/examples, no curl, no llamafile.
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_IS_DEV OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_HTML OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI OFF CACHE BOOL "" FORCE)
set(LLAMA_USE_PREBUILT_UI OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD OFF CACHE BOOL "" FORCE)

# One ARMv8.2-A CPU backend with dot-product + NEON for the Snapdragon target.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod" CACHE STRING "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp EXCLUDE_FROM_ALL)

add_library(llama_jni SHARED llama_jni.cpp)
target_include_directories(llama_jni PRIVATE
        llama.cpp/include
        llama.cpp/ggml/include)
target_compile_options(llama_jni PRIVATE -Wno-deprecated-declarations)
target_link_libraries(llama_jni PRIVATE llama ggml android log)
