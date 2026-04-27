cmake_minimum_required(VERSION 3.13.2)

# ARM64-only build: aggressive optimizations for AArch64/NEON
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=c++17 -DNDEBUG")
set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS}   -DNDEBUG")

# Release flags:
#  -march=armv8.2-a+simd+crypto  – enables fp16/dotprod on A75/A76/A55 gen2+
#  -mtune=cortex-a55             – size-optimise for efficiency cluster
#  -fno-strict-aliasing          – safe with NEON pointer casts
#  -falign-functions=64          – hot functions start on cache-line boundaries
#  -fno-math-errno               – remove math-error-check overhead (safe with -ffast-math)
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -march=armv8.2-a+simd+crypto -mtune=cortex-a55 \
    -ffast-math -fno-math-errno -fno-strict-aliasing \
    -fomit-frame-pointer -funroll-loops \
    -falign-functions=64 \
    -fvisibility=hidden -flto=thin \
    -ffunction-sections -fdata-sections")
set(CMAKE_C_FLAGS_RELEASE   "-O3 -march=armv8.2-a+simd+crypto -mtune=cortex-a55 \
    -ffast-math -fno-math-errno -fno-strict-aliasing \
    -fomit-frame-pointer -funroll-loops \
    -falign-functions=64 \
    -fvisibility=hidden -flto=thin \
    -ffunction-sections -fdata-sections")
set(CMAKE_SHARED_LINKER_FLAGS_RELEASE "${CMAKE_SHARED_LINKER_FLAGS_RELEASE} \
    -Wl,--gc-sections -Wl,--strip-all -flto=thin")

add_definitions("-DVFS_FRONTEND -DHAVE_STRL")

# Let's include oboe
set (OBOE_DIR oboe)
add_subdirectory (${OBOE_DIR} oboe)
include_directories (${OBOE_DIR}/include)
include_directories (${OBOE_DIR}/src)

include_directories("libretro/libretro-common/include")

set (LIBRETRO_COMMON
        libretro/libretro-common/vfs/vfs_implementation.c
        libretro/libretro-common/string/stdstring.c
        libretro/libretro-common/encodings/encoding_utf.c
        libretro/libretro-common/file/file_path.c
        libretro/libretro-common/time/rtime.c
)

add_library(libretrodroid SHARED
        libretrodroidjni.h
        libretrodroidjni.cpp
        libretrodroid.h
        libretrodroid.cpp
        log.h
        core.h
        core.cpp
        video.h
        video.cpp
        immersivemode.h
        immersivemode.cpp
        videolayout.h
        videolayout.cpp
        renderers/renderer.h
        renderers/renderer.cpp
        renderers/es3/es3utils.h
        renderers/es3/es3utils.cpp
        renderers/es3/framebufferrenderer.h
        renderers/es3/framebufferrenderer.cpp
        renderers/es2/imagerendereres2.h
        renderers/es2/imagerendereres2.cpp
        renderers/es3/imagerendereres3.h
        renderers/es3/imagerendereres3.cpp
        audio.h
        audio.cpp
        resamplers/resampler.h
        resamplers/linearresampler.h
        resamplers/linearresampler.cpp
        resamplers/sincresampler.h
        resamplers/sincresampler.cpp
        fpssync.h
        fpssync.cpp
        environment.h
        environment.cpp
        input.h
        input.cpp
        shadermanager.h
        shadermanager.cpp
        rumble.h
        rumble.cpp
        rumblestate.h
        rumblestate.cpp
        utils/javautils.h
        utils/javautils.cpp
        utils/utils.cpp
        utils/utils.h
        utils/jnistring.h
        utils/jnistring.cpp
        utils/libretrodroidexception.h
        utils/libretrodroidexception.cpp
        utils/rect.h
        utils/rect.cpp
        errorcodes.h
        errorcodes.cpp
        vfs/vfs.h
        vfs/vfs.cpp
        vfs/vfsfile.h
        vfs/vfsfile.cpp
        vfs/fdwrapper.h
        vfs/fdwrapper.cpp
        microphone/microphone.h
        microphone/microphone.cpp
        microphone/microphoneinterface.h
        microphone/microphoneinterface.cpp
        ${LIBRETRO_COMMON}
)

# Support 16k pages for Android 15 + LTO thin linking
target_link_options(libretrodroid PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,--gc-sections"
    "-flto=thin"
)

# add lib dependencies
target_link_libraries(libretrodroid
                      android
                      log 
                      EGL
                      oboe
                      GLESv3
)
