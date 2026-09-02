cmake_minimum_required(VERSION 3.20)

project(moons_native_agent LANGUAGES CXX)

if(NOT DEFINED JAVA_HOME)
    if(DEFINED ENV{JAVA_HOME})
        set(JAVA_HOME "$ENV{JAVA_HOME}")
    else()
        message(FATAL_ERROR "JAVA_HOME must point to a JDK containing include/jni.h")
    endif()
endif()

set(JAVA_INCLUDE_DIR "${JAVA_HOME}/include")
if(WIN32)
    set(JAVA_PLATFORM_INCLUDE_DIR "${JAVA_INCLUDE_DIR}/win32")
elseif(APPLE)
    set(JAVA_PLATFORM_INCLUDE_DIR "${JAVA_INCLUDE_DIR}/darwin")
else()
    set(JAVA_PLATFORM_INCLUDE_DIR "${JAVA_INCLUDE_DIR}/linux")
endif()

if(NOT EXISTS "${JAVA_INCLUDE_DIR}/jni.h" OR NOT EXISTS "${JAVA_INCLUDE_DIR}/jvmti.h")
    message(FATAL_ERROR "JAVA_HOME does not contain JNI/JVMTI headers: ${JAVA_HOME}")
endif()

add_library(moons-native SHARED src/moons_native_agent.cpp)
target_compile_features(moons-native PRIVATE cxx_std_17)
target_include_directories(moons-native PRIVATE
        "${JAVA_INCLUDE_DIR}"
        "${JAVA_PLATFORM_INCLUDE_DIR}")

if(MSVC)
    target_compile_options(moons-native PRIVATE /W4 /EHsc /permissive- /utf-8)
else()
    target_compile_options(moons-native PRIVATE -Wall -Wextra -Wpedantic)
endif()

set_target_properties(moons-native PROPERTIES
        PREFIX ""
        OUTPUT_NAME "moons-native"
        RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/out"
        LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/out")

foreach(configuration DEBUG RELEASE RELWITHDEBINFO MINSIZEREL)
    set_target_properties(moons-native PROPERTIES
            RUNTIME_OUTPUT_DIRECTORY_${configuration} "${CMAKE_BINARY_DIR}/out"
            LIBRARY_OUTPUT_DIRECTORY_${configuration} "${CMAKE_BINARY_DIR}/out")
endforeach()
