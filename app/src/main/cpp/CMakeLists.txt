# Нативные мосты для llama.cpp (LLM) и whisper.cpp (STT).
# Зависимости подключаются как git-субмодули:
#   external/llama.cpp    (llama.cpp v0.5.0+ dev, ggml 0.25.3 внутри subtree ggml/)
#   external/whisper.cpp  (whisper.cpp v1.7.6, собственный ggml в external/whisper.cpp/ggml)
cmake_minimum_required(VERSION 3.22)
project("llm_translate_native" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/external/llama.cpp)
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/external/whisper.cpp)

if(NOT EXISTS ${LLAMA_DIR}/CMakeLists.txt OR NOT EXISTS ${WHISPER_DIR}/CMakeLists.txt)
    message(WARNING "Субмодули llama.cpp/whisper.cpp не инициализированы. Выполните: "
                    "git submodule update --init --recursive")
endif()

# ============================================================================
# Почему нельзя просто сделать add_subdirectory() для обоих субпроектов.
#
# Имя CMake-цели уникально ГЛОБАЛЬНО во всей сборке. Оба субмодуля определяют
# цели с одинаковыми именами: ggml, ggml-base, ggml-cpu (см. их
# ggml/src/CMakeLists.txt), а whisper.cpp в верхнем CMakeLists имеет guard
# "if (NOT TARGET ggml) add_subdirectory(ggml) endif()" - т.е. при втором
# add_subdirectory() он не создаст СВОЙ ggml, а переиспользует чужой (ggml
# 0.25.x из llama.cpp) для компиляции whisper v1.7.6, рассчитанного на ggml
# 0.8.x. Это риск несовместимости API на этапе компиляции/линковки.
# Повторное включение одного и того же ggml дважды через add_subdirectory с
# разными BINARY_DIR тоже ha-zard: алиас ggml::ggml объявляется повторно.
#
# Решение (минимально надёжное):
#   - llama_bridge: llama.cpp целиком через add_subdirectory (llama + его ggml
#     одной версии, статически линкуются в наш SHARED-объект);
#   - whisper_bridge: исходники whisper v1.7.6 и ЕГО ggml компилируются
#     напрямую в наш SHARED-объект (без создания целей ggml*), поэтому цели
#     не конфликтуют, а символы каждого ggml остаются внутри своего .so.
#     Каждый .so самодостаточен (Kotlin грузит их независимо System.loadLibrary).
# Это тот же подход, что использует официальный пример
# external/whisper.cpp/examples/whisper.android/lib/src/main/jni:
# whisper.cpp компилируется прямо в SHARED-библиотеку, GGML_USE_CPU задается.
# ============================================================================

# --- llama.cpp: минимальная сборка библиотек (кэш-переменные ДО add_subdirectory) ---
set(BUILD_SHARED_LIBS       OFF CACHE BOOL "" FORCE)  # статические llama/ggml линкуем в SHARED llama_bridge
set(LLAMA_BUILD_COMMON      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS       OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS       OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP         OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI          OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD        OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL           OFF CACHE BOOL "" FORCE)  # net - только загрузка моделей из файлов
set(LLAMA_SUBPROCESS        OFF CACHE BOOL "" FORCE)  # не для мобильных ОС
set(LLAMA_FATAL_WARNINGS    OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP             OFF CACHE BOOL "" FORCE)  # NDK clang: OpenMP ненадёжен
set(GGML_CCACHE             OFF CACHE BOOL "" FORCE)
set(GGML_LTO                OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE          OFF CACHE BOOL "" FORCE)  # sgemm требует спец.-арх флагов (+dotprod);
                                                      # на NDK базовый march=armv8-a их не включает
set(GGML_CPU_ALL_VARIANTS   OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL         OFF CACHE BOOL "" FORCE)
set(GGML_BLAS               OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE             OFF CACHE BOOL "" FORCE)  # кросс-компиляция: без -march=native
set(GGML_FATAL_WARNINGS     OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_DIR} ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp EXCLUDE_FROM_ALL)

add_library(llama_bridge SHARED
    llama_bridge.cpp
)

# Цели llama (PUBLIC) и ggml/ggml-base/ggml-cpu подтягиваются транзитивно
# вместе с публичными include-путями (include/llama.h -> ggml/include).
target_link_libraries(llama_bridge PRIVATE llama)

# --- whisper.cpp v1.7.6: компилируем исходники напрямую в whisper_bridge ---
set(WHISPER_SRC ${WHISPER_DIR}/src)
set(WHISPER_GGML_SRC ${WHISPER_DIR}/ggml/src)

set(WHISPER_BRIDGE_SOURCES
    whisper_bridge.cpp
    # whisper core (src/CMakeLists.txt: whisper.cpp; coreml/openvino отключены)
    ${WHISPER_SRC}/whisper.cpp
    # ggml-base (whisper ggml/src/CMakeLists.txt)
    ${WHISPER_GGML_SRC}/ggml.c
    ${WHISPER_GGML_SRC}/ggml.cpp
    ${WHISPER_GGML_SRC}/ggml-alloc.c
    ${WHISPER_GGML_SRC}/ggml-backend.cpp
    ${WHISPER_GGML_SRC}/ggml-opt.cpp
    ${WHISPER_GGML_SRC}/ggml-threading.cpp
    ${WHISPER_GGML_SRC}/ggml-quants.c
    ${WHISPER_GGML_SRC}/gguf.cpp
    # регистрация бэкендов (цель ggml)
    ${WHISPER_GGML_SRC}/ggml-backend-reg.cpp
    # CPU-бэкенд (ggml_add_cpu_backend_variant_impl, без llamafile/kleidiai/amx-host)
    ${WHISPER_GGML_SRC}/ggml-cpu/ggml-cpu.c
    ${WHISPER_GGML_SRC}/ggml-cpu/ggml-cpu.cpp
    ${WHISPER_GGML_SRC}/ggml-cpu/repack.cpp
    ${WHISPER_GGML_SRC}/ggml-cpu/hbm.cpp
    ${WHISPER_GGML_SRC}/ggml-cpu/quants.c
    ${WHISPER_GGML_SRC}/ggml-cpu/traits.cpp
    ${WHISPER_GGML_SRC}/ggml-cpu/amx/amx.cpp
    ${WHISPER_GGML_SRC}/ggml-cpu/amx/mmq.cpp
    ${WHISPER_GGML_SRC}/ggml-cpu/binary-ops.cpp
    ${WHISPER_GGML_SRC}/ggml-cpu/unary-ops.cpp
    ${WHISPER_GGML_SRC}/ggml-cpu/vec.cpp
    ${WHISPER_GGML_SRC}/ggml-cpu/ops.cpp
)

if(CMAKE_SYSTEM_PROCESSOR MATCHES "^(aarch64|arm64|ARM64)$")
    list(APPEND WHISPER_BRIDGE_SOURCES
        ${WHISPER_GGML_SRC}/ggml-cpu/arch/arm/quants.c
        ${WHISPER_GGML_SRC}/ggml-cpu/arch/arm/repack.cpp
    )
elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "^(x86_64|AMD64)$")
    list(APPEND WHISPER_BRIDGE_SOURCES
        ${WHISPER_GGML_SRC}/ggml-cpu/arch/x86/quants.c
        ${WHISPER_GGML_SRC}/ggml-cpu/arch/x86/repack.cpp
    )
endif()

add_library(whisper_bridge SHARED ${WHISPER_BRIDGE_SOURCES})

# Пути включов - по образцу whisper.cpp/examples/whisper.android/lib/src/main/jni/CMakeLists.txt
target_include_directories(whisper_bridge PRIVATE
    ${WHISPER_DIR}/include
    ${WHISPER_SRC}
    ${WHISPER_DIR}/ggml/include
    ${WHISPER_DIR}/ggml/src
    ${WHISPER_DIR}/ggml/src/ggml-cpu
)

# GGML_USE_CPU - статическая регистрация CPU-бэкенда в ggml-backend-reg.cpp
# (как в официальном android-примере whisper). GGML_USE_CPU_REPACK соответствует
# включению repack.cpp (default GGML_CPU_REPACK=ON в whisper ggml).
target_compile_definitions(whisper_bridge PRIVATE
    GGML_USE_CPU
    GGML_USE_CPU_REPACK
)

if(CMAKE_SYSTEM_NAME STREQUAL "Android")
    # ggml-base в субмодуле компилируется с _GNU_SOURCE для Linux/Android
    target_compile_definitions(whisper_bridge PRIVATE _GNU_SOURCE)
endif()

find_library(log-lib log)

target_link_libraries(llama_bridge
    android
    ${log-lib}
)

target_link_libraries(whisper_bridge
    android
    ${log-lib}
)
