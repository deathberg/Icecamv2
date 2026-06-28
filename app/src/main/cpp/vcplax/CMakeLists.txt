cmake_minimum_required(VERSION 3.22.1)

add_executable(vcplax
        main.cpp
        binder_service.cpp
        media_pipeline.cpp
)

target_include_directories(vcplax PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/../common
)

target_link_libraries(vcplax
        binder_ndk
        mediandk
        log
        android
        dl
)

set_target_properties(vcplax PROPERTIES
        CXX_STANDARD 17
        CXX_STANDARD_REQUIRED ON
        RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}"
)
