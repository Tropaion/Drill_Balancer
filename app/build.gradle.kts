// Single-module build: this directory is both the Gradle root and the app module.
// Kotlin is compiled by AGP's built-in Kotlin support (no kotlin-android plugin).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "dev.drillbalancer"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.drillbalancer"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true      // VERSION_NAME for the recordings' journal
    }

    testOptions {
        // Robolectric needs merged resources/manifest for Compose and screenshot tests
        unitTests.isIncludeAndroidResources = true
        // Robolectric on JDK 17+ reflects into JDK internals (robolectric.org/getting-started)
        unitTests.all {
            it.jvmArgs(
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                "--add-opens=java.base/java.security=ALL-UNNAMED",
                "--add-opens=java.base/java.text=ALL-UNNAMED",
                "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
            )
        }
    }
}

// Golden screenshots are kept in the source tree so verifyRoborazziDebug can compare against them
roborazzi {
    outputDir.set(layout.projectDirectory.dir("src/test/screenshots"))
}

// Those screenshots are outside build/: a test task restored from the build cache would put
// back old images instead of rendering, so the unit tests always run for real.
// The conformance tests read the firmware sources (and the stream's golden vector and the
// dataset contract) at run time, so a change there alone must also make the tests run again.
// DB_SIM_URL (the opt-in simulator check, README) is an input too, as are DB_FINALIZE_OUT and
// DB_REVIEW_OUT (where FinalizerCrossCheckTest and ReviewValidatorExportTest leave recordings for
// dbdata.validate, opt-in).
tasks.withType<Test>().configureEach {
    outputs.cacheIf { false }
    inputs.files(
        fileTree(layout.projectDirectory.dir("../firmware")) {
            include(
                "components/**/*.h", "components/**/*.c", "partitions.csv", "CMakeLists.txt", "test_host/**/*.c",
                "test_host/**/vectors/*.bin", "tools/dataset/dbdata/contract/*.json",
                "tools/dataset/fixtures/**",
            )
        },
    ).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("firmwareSources")
    for (name in listOf("DB_SIM_URL", "DB_FINALIZE_OUT", "DB_REVIEW_OUT")) {
        val v = providers.environmentVariable(name)
        inputs.property(name, v.orElse(""))
        v.orNull?.let { environment(name, it) }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.nordic.ble.ktx)

    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    // Roborazzi pulls espresso 3.5, which calls InputManager.getInstance() (gone in SDK 37)
    testImplementation(libs.androidx.test.espresso.core)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
}
