import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releasePropertiesFile = rootProject.file("keystore.properties").takeIf { it.exists() }

val releaseProperties = Properties().apply {
    releasePropertiesFile?.inputStream()?.use { load(it) }
}

val releaseArtifactTasks = setOf(
    "assembleRelease",
    "bundleRelease",
    "packageRelease",
    "packageReleaseBundle",
    "packageReleaseUniversalApk",
    "makeApkFromBundleForRelease",
    "installRelease",
    "installReleaseViaBundle",
    "signReleaseBundle",
)

gradle.taskGraph.whenReady {
    if (allTasks.any { it.name in releaseArtifactTasks }) {
        require(releaseProperties.isNotEmpty()) {
            "Release signing properties not found: keystore.properties"
        }
        listOf("storeFile", "storePassword", "keyAlias", "keyPassword").forEach { key ->
            require(!releaseProperties.getProperty(key).isNullOrBlank()) {
                "Release signing property is missing: $key"
            }
        }
    }
}

android {
    namespace = "com.minimalphone"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.minimalphone"
        minSdk = 35
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (releaseProperties.isNotEmpty()) {
            create("release") {
                storeFile = releaseProperties.getProperty("storeFile")?.let { rootProject.file(it) }
                storePassword = releaseProperties.getProperty("storePassword")
                keyAlias = releaseProperties.getProperty("keyAlias")
                keyPassword = releaseProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            ndk { abiFilters += "arm64-v8a" }
            isMinifyEnabled = true
            isShrinkResources = true
            isCrunchPngs = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (releaseProperties.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.androidResources.localeFilters.addAll("ru")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.compose.ui:ui:1.11.4")
    implementation("androidx.compose.foundation:foundation:1.11.4")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.googlecode.libphonenumber:libphonenumber:9.0.43")
}
