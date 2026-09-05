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
)
file(WRITE "${RENDERER_SOURCE}" "${RENDERER_CONTENT}")

# ndkports publishes Pango as a Prefab package without a package-level
# Config.cmake. Import its ABI-specific Prefab modules directly.
set(PREFAB_ROOT "${CMAKE_FIND_ROOT_PATH}")
set(PREFAB_ABI_DIR "${PREFAB_ROOT}/modules")

function(prefab_module_library OUT_VAR MODULE)
    find_library(_LIB
        NAMES "${MODULE}"
        PATHS "${PREFAB_ABI_DIR}/${MODULE}/libs/android.${ANDROID_ABI}"
        NO_DEFAULT_PATH
    )
    if(NOT _LIB)
        message(FATAL_ERROR
            "Prefab module '${MODULE}' was not found for ABI '${ANDROID_ABI}' under ${PREFAB_ABI_DIR}")
    endif()
    set(${OUT_VAR} "${_LIB}" PARENT_SCOPE)
endfunction()

function(prefab_module_include OUT_VAR MODULE)
    set(_DIR "${PREFAB_ABI_DIR}/${MODULE}/include")
    if(NOT IS_DIRECTORY "${_DIR}")
        set(_DIR "${PREFAB_ABI_DIR}/${MODULE}/include/${MODULE}")
    endif()
    if(NOT IS_DIRECTORY "${_DIR}")
        message(FATAL_ERROR
            "Prefab module '${MODULE}' headers were not found under ${PREFAB_ABI_DIR}")
    endif()
    set(${OUT_VAR} "${_DIR}" PARENT_SCOPE)
endfunction()

prefab_module_library(PANGO_LIB pango-1.0)
prefab_module_library(PANGOCAIRO_LIB pangocairo-1.0)
prefab_module_include(PANGO_INCLUDE pango-1.0)
prefab_module_include(PANGOCAIRO_INCLUDE pangocairo-1.0)

file(GLOB PREFAB_MODULE_INCLUDE_DIRS LIST_DIRECTORIES true
    "${PREFAB_ABI_DIR}/*/include")

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
    "${PANGO_INCLUDE}"
    "${PANGOCAIRO_INCLUDE}"
    ${PREFAB_MODULE_INCLUDE_DIRS}
)

target_link_libraries(novela_cinematic PRIVATE
    "${PANGOCAIRO_LIB}"
    "${PANGO_LIB}"
    log
    android
)
