cmake_minimum_required(VERSION 3.18.1)

project("nativesrt")

# Note: This CMakeLists.txt expects libsrt to be available.
# For production use, you need to:
# 1. Build libsrt for Android (all ABIs)
# 2. Place libsrt.so files in src/main/jniLibs/<ABI>/ directories
# 3. Place SRT headers in src/main/cpp/srt-include/ directory

add_library(nativesrt SHARED NativeSrt.cpp)

# Include SRT headers
# If you have SRT headers in a different location, update this path
target_include_directories(nativesrt PRIVATE
    ${CMAKE_SOURCE_DIR}/srt-include
)

# Link with Android libraries
find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(nativesrt
    ${log-lib}
    ${android-lib}
)

# Link libsrt if available
# The library should be placed in src/main/jniLibs/${ANDROID_ABI}/libsrt.so
set(SRT_LIB_PATH "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libsrt.so")
if(EXISTS ${SRT_LIB_PATH})
    target_link_libraries(nativesrt ${SRT_LIB_PATH})
else()
    message(WARNING "libsrt.so not found at ${SRT_LIB_PATH}. Please build and add libsrt for your target ABI.")
endif()
