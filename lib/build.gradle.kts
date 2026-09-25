import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    id("kotlin-parcelize")
}

val hostJniDir = layout.buildDirectory.dir("host-jni")
val cppSourceDir = layout.projectDirectory.dir("src/main/cpp")

val cmakeConfigureHost by tasks.registering(Exec::class) {
    group = "build"
    description = "Configure the CMake host build of jni_cb_term"
    inputs.dir(cppSourceDir)
    outputs.dir(hostJniDir)
    commandLine(
        "cmake",
        "-S",
        cppSourceDir.asFile.absolutePath,
        "-B",
        hostJniDir.get().asFile.absolutePath,
        "-DCMAKE_BUILD_TYPE=Debug",
        // Host ASan crash-regression tests (#533) — see CMakeLists.
        "-DCB_TERM_HOST_TESTS=ON",
    )
}

val buildHostNativeTests by tasks.registering(Exec::class) {
    group = "verification"
    description = "Build the host ASan crash-regression tests (#533)"
    dependsOn(cmakeConfigureHost)
    // Shares the cmake build dir with cmakeBuildHost; order after it and
    // claim only the test binary so their outputs don't overlap.
    mustRunAfter(cmakeBuildHost)
    inputs.dir(cppSourceDir)
    commandLine(
        "cmake",
        "--build",
        hostJniDir.get().asFile.absolutePath,
        "--target",
        "reflow_underflow_test",
    )
    outputs.file(hostJniDir.map { it.file("reflow_underflow_test") })
}

val runHostNativeTests by tasks.registering(Exec::class) {
    group = "verification"
    description = "Run the host ASan crash-regression tests (#533) — ASan aborts non-zero on a fault"
    dependsOn(buildHostNativeTests)
    commandLine(hostJniDir.get().file("reflow_underflow_test").asFile.absolutePath)
}

val cmakeBuildHost by tasks.registering(Exec::class) {
    group = "build"
    description = "Build libjni_cb_term for the host JVM"
    dependsOn(cmakeConfigureHost)
    inputs.dir(hostJniDir)
    commandLine(
        "cmake",
        "--build",
        hostJniDir.get().asFile.absolutePath,
        "--target",
        "jni_cb_term",
    )
    outputs.dir(hostJniDir)
}

android {
    namespace = "org.connectbot.terminal"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        externalNativeBuild {
            cmake {}
        }

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            debugSymbolLevel = "full"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.3"
    }

    packaging {
        jniLibs {
            keepDebugSymbols.add("**/*.so")
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { testTask ->
                testTask.dependsOn(cmakeBuildHost)
                // The unit-test run is also what executes the host ASan
                // crash-regression tests (#533) — a JVM test provably could
                // not catch the reflow heap underflow (slop reads pass), so
                // the native gate rides the same task CI already runs.
                testTask.dependsOn(runHostNativeTests)
                testTask.jvmArgs("-Djava.library.path=${hostJniDir.get().asFile.absolutePath}")
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.ui)

    // Jetpack Compose
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.foundation)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(composeBom)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.mockk)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
