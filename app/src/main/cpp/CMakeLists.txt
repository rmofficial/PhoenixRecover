cmake_minimum_required(VERSION 3.22)
project(phoenix_engine LANGUAGES CXX)

add_library(phoenix_engine SHARED
    signatures.cpp
    sources.cpp
    carver.cpp
    scanner.cpp
    jni_bridge.cpp)

target_compile_features(phoenix_engine PRIVATE cxx_std_17)
target_compile_options(phoenix_engine PRIVATE -Wall -Wextra)
