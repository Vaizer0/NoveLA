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

# Prefab exposes one CMake package per AAR dependency. For ndkports, the
# package name is the Maven artifact name; the individual Pango libraries are
# Prefab modules within that package.
find_package(pango-ndk26-static CONFIG REQUIRED)
find_package(cairo-ndk26-static CONFIG REQUIRED)

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
    pango-ndk26-static::pangocairo-1.0
    pango-ndk26-static::pango-1.0
    cairo-ndk26-static::cairo
    log
    android
)
