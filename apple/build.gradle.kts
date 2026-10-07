plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.applesideload.apple"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        testOptions.targetSdk = 35
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].java.srcDir("src/main/kotlin")
    sourceSets["test"].java.srcDir("src/test/kotlin")
    // core's Log writes through android.util.Log, which only exists as a
    // throwing stub in local unit tests.
    testOptions { unitTests.isReturnDefaultValues = true }
    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

dependencies {
    api(project(":core"))
    implementation(libs.androidx.core.ktx)
    api(libs.bouncycastle.prov)
    api(libs.bouncycastle.pkix)
    implementation(libs.kotlin.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.coroutines.test)
}
