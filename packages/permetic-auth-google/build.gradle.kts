plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "ac.onyx.permetic.auth.google"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

dependencies {
    // api, not implementation: TokenProvider and AuthToken are in this module's own
    // public signatures, so a consumer needs them on its compile classpath.
    api(project(":permetic"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.credentials)
    // The Credential Manager backend for API < 34, where the system has no credential
    // provider of its own and Play Services answers instead. This — not an explicit
    // Play Services API call — is the whole GMS dependency, and the reason this module
    // is separate from permetic-core (spec 01, D-1).
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
