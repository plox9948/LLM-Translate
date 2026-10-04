# Нативные мосты для llama.cpp (LLM) и whisper.cpp (STT).
# Зависимости подключаются как git-субмодули:
#   external/llama.cpp    (https://github.com/ggml-org/llama.cpp)
#   external/whisper.cpp  (https://github.com/ggml-org/whisper.cpp)
cmake_minimum_required(VERSION 3.22.1)
project("llm_translate_native" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/external/llama.cpp)
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/external/whisper.cpp)

if(NOT EXISTS ${LLAMA_DIR}/llama.cpp OR NOT EXISTS ${WHISPER_DIR}/whisper.cpp)
    message(WARNING "Субмодули llama.cpp/whisper.cpp не инициализированы. Выполните: "
                    "git submodule update --init --recursive")
endif()

# --- llama.cpp ---
file(GLOB LLAMA_SOURCES
    ${LLAMA_DIR}/llama.cpp
    ${LLAMA_DIR}/ggml.c
    ${LLAMA_DIR}/ggml.cpp
    ${LLAMA_DIR}/ggml-alloc.c
    ${LLAMA_DIR}/ggml-backend*.c
    ${LLAMA_DIR}/ggml-backend*.cpp
    ${LLAMA_DIR}/ggml-*.c
    ${LLAMA_DIR}/ggml-*.cpp
    ${LLAMA_DIR}/gguf.c
)
# Исключаем Windows-специфику и тесты.
list(FILTER LLAMA_SOURCES EXCLUDE REGEX "ggml-vulkan|ggml-cuda|ggml-metal|ggml-sycl|ggml-kompute")

add_library(llama_bridge SHARED
    llama_bridge.cpp
    ${LLAMA_SOURCES}
)

target_include_directories(llama_bridge PRIVATE
    ${LLAMA_DIR}
    ${LLAMA_DIR}/include
)

target_compile_definitions(llama_bridge PRIVATE
    LLAMA_BUILD_NO_METAL
    LLAMA_BUILD_NO_CUDA
    LLAMA_BUILD_NO_VULKAN
    LLAMA_BUILD_NO_SYCL
    LLAMA_BUILD_NO_KOMPUTE
    LLAMA_BUILD_NO_RPC
    LLAMA_BUILD_NO_OMNIGPU
)

# --- whisper.cpp ---
file(GLOB WHISPER_SOURCES
    ${WHISPER_DIR}/whisper.cpp
    ${WHISPER_DIR}/ggml.c
    ${WHISPER_DIR}/ggml.cpp
    ${WHISPER_DIR}/ggml-alloc.c
    ${WHISPER_DIR}/ggml-backend*.c
    ${WHISPER_DIR}/ggml-backend*.cpp
    ${WHISPER_DIR}/ggml-*.c
    ${WHISPER_DIR}/ggml-*.cpp
    ${WHISPER_DIR}/gguf.c
)
list(FILTER WHISPER_SOURCES EXCLUDE REGEX "ggml-vulkan|ggml-cuda|ggml-metal|ggml-sycl|ggml-kompute")

add_library(whisper_bridge SHARED
    whisper_bridge.cpp
    ${WHISPER_SOURCES}
)

target_include_directories(whisper_bridge PRIVATE
    ${WHISPER_DIR}
    ${WHISPER_DIR}/include
)

target_compile_definitions(whisper_bridge PRIVATE
    GGML_BUILD_NO_METAL
    GGML_BUILD_NO_CUDA
    GGML_BUILD_NO_VULKAN
    GGML_BUILD_NO_SYCL
    GGML_BUILD_NO_KOMPUTE
    GGML_BUILD_NO_RPC
    GGML_BUILD_NO_OMNIGPU
)

find_library(log-lib log)

target_link_libraries(llama_bridge
    android
    ${log-lib}
)

target_link_libraries(whisper_bridge
    android
    ${log-lib}
)
