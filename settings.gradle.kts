@file:Suppress("ktlint")

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
    }
}

// lemuroid-cores removed: cores are now downloaded at runtime from the
// libretro nightly buildbot (arm64-v8a) instead of being bundled or
// fetched from the LemuroidCores GitHub releases.
include(
    ":libretrodroid",
    ":retrograde-util",
    ":retrograde-app-shared",
    ":lemuroid-touchinput",
    ":lemuroid-app",
    ":lemuroid-metadata-libretro-db",
    ":lemuroid-app-ext-free",
    ":lemuroid-app-ext-play",
    ":baselineprofile"
)

fun usePlayDynamicFeatures(): Boolean {
    val task = gradle.startParameter.taskRequests.toString()
    return task.contains("Play") && task.contains("Dynamic")
}
