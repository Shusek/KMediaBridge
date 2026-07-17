# SPDX-License-Identifier: LGPL-2.1-or-later
cmake_minimum_required(VERSION 3.24)
project(kmediabridge LANGUAGES C)

find_package(PkgConfig REQUIRED)
pkg_check_modules(FFMPEG REQUIRED IMPORTED_TARGET libavformat libavcodec libavutil)

add_library(kmediabridge SHARED src/kmedia_bridge.c src/kmedia_bridge_subtitles.c)
target_include_directories(kmediabridge PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/include")
target_link_libraries(kmediabridge PRIVATE PkgConfig::FFMPEG)
target_compile_features(kmediabridge PRIVATE c_std_11)

if(MSVC)
    target_compile_options(kmediabridge PRIVATE /W4 /WX)
else()
    target_compile_options(kmediabridge PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
endif()

set_target_properties(
    kmediabridge
    PROPERTIES
        C_VISIBILITY_PRESET hidden
        VISIBILITY_INLINES_HIDDEN YES
        VERSION 0.2.0
        SOVERSION 1
)

if(APPLE)
    set_target_properties(kmediabridge PROPERTIES INSTALL_RPATH "@loader_path")
elseif(UNIX)
    set_target_properties(kmediabridge PROPERTIES INSTALL_RPATH "$ORIGIN")
endif()

install(TARGETS kmediabridge LIBRARY DESTINATION lib RUNTIME DESTINATION bin)
install(FILES include/kmedia_bridge.h DESTINATION include)
