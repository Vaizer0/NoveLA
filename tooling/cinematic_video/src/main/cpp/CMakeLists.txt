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

# The supplied renderer contains a legacy cairo_arc forward declaration with
# one extra parameter. Keep the supplied source files untouched and normalize
# only the generated build copy to Cairo's real six-parameter API.
file(READ "${RENDERER_SOURCE}" RENDERER_CONTENT)
string(REPLACE
    "void cairo_arc(cairo_t*, double,double,double,double,double,double);"
    "void cairo_arc(cairo_t*, double,double,double,double,double);"
    RENDERER_CONTENT
    "${RENDERER_CONTENT}"
)
file(WRITE "${RENDERER_SOURCE}" "${RENDERER_CONTENT}")

# AGP passes a CMake list in CMAKE_FIND_ROOT_PATH. The first entry is the
# Prefab-generated root for this ABI; later entries (such as the NDK root)
# must not be mixed into Prefab filesystem paths.
list(GET CMAKE_FIND_ROOT_PATH 0 PREFAB_ROOT)

# Resolve the Pango dependency closure from the generated Prefab tree rather
# than relying on a package-level Config.cmake. This is robust to Prefab's
# generated directory layout and keeps the supplied renderer unchanged.
file(GLOB_RECURSE PREFAB_INCLUDE_DIRS LIST_DIRECTORIES true
    "${PREFAB_ROOT}/*/include")
file(GLOB_RECURSE PREFAB_STATIC_LIBS
    "${PREFAB_ROOT}/*.a")

list(FILTER PREFAB_INCLUDE_DIRS EXCLUDE REGEX ".*/include/.*\\.a$")

if(NOT PREFAB_STATIC_LIBS)
    message(FATAL_ERROR
        "No Prefab static libraries were found under ${PREFAB_ROOT}; Pango/GLib/Cairo dependencies are unavailable")
endif()

set(HAVE_PANGO FALSE)
set(HAVE_PANGOCAIRO FALSE)
foreach(LIB IN LISTS PREFAB_STATIC_LIBS)
    get_filename_component(LIB_NAME "${LIB}" NAME)
    if(LIB_NAME STREQUAL "libpango-1.0.a")
        set(HAVE_PANGO TRUE)
    elseif(LIB_NAME STREQUAL "libpangocairo-1.0.a")
        set(HAVE_PANGOCAIRO TRUE)
    endif()
endforeach()

if(NOT HAVE_PANGO OR NOT HAVE_PANGOCAIRO)
    message(FATAL_ERROR
        "Required Pango static libraries were not found under ${PREFAB_ROOT} (pango=${HAVE_PANGO}, pangocairo=${HAVE_PANGOCAIRO})")
endif()

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
    -Wl,--start-group
    -Wl,--end-group
)

target_include_directories(novela_cinematic PRIVATE
    "${RENDERER_DIR}"
    ${PREFAB_INCLUDE_DIRS}
)

# Static ndkports packages ship their dependency closure as individual
# archives. Link the complete generated Prefab set inside a linker group so
# circular static dependencies resolve deterministically.
target_link_libraries(novela_cinematic PRIVATE
    ${PREFAB_STATIC_LIBS}
    log
    android
    dl
    m
)