pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Auto-provisions the JDK 17 toolchain (root CLAUDE.md minimum) when the host
    // only has a different JDK installed, instead of failing the build.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "permetic-android"

// permetic-push and permetic-billing are not included yet — they ship no code this
// round and an empty included module would misrepresent the build graph as having
// artifacts that don't exist. Add them when their tasks (spec 01, tasks 7-8) start.
include(":permetic")
project(":permetic").projectDir = file("packages/permetic")

// Optional artifact, separate so permetic-core stays free of Play Services: only
// Credential Manager's pre-API-34 backend pulls GMS in (spec 01, open decision D-1).
include(":permetic-auth-google")
project(":permetic-auth-google").projectDir = file("packages/permetic-auth-google")
