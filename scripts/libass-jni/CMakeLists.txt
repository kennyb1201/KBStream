# Builds libassjni.so: the JNI bridge in libassjni.cpp plus the static
# libass/FreeType/HarfBuzz/fontconfig/fribidi/expat that build_libass.sh
# cross-compiled for this ABI.
#
# Everything is linked into ONE uniquely named shared library on purpose. The
# MPV engine's libmpv.so statically links its own copies of the same
# libraries, so shipping a second libass.so / libfreetype.so would put two
# files with one soname in the APK. Namespacing them into libassjni.so is the
# same trick media3's decoder_ffmpeg extension uses with libffmpegJNI.so (see
# the FFmpeg block in app/build.gradle.kts).
#
# Required from the caller (build_libass.sh):
#   ASS_DEPS_PREFIX  directory holding <abi>/{include,lib} for each ABI
#   ANDROID_ABI      the ABI being built (supplied by the NDK toolchain file)

cmake_minimum_required(VERSION 3.22)
project(assjni CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT DEFINED ASS_DEPS_PREFIX)
    message(FATAL_ERROR "ASS_DEPS_PREFIX is not set; run this through scripts/build_libass.sh")
endif()

set(DEPS "${ASS_DEPS_PREFIX}/${ANDROID_ABI}")
foreach(required
        "${DEPS}/lib/libass.a"
        "${DEPS}/lib/libharfbuzz.a"
        "${DEPS}/lib/libfreetype.a"
        "${DEPS}/lib/libfribidi.a"
        "${DEPS}/lib/libfontconfig.a"
        "${DEPS}/lib/libexpat.a")
    if(NOT EXISTS "${required}")
        message(FATAL_ERROR "missing static dependency ${required}; the ${ANDROID_ABI} prefix is incomplete")
    endif()
endforeach()

add_library(assjni SHARED libassjni.cpp)

target_include_directories(assjni PRIVATE
    "${DEPS}/include"
    "${DEPS}/include/freetype2"
    "${DEPS}/include/fontconfig")

# The whole group is wrapped in --start-group/--end-group because these
# libraries reference each other in more than one direction: libass pulls in
# FreeType, HarfBuzz, fribidi and fontconfig, fontconfig pulls in expat, and
# HarfBuzz's own FreeType integration is compiled out (see build_libass.sh)
# but the remaining edges still do not form a single pass. A single ordered
# pass leaves unresolved symbols whenever the archive order does not happen
# to match the reference direction, which is the classic static-link failure.
target_link_libraries(assjni PRIVATE
    "-Wl,--start-group"
    "${DEPS}/lib/libass.a"
    "${DEPS}/lib/libharfbuzz.a"
    "${DEPS}/lib/libfreetype.a"
    "${DEPS}/lib/libfontconfig.a"
    "${DEPS}/lib/libfribidi.a"
    "${DEPS}/lib/libexpat.a"
    "-Wl,--end-group"
    z
    m
    log
    android
    jnigraphics)

# 16 KB page-size devices (Android 15+) crash on a 4 KB-aligned shared
# library. NDK r27+ honours ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES and aligns
# 64-bit output by default, but stating it here makes the requirement
# visible and survives a toolchain that does not, matching the flag media3
# used before 1.10. build_libass.sh verifies the result with llvm-readelf.
target_link_options(assjni PRIVATE "-Wl,-z,max-page-size=16384")

# The ABI's own libc++ is not used: nothing here crosses a std:: type
# boundary through JNI, and an unneeded libc++_shared.so would be one more
# library to keep in step with the MPV engine's copy.
set_target_properties(assjni PROPERTIES
    CXX_STANDARD 17
    ANDROID_STL c++_static)
