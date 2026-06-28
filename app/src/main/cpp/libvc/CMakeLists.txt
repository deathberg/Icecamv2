cmake_minimum_required(VERSION 3.22.1)

add_library(vc SHARED
    libvc.cpp
    graphic_buffer_hooks.cpp
)

target_include_directories(vc PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/../common
)

target_compile_definitions(vc PRIVATE
    ICECAM_LIBVC_BUILD
)

target_link_libraries(vc
    shadowhook::shadowhook
    log
    android
    dl
)

set_target_properties(vc PROPERTIES
    OUTPUT_NAME "vc"
    CXX_VISIBILITY_PRESET default
)
