cmake_minimum_required(VERSION 3.20)

project(moons_bridge LANGUAGES CXX)

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
else()
    message(FATAL_ERROR "The JNI bridge DLL currently requires Windows")
endif()

if(NOT EXISTS "${JAVA_INCLUDE_DIR}/jni.h")
    message(FATAL_ERROR "JAVA_HOME does not contain JNI headers: ${JAVA_HOME}")
endif()

add_library(moons-bridge SHARED src/moons_bridge.cpp)
target_compile_features(moons-bridge PRIVATE cxx_std_17)
target_include_directories(moons-bridge PRIVATE
        "${JAVA_INCLUDE_DIR}"
        "${JAVA_PLATFORM_INCLUDE_DIR}")
target_link_libraries(moons-bridge PRIVATE
        "${JAVA_HOME}/lib/jvm.lib"
        shell32
        ole32
        uuid)

if(MSVC)
    target_compile_options(moons-bridge PRIVATE /W4 /EHsc /permissive- /utf-8)
else()
    target_compile_options(moons-bridge PRIVATE -Wall -Wextra -Wpedantic)
endif()

set_target_properties(moons-bridge PROPERTIES
        PREFIX ""
        OUTPUT_NAME "moons-bridge"
        RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/out"
        LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/out")

foreach(configuration DEBUG RELEASE RELWITHDEBINFO MINSIZEREL)
    set_target_properties(moons-bridge PROPERTIES
            RUNTIME_OUTPUT_DIRECTORY_${configuration} "${CMAKE_BINARY_DIR}/out"
            LIBRARY_OUTPUT_DIRECTORY_${configuration} "${CMAKE_BINARY_DIR}/out")
endforeach()
