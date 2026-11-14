cmake_minimum_required(VERSION 3.22.1)

project("privacyguard_scan")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Enforce 16 KB ELF segment alignment for Android 15+ 16 KB page size support
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")

add_library(
    privacyguard_scan
    SHARED
    scanner_jni.cpp
)

target_include_directories(
    privacyguard_scan
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_link_libraries(
    privacyguard_scan
    PRIVATE
    z
    log
)
