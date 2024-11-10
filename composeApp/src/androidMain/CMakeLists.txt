cmake_minimum_required(VERSION 3.20.0)
project(ggwave)

set (CMAKE_CXX_STANDARD 11)

file(GLOB_RECURSE sources_ggwave "../../native/ggwave/*.cpp" "../../native/test-cpp.cpp")

add_library(ggwave SHARED ${sources} ${sources_ggwave})

target_link_libraries(ggwave)