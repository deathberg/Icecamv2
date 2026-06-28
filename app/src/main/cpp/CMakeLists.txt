cmake_minimum_required(VERSION 3.22.1)
project(icecamv2_native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(shadowhook REQUIRED CONFIG)

add_library(vc SHARED
        libvc/libvc.cpp
        libvc/graphic_buffer_hooks.cpp
        libvc/frame_inject.cpp
        common/xor_decode.cpp)

target_include_directories(vc PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/common)

target_link_libraries(vc
        shadowhook::shadowhook
        log
        android
        dl)

# vcplax daemon sources live in vcplax/ for AOSP-toolchain builds.
# Public NDK does not expose ServiceManager registration APIs required to
# link a standalone Binder host binary; the reference vcplax.so is packaged
# from testicecam2.apk until an AOSP sysroot build is wired up.
