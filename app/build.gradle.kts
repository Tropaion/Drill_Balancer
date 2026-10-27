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
                // HttpGitHubApi sends PATCH on the desktop JVM's HttpURLConnection (HTTPS: the live test)
                "--add-opens=java.base/sun.net.www.protocol.https=ALL-UNNAMED",
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
// DB_SIM_URL (the opt-in simulator check, README) is an input too, with its DB_SIM_OUT,
// DB_SIM_SCENARIOS, DB_SIM_STORE_OUT, DB_SIM_TRUTH_OUT and DB_SIM_FINALIZE_OUT (the scenario
// takes' plan and outputs; the plan's content is no input and the simulator's state cannot be,
// so these run with --rerun), as are DB_FINALIZE_OUT, DB_REVIEW_OUT and DB_MUTE_OUT (where
// FinalizerCrossCheckTest, ReviewValidatorExportTest and MuteValidatorExportTest leave
// recordings for dbdata.validate, opt-in), and DB_GITHUB_LIVE_BRANCH (the opt-in
// GitHubLiveTest's branch). DB_GITHUB_TOKEN, the live test's token, is no input:
// Gradle would keep an input's value in its task history and configuration cache. It is read
// from the environment when the tests start (doFirst), never at configuration, never cached
// (unit test tasks never are) and never written to a report; run the live test with --rerun.
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
    val optIn = listOf(
        "DB_SIM_URL", "DB_SIM_OUT", "DB_SIM_SCENARIOS", "DB_SIM_STORE_OUT", "DB_SIM_TRUTH_OUT", "DB_SIM_FINALIZE_OUT",
        "DB_FINALIZE_OUT", "DB_REVIEW_OUT", "DB_MUTE_OUT", "DB_GITHUB_LIVE_BRANCH",
    )
    for (name in optIn) {
        val v = providers.environmentVariable(name)
        inputs.property(name, v.orElse(""))
        v.orNull?.let { environment(name, it) }
    }
    doFirst {
        val test = this as Test
        test.environment.remove("DB_GITHUB_TOKEN")      // never one from a stored configuration
        System.getenv("DB_GITHUB_TOKEN")?.takeIf { it.isNotBlank() }?.let { test.environment("DB_GITHUB_TOKEN", it) }
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
    implementation(libs.androidx.work.runtime.ktx)

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
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.mockwebserver3)
}
