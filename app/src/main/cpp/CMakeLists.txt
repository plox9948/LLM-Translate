cmake_minimum_required(VERSION 3.22.1)
project(llmslimt_jni CXX C)

# Численное ядро slimt в отладочной сборке APK неприемлемо медленно,
# поэтому тип сборки по умолчанию — Release. Без FORCE внешний
# -DCMAKE_BUILD_TYPE=Debug (например при отладке JNI через lldb) имеет
# приоритет командной строки и не перезатирается этим значением.
set(CMAKE_BUILD_TYPE Release CACHE STRING "Тип сборки C++ (по умолчанию Release)")
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Параметры сборки slimt (Firefox Translations engine)
set(WITH_TESTS OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(BUILD_STATIC ON CACHE BOOL "" FORCE)
set(USE_BUILTIN_SENTENCEPIECE ON CACHE BOOL "" FORCE)
set(SPM_ENABLE_TCMALLOC OFF CACHE BOOL "" FORCE)
set(SPM_ENABLE_SHARED OFF CACHE BOOL "" FORCE)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(USE_NEON ON CACHE BOOL "" FORCE)
    add_compile_definitions(USE_NEON)
endif()
if(ANDROID_ABI STREQUAL "x86_64")
    add_compile_definitions(SLIMT_HAS_RUY)
endif()

set(SLIMT_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../third_party/slimt)

# sentencepiece (встроенный, protobuf-lite) + ruy
set(SPM_USE_BUILTIN_PROTOBUF ON)
set(CMAKE_POLICY_DEFAULT_CMP0077 NEW)
add_subdirectory(${SLIMT_ROOT}/3rd-party ${CMAKE_CURRENT_BINARY_DIR}/3rd-party)

# Консольные утилиты sentencepiece (spm_*) в Android-сборке линкуются без
# liblog, а protobuf-lite на Android вызывает __android_log_write —
# добавляем log только этим исполняемым целям (в APK они не попадают).
if(ANDROID)
    foreach(_spm_target spm_encode spm_decode spm_normalize spm_train spm_export_vocab)
        if(TARGET ${_spm_target})
            target_link_libraries(${_spm_target} log)
        endif()
    endforeach()
endif()

# Переменные, которые slimt/CMakeLists.txt ожидает из корня проекта slimt.
set(SLIMT_COMPILE_OPTIONS
    -Wall
    -Wextra
    -Wno-unknown-pragmas
    -Wno-missing-field-initializers
    -Wno-unused-function
    -Wextra-semi
    # Детерминированность чисел между ABI: ядро slimt тоже компилируется
    # без FMA-контракта (см. комментарий к цели slimt_jni ниже).
    -ffp-contract=off)
set(SLIMT_PUBLIC_LIBS SentencePiece::SentencePiece)
set(SLIMT_INTERFACE_LIBS "")
set(SLIMT_PRIVATE_LIBS ruy)
set(SLIMT_COMPILE_DEFINITIONS SLIMT_HAS_RUY)
set(SLIMT_LINK_OPTIONS "")

add_subdirectory(${SLIMT_ROOT}/slimt ${CMAKE_CURRENT_BINARY_DIR}/slimt)

# В subdirectory-режиме публичные include-пути slimt (CMAKE_SOURCE_DIR /
# CMAKE_BINARY_DIR) указывают не туда — добавляем корректные;
# несуществующие пути компилятор просто игнорирует.
target_include_directories(slimt-static PRIVATE
    ${SLIMT_ROOT}
    ${CMAKE_CURRENT_BINARY_DIR})

# Детерминированность между ABI: без -ffp-contract=off ARM иначе fusionит FMA,
# что на int8-квантовании расходится с x86 (см. build.rs slimt-sys).
add_library(slimt_jni SHARED slimt_jni.cpp)
target_compile_options(slimt_jni PRIVATE -ffp-contract=off)
target_include_directories(slimt_jni PRIVATE
    ${SLIMT_ROOT}
    ${CMAKE_CURRENT_BINARY_DIR})
target_link_libraries(slimt_jni PRIVATE slimt-static android log)
