cmake_minimum_required(VERSION 3.22.1)
project(icecamv2_native LANGUAGES C CXX)

find_package(shadowhook REQUIRED CONFIG)

set(ICE_LIBVC_SOURCES
    libvc/xor_decode.cpp
    libvc/inject_state.cpp
    libvc/graphic_buffer_hooks.cpp
    libvc/libvc_init.cpp
)

set(ICE_VCPLAX_SOURCES
    vcplax/parcel_utils.cpp
    vcplax/binder_runtime.cpp
    vcplax/media_context.cpp
    vcplax/binder_service.cpp
    vcplax/main.cpp
)

add_library(vc SHARED ${ICE_LIBVC_SOURCES})
target_include_directories(vc PRIVATE libvc)
target_compile_options(vc PRIVATE -Wall -Wextra -Wno-unused-parameter)
target_link_libraries(vc shadowhook::shadowhook android log dl)

add_executable(vcplax ${ICE_VCPLAX_SOURCES})
target_include_directories(vcplax PRIVATE vcplax vcplax/ndk_compat)
target_compile_options(vcplax PRIVATE -Wall -Wextra -Wno-unused-parameter)
target_link_libraries(vcplax android binder_ndk log dl)
set_target_properties(vcplax PROPERTIES
    OUTPUT_NAME "vcplax"
    SUFFIX ".so"
    LINK_FLAGS "-Wl,--allow-shlib-undefined"
)

# Install vcplax executable into jniLibs so NativeExtractor can package it.
set(JNI_LIBS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")
add_custom_command(TARGET vcplax POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory "${JNI_LIBS_DIR}"
    COMMAND ${CMAKE_COMMAND} -E copy $<TARGET_FILE:vcplax> "${JNI_LIBS_DIR}/$<TARGET_FILE_NAME:vcplax>"
    COMMENT "Copy vcplax.so to jniLibs/${ANDROID_ABI}"
)
