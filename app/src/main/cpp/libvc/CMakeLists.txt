cmake_minimum_required(VERSION 3.22.1)

find_package(shadowhook REQUIRED CONFIG)

add_library(vc SHARED
        libvc_init.cpp
        graphic_buffer_hooks.cpp
        xor_decode.cpp
)

target_include_directories(vc PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/../common
)

target_compile_definitions(vc PRIVATE VC_EXPORTS)

target_link_libraries(vc
        shadowhook::shadowhook
        log
)

set_target_properties(vc PROPERTIES
        OUTPUT_NAME "vc"
        CXX_STANDARD 17
        CXX_STANDARD_REQUIRED ON
)
