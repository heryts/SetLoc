cmake_minimum_required(VERSION 3.22.1)
project(gpsz_hook CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "gpsz_hook hanya dibangun untuk arm64-v8a")
endif()

set(GPSZ_RS "${CMAKE_CURRENT_SOURCE_DIR}/target/aarch64-linux-android/release/libgpsz_hookrs.a")
if(NOT EXISTS "${GPSZ_RS}")
    message(FATAL_ERROR "${GPSZ_RS} belum ada, jalankan cargo xtask build")
endif()
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${GPSZ_RS}")

find_package(lsplant REQUIRED CONFIG)
find_package(shadowhook REQUIRED CONFIG)

add_library(gpsz_hook SHARED bridge.cpp)
target_compile_options(gpsz_hook PRIVATE -Os -fvisibility=hidden)
target_link_options(gpsz_hook PRIVATE
    -Wl,-z,max-page-size=16384
    -Wl,--gc-sections
    -Wl,-u,JNI_OnLoad
    -Wl,-u,Java_io_gpsz_zygote_LsNative_nativeReady
    -Wl,-u,Java_io_gpsz_zygote_LsNative_nativeHook)
target_link_libraries(gpsz_hook PRIVATE "${GPSZ_RS}" lsplant::lsplant shadowhook::shadowhook log dl)
