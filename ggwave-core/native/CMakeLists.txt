cmake_minimum_required(VERSION 3.20.0)
project(ggwave)

set(CMAKE_CXX_STANDARD 11)

# The Android NDK sysroot already provides jni.h; desktop builds need the host JDK headers.
if(NOT ANDROID)
    find_package(JNI REQUIRED)
    include_directories(${JNI_INCLUDE_DIRS})
endif()

file(GLOB_RECURSE sources_ggwave "${CMAKE_CURRENT_SOURCE_DIR}/ggwave/*.cpp" "${CMAKE_CURRENT_SOURCE_DIR}/ggwave_jni.cpp")
add_library(ggwave SHARED ${sources_ggwave})
