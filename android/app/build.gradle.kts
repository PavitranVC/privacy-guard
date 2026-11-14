import java.security.SecureRandom
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.refine)
}

val keystorePropsFile = file("keystore/keystore.properties")
val releaseKeystoreFile = file("keystore/release.jks")

val releasePassword: String = run {
    val envPass = System.getenv("KEYSTORE_PASSWORD")
    if (!envPass.isNullOrBlank()) {
        envPass
    } else if (keystorePropsFile.exists()) {
        val props = Properties()
        keystorePropsFile.inputStream().use { props.load(it) }
        props.getProperty("password") ?: "privacyguard123"
    } else {
        val pass = if (releaseKeystoreFile.exists()) {
            "privacyguard123"
        } else {
            val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            val random = SecureRandom()
            (1..32).map { chars[random.nextInt(chars.length)] }.joinToString("")
        }
        keystorePropsFile.parentFile?.mkdirs()
        val props = Properties()
        props.setProperty("password", pass)
        keystorePropsFile.outputStream().use { props.store(it, "Local release keystore password") }
        pass
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
        create("release") {
            if (releaseKeystoreFile.exists()) {
                storeFile = releaseKeystoreFile
                storePassword = releasePassword
                keyAlias = "privacyguard"
                keyPassword = releasePassword
            } else {
                initWith(getByName("debug"))
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
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
