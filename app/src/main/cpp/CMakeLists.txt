cmake_minimum_required(VERSION 3.22.1)
project(ugscaler_ncnn LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(NCNN_VERSION 20260526)
set(NCNN_ARCHIVE ncnn-${NCNN_VERSION}-android-vulkan-shared.zip)
set(NCNN_URL https://github.com/Tencent/ncnn/releases/download/${NCNN_VERSION}/${NCNN_ARCHIVE})
set(NCNN_SHA256 eb205b332274974511890903828451ae7a4c19c309f21431536e0a8c9f3dd0c1)
set(NCNN_LOCAL "${CMAKE_CURRENT_SOURCE_DIR}/../../../../build/native-deps/ncnn-${NCNN_VERSION}-android-vulkan-shared")

if(EXISTS "${NCNN_LOCAL}/${ANDROID_ABI}/lib/cmake/ncnn/ncnnConfig.cmake")
    set(NCNN_ROOT "${NCNN_LOCAL}")
else()
    set(NCNN_DOWNLOAD_DIR "${CMAKE_BINARY_DIR}/ncnn-download")
    set(NCNN_ZIP "${NCNN_DOWNLOAD_DIR}/${NCNN_ARCHIVE}")
    set(NCNN_ROOT "${NCNN_DOWNLOAD_DIR}/ncnn-${NCNN_VERSION}-android-vulkan-shared")
    if(NOT EXISTS "${NCNN_ROOT}/${ANDROID_ABI}/lib/cmake/ncnn/ncnnConfig.cmake")
        file(MAKE_DIRECTORY "${NCNN_DOWNLOAD_DIR}")
        file(DOWNLOAD "${NCNN_URL}" "${NCNN_ZIP}"
                EXPECTED_HASH SHA256=${NCNN_SHA256}
                SHOW_PROGRESS TLS_VERIFY ON)
        file(ARCHIVE_EXTRACT INPUT "${NCNN_ZIP}" DESTINATION "${NCNN_DOWNLOAD_DIR}")
    endif()
endif()

set(ncnn_DIR "${NCNN_ROOT}/${ANDROID_ABI}/lib/cmake/ncnn")
find_package(ncnn REQUIRED CONFIG)

add_library(ugscaler_ncnn SHARED ugscaler_ncnn.cpp)
target_link_libraries(ugscaler_ncnn PRIVATE ncnn android jnigraphics log)
target_link_options(ugscaler_ncnn PRIVATE "-Wl,-z,max-page-size=16384")
