cmake_minimum_required(VERSION 3.22.1)
project(novela_cinematic_renderer LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(RENDERER_DIR "${CMAKE_CURRENT_BINARY_DIR}/renderer")
file(MAKE_DIRECTORY "${RENDERER_DIR}")
set(RENDERER_SOURCE "${RENDERER_DIR}/novela_cinematic_renderer.cpp")
file(WRITE "${RENDERER_SOURCE}" "")

foreach(PART 001.cpp.part 002.cpp.part 003.cpp.part 004.cpp.part)
    file(READ "${CMAKE_CURRENT_LIST_DIR}/renderer_parts/${PART}" PART_CONTENT)
    file(APPEND "${RENDERER_SOURCE}" "${PART_CONTENT}")
endforeach()

# Keep the user-supplied renderer files byte-for-byte unchanged. Its legacy
# cairo_arc forward declaration has one extra parameter; normalize only the
# generated build copy so it matches Cairo's actual six-argument API.
file(READ "${RENDERER_SOURCE}" RENDERER_CONTENT)
string(REPLACE
    "void cairo_arc(cairo_t*, double,double,double,double,double,double);"
    "void cairo_arc(cairo_t*, double,double,double,double,double);"
    "${RENDERER_CONTENT}"
    RENDERER_CONTENT
)
file(WRITE "${RENDERER_SOURCE}" "${RENDERER_CONTENT}")

# ndkports publishes the Pango package as "pango" in prefab.json, with
# pango-1.0 / pangocairo-1.0 as its CMake module targets. AGP supplies the
# generated Prefab package root through CMAKE_FIND_ROOT_PATH.
find_package(pango REQUIRED CONFIG)

add_library(novela_cinematic SHARED
    renderer_jni.cpp
)

target_compile_options(novela_cinematic PRIVATE
    -O3
    -ffunction-sections
    -fdata-sections
)

target_link_options(novela_cinematic PRIVATE
    -Wl,--gc-sections
)

target_include_directories(novela_cinematic PRIVATE
    "${RENDERER_DIR}"
)

target_link_libraries(novela_cinematic PRIVATE
    pango::pangocairo-1.0
    pango::pango-1.0
    log
    android
)
