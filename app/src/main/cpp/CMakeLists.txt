cmake_minimum_required(VERSION 3.22.1)
project(hindidub CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# whisper.cpp
#   Point WHISPER_DIR at a local checkout for a pinned/offline build, otherwise
#   FetchContent clones it at configure time (needs network on first build).
# ---------------------------------------------------------------------------
# Vendored sources ship with this package, so the native build needs no network.
# Override either path to use your own checkout instead.
set(WHISPER_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../vendor/whisper.cpp"
    CACHE PATH "whisper.cpp checkout")
set(SENTENCEPIECE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../vendor/sentencepiece"
    CACHE PATH "sentencepiece checkout")

if(NOT EXISTS "${WHISPER_DIR}/CMakeLists.txt")
    message(STATUS "vendored whisper.cpp missing, falling back to FetchContent")
    include(FetchContent)
    set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    FetchContent_Declare(
        whisper
        GIT_REPOSITORY https://github.com/ggerganov/whisper.cpp.git
        GIT_TAG v1.7.4
        GIT_SHALLOW TRUE
    )
    FetchContent_MakeAvailable(whisper)
    set(WHISPER_INCLUDE_DIR ${whisper_SOURCE_DIR}/include)
else()
    add_subdirectory(${WHISPER_DIR} ${CMAKE_BINARY_DIR}/whisper)
    set(WHISPER_INCLUDE_DIR ${WHISPER_DIR}/include)
endif()

# ---------------------------------------------------------------------------
# sentencepiece (tokenizer for the NMT model)
# ---------------------------------------------------------------------------
if(NOT EXISTS "${SENTENCEPIECE_DIR}/CMakeLists.txt")
    message(STATUS "vendored sentencepiece missing, falling back to FetchContent")
    include(FetchContent)
    FetchContent_Declare(
        sentencepiece
        GIT_REPOSITORY https://github.com/google/sentencepiece.git
        GIT_TAG v0.2.0
        GIT_SHALLOW TRUE
    )
    FetchContent_MakeAvailable(sentencepiece)
else()
    add_subdirectory(${SENTENCEPIECE_DIR} ${CMAKE_BINARY_DIR}/sentencepiece)
endif()

# ---------------------------------------------------------------------------
# JNI bridges
# ---------------------------------------------------------------------------
add_library(whisperjni SHARED whisper_jni.cpp)
target_include_directories(whisperjni PRIVATE ${WHISPER_INCLUDE_DIR})
target_link_libraries(whisperjni PRIVATE whisper android log)

add_library(spjni SHARED sp_jni.cpp)
target_link_libraries(spjni PRIVATE sentencepiece android log)
