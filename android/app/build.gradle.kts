import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.refine)
}

val keystorePropsFile = file("keystore/keystore.properties")
val releaseKeystoreFile = file("keystore/release.jks")

val releaseStorePassword: String? = System.getenv("PG_KEYSTORE_PASSWORD")
    ?: System.getenv("KEYSTORE_PASSWORD")
    ?: if (keystorePropsFile.exists()) {
        val props = Properties()
        keystorePropsFile.inputStream().use { props.load(it) }
        props.getProperty("storePassword") ?: props.getProperty("password")
    } else null

val releaseKeyPassword: String? = System.getenv("PG_KEY_PASSWORD")
    ?: System.getenv("KEY_PASSWORD")
    ?: if (keystorePropsFile.exists()) {
        val props = Properties()
        keystorePropsFile.inputStream().use { props.load(it) }
        props.getProperty("keyPassword") ?: props.getProperty("password")
    } else null

val releaseKeyAlias: String? = System.getenv("PG_KEY_ALIAS")
    ?: if (keystorePropsFile.exists()) {
        val props = Properties()
        keystorePropsFile.inputStream().use { props.load(it) }
        props.getProperty("keyAlias") ?: "privacyguard"
    } else "privacyguard"

val hasReleaseSigning = releaseKeystoreFile.exists() && !releaseStorePassword.isNullOrBlank()

if (project.hasProperty("requireReleaseSigning") && !hasReleaseSigning) {
    gradle.taskGraph.whenReady {
        if (hasTask(":app:assembleRelease") || hasTask(":app:bundleRelease")) {
            throw GradleException("Release signing credentials required by -PrequireReleaseSigning but missing or incomplete!")
        }
    }
}

android {
    namespace = "org.privacyguard.android"
    compileSdk = 35
    ndkVersion = "28.0.13004108"

    defaultConfig {
        applicationId = "org.privacyguard.android"
        minSdk = 34
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf(
                    "-std=c++17",
                    "-O3",
                    "-fno-exceptions",
                    "-fno-rtti",
                    "-fvisibility=hidden",
                    "-D_FORTIFY_SOURCE=2",
                    "-Wformat",
                    "-Werror=format-security"
                )
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -Wl,--no-undefined"
                )
            }
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword ?: releaseStorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                signingConfig = null
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.annotation)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Jetpack Compose (Material 3, Lifecycle, ViewModel - zero extended icon bloat)
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // ProfileInstaller
    implementation(libs.androidx.profileinstaller)

    // Shizuku
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // Hidden API & Refine
    compileOnly(libs.hidden.stub)
    implementation(libs.hidden.compat)
    implementation(libs.refine.runtime)
    compileOnly(libs.refine.annotation)
    implementation(libs.hiddenapibypass)

    // Unit Testing
    testImplementation(libs.junit)
}
