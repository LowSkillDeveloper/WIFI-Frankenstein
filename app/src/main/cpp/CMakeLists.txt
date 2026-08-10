cmake_minimum_required(VERSION 3.10)
project("cracker" C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

add_library(cracker SHARED cracker.c)

target_compile_options(cracker PRIVATE
        -O2
        -funroll-loops
        $<$<NOT:$<CONFIG:Debug>>:-DNDEBUG>
)

if (ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_options(cracker PRIVATE -march=armv8-a+crypto)
endif ()

target_include_directories(cracker PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

set_target_properties(cracker PROPERTIES
        POSITION_INDEPENDENT_CODE ON
)
