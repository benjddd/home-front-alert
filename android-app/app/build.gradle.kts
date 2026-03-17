import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

val backendUrlEnv = localProperties.getProperty("backend.url") ?: "https://home-front-alert-hfc.web.app"
val apiKeyEnv = localProperties.getProperty("backend.api_key") ?: "Attius-HFC-Shield-2026-Bypass"

android {
    namespace = "com.attius.homefrontalert"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.attius.homefrontalert"
        minSdk = 26
        targetSdk = 35
        versionCode = 21
        versionName = "1.7.1"

        buildConfigField("String", "BACKEND_URL", "\"$backendUrlEnv\"")
        buildConfigField("String", "API_KEY", "\"$apiKeyEnv\"")
    }

    signingConfigs {
        create("release") {
            storeFile = file("../release.jks")
            storePassword = "HomeFront2026!"
            keyAlias = "homefront"
            keyPassword = "HomeFront2026!"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    flavorDimensions += "version"
    productFlavors {
        create("standard") {
            dimension = "version"
            buildConfigField("boolean", "IS_PAID", "false")
        }
        create("pro") {
            dimension = "version"
            buildConfigField("boolean", "IS_PAID", "true")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    
    kotlinOptions {
        jvmTarget = "1.8"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    // Name APKs: HomeFrontAlert-{version}-{flavor}-{buildType}.apk
    applicationVariants.all {
        val variant = this
        variant.outputs
            .map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
            .forEach { output ->
                output.outputFileName = "HomeFrontAlert-${variant.versionName}-${variant.flavorName}-${variant.buildType.name}.apk"
            }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.firebase:firebase-messaging-ktx:23.4.0")
    implementation("com.google.android.gms:play-services-location:21.1.0")
}
