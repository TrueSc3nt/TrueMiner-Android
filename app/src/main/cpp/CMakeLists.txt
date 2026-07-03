cmake_minimum_required(VERSION 3.22.1)
project(trueminer_native)

add_library(trueminer_native SHARED native_miner.cpp)

target_compile_features(trueminer_native PRIVATE cxx_std_17)
target_compile_options(trueminer_native PRIVATE -Wall -Wextra -O3)

find_library(log-lib log)
find_library(android-lib android)
find_library(vulkan-lib vulkan)
find_library(dl-lib dl)

target_link_libraries(trueminer_native ${log-lib} ${android-lib} ${vulkan-lib} ${dl-lib})
