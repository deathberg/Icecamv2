cmake_minimum_required(VERSION 3.22.1)

add_executable(vcplax
    main.cpp
    binder_service.cpp
    media_pipeline.cpp
    java_parcel_reader.cpp
)

target_include_directories(vcplax PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/../common
)

target_link_libraries(vcplax
    log
    dl
    binder_ndk
    android
)

set_target_properties(vcplax PROPERTIES
    OUTPUT_NAME "vcplax"
    SUFFIX ".so"
    POSITION_INDEPENDENT_CODE ON
)
