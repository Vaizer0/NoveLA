cmake_minimum_required(VERSION 3.22.1)
project(novela_cinematic_renderer LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# The renderer intentionally keeps its own minimal Cairo/Pango ABI declarations.
# Native dependencies are consumed through AGP Prefab rather than modifying the
# renderer source or vendoring platform headers into the application.
find_package(cairo CONFIG REQUIRED)
find_package(pango CONFIG REQUIRED)
find_package(pangocairo CONFIG REQUIRED)

add_executable(novela_cpp_renderer
    novela_cinematic_fast_renderer_orange_numpyfree_devanagari_typography_fixed.cpp
)

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
