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

find_package(cairo CONFIG REQUIRED)
find_package(pango CONFIG REQUIRED)
find_package(pangocairo CONFIG REQUIRED)

add_executable(novela_cpp_renderer "${RENDERER_SOURCE}")

target_compile_options(novela_cpp_renderer PRIVATE
    -O3
    -ffunction-sections
    -fdata-sections
)

target_link_options(novela_cpp_renderer PRIVATE
    -Wl,--gc-sections
)

target_link_libraries(novela_cpp_renderer PRIVATE
    pangocairo::pangocairo
    pango::pango
    cairo::cairo
    log
    android
)

# ExternalNativeBuild normally packages shared libraries. The renderer is intentionally
# an executable because the supplied C++ source owns main() and invokes ffmpeg itself.
# Emit the executable into the native intermediate tree; Gradle copies it into APK assets.
set_target_properties(novela_cpp_renderer PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/renderer-bin/${ANDROID_ABI}"
)
