cmake_minimum_required(VERSION 3.0)

set(CLASH_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../foss/golang/clash")

set(COMMIT_HASH "unknown")
set(CURRENT_BRANCH "unknown")

set(CORE_LABEL "unknown")

find_package(Git QUIET)

if (GIT_FOUND AND EXISTS "${CLASH_SOURCE_DIR}")
    execute_process(
            COMMAND ${GIT_EXECUTABLE} log -1 --format=%H
            WORKING_DIRECTORY ${CLASH_SOURCE_DIR}
            OUTPUT_VARIABLE CLASH_COMMIT
            OUTPUT_STRIP_TRAILING_WHITESPACE
            ERROR_QUIET
            RESULT_VARIABLE CLASH_COMMIT_RESULT
    )

    if (CLASH_COMMIT_RESULT EQUAL 0 AND NOT "${CLASH_COMMIT}" STREQUAL "")
        string(SUBSTRING "${CLASH_COMMIT}" 0 7 COMMIT_HASH)

        execute_process(
                COMMAND ${GIT_EXECUTABLE} describe --tags --exact-match ${CLASH_COMMIT}
                WORKING_DIRECTORY ${CLASH_SOURCE_DIR}
                OUTPUT_VARIABLE CLASH_TAG
                OUTPUT_STRIP_TRAILING_WHITESPACE
                ERROR_QUIET
                RESULT_VARIABLE CLASH_TAG_RESULT
        )

        if (CLASH_TAG_RESULT EQUAL 0 AND NOT "${CLASH_TAG}" STREQUAL "")
            set(CORE_LABEL "${CLASH_TAG}")
        else ()
            execute_process(
                    COMMAND ${GIT_EXECUTABLE} branch -r --contains ${CLASH_COMMIT}
                    WORKING_DIRECTORY ${CLASH_SOURCE_DIR}
                    OUTPUT_VARIABLE CLASH_BRANCHES
                    OUTPUT_STRIP_TRAILING_WHITESPACE
                    ERROR_QUIET
                    RESULT_VARIABLE CLASH_BRANCH_RESULT
            )

            if (CLASH_BRANCH_RESULT EQUAL 0 AND NOT "${CLASH_BRANCHES}" STREQUAL "")
                string(REPLACE "\n" ";" CLASH_BRANCHES "${CLASH_BRANCHES}")
                list(GET CLASH_BRANCHES 0 CURRENT_BRANCH)
                string(REGEX REPLACE "^[ \t]+" "" CURRENT_BRANCH "${CURRENT_BRANCH}")
                string(REGEX REPLACE "^origin/" "" CURRENT_BRANCH "${CURRENT_BRANCH}")
                string(REGEX REPLACE "[ \t].*$" "" CURRENT_BRANCH "${CURRENT_BRANCH}")

                set(CORE_LABEL "${CURRENT_BRANCH}")
            endif ()
        endif ()
    endif ()
endif ()

message(STATUS "core version = ${CORE_LABEL}_${COMMIT_HASH}")

string(TIMESTAMP COMPILE_TIME "%y%m%d")

if ("${CORE_LABEL}" STREQUAL "unknown")
    set(GIT_VERSION "${COMMIT_HASH}_${COMPILE_TIME}")
else ()
    set(GIT_VERSION "${CORE_LABEL}_${COMMIT_HASH}_${COMPILE_TIME}")
endif ()

string(REGEX REPLACE "[^A-Za-z0-9_./+-]" "_" GIT_VERSION "${GIT_VERSION}")

configure_file(${CMAKE_CURRENT_SOURCE_DIR}/version.h.in ${CMAKE_CURRENT_SOURCE_DIR}/version.h @ONLY)

project(clash-bridge C)

set(CMAKE_POSITION_INDEPENDENT_CODE on)
set(CMAKE_C_FLAGS_RELEASE "${CMAKE_C_FLAGS_RELEASE} -O3")

set(GO_OUTPUT_BASE ${GO_OUTPUT}/${FLAVOR_NAME})

if ("${CMAKE_BUILD_TYPE}" STREQUAL "Debug")
    set(GO_OUTPUT_BASE "${GO_OUTPUT_BASE}Debug")
elseif ("${CMAKE_BUILD_TYPE}" STREQUAL "Release")
    set(GO_OUTPUT_BASE "${GO_OUTPUT_BASE}Release")
elseif ("${CMAKE_BUILD_TYPE}" STREQUAL "RelWithDebInfo")
    set(GO_OUTPUT_BASE "${GO_OUTPUT_BASE}Release")
else ()
    message(FATAL_ERROR "Unknown build type ${CMAKE_BUILD_TYPE}")
endif ()

include_directories("${GO_OUTPUT_BASE}/${CMAKE_ANDROID_ARCH_ABI}")
include_directories("${GO_SOURCE}")

link_directories("${GO_OUTPUT_BASE}/${CMAKE_ANDROID_ARCH_ABI}")

add_library(bridge SHARED main.c jni_helper.c bridge_helper.c)
target_link_libraries(bridge log clash)
