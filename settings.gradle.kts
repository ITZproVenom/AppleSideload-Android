pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AppleSideload"

include(":app")
include(":core")
include(":device")
include(":apple")
include(":signing")
include(":sideload")
