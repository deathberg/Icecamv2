cmake_minimum_required(VERSION 3.22.1)
project(icecamv2_native)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(shadowhook REQUIRED CONFIG)

add_subdirectory(libvc)
add_subdirectory(vcplax)
