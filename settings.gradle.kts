pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        // Resolve com.github.* (RootEncoder) from JitPack first so Maven Central
        // is never probed for it; keeps central 429s from breaking those lookups.
        maven("https://jitpack.io") {
            content {
                includeGroupByRegex("com\\.github\\..*")
            }
        }
        // Classic Xposed API (compileOnly) for the CamProbe module
        maven("https://api.xposed.info/")
        google()
        mavenCentral()
    }
}

rootProject.name = "CamCast"
include(":app")
include(":probe")
