cmake_minimum_required(VERSION 3.22.1)
project(gpsz_hook CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(lsplant REQUIRED CONFIG)
find_package(shadowhook REQUIRED CONFIG)

add_library(gpsz_hook SHARED hook.cpp)
target_compile_options(gpsz_hook PRIVATE -Os -fvisibility=hidden)
target_link_options(gpsz_hook PRIVATE -Wl,-z,max-page-size=16384)
target_link_libraries(gpsz_hook PRIVATE lsplant::lsplant shadowhook::shadowhook)
