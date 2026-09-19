cmake_minimum_required(VERSION 3.15)
project(OfflineMeshCore VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include_directories(include)

add_executable(test_core_engine
    tests/test_core_engine.cpp
)

target_include_directories(test_core_engine PRIVATE include)
