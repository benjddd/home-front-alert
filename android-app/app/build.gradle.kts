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
val apiKeyEnv = localProperties.getProperty("backend.api_key") ?: "DEVELOPMENT_MODE_UNSET"
val storePasswordEnv = localProperties.getProperty("keystore.password") ?: System.getenv("STORE_PASSWORD") ?: ""
val keyPasswordEnv = localProperties.getProperty("key.password") ?: System.getenv("KEY_PASSWORD") ?: ""

android {
    namespace = "com.attius.homefrontalert"
    compileSdk = 34

    androidResources {
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:!CVS:!thumbs.db:!picasa.ini:!*~:desktop.ini"
    }

    defaultConfig {
        applicationId = "com.attius.homefrontalert"
        minSdk = 26
        targetSdk = 35
        versionCode = 36
        versionName = "2.2.0"

        buildConfigField("String", "BACKEND_URL", "\"$backendUrlEnv\"")
        buildConfigField("String", "API_KEY", "\"$apiKeyEnv\"")
    }

    signingConfigs {
        create("release") {
            storeFile = file("../release.jks")
            storePassword = storePasswordEnv
            keyAlias = "homefront"
            keyPassword = keyPasswordEnv
        }
    }

    sourceSets.getByName("main") {
        resources.setExcludes(setOf("**/desktop.ini"))
        res.setExcludes(setOf("**/desktop.ini"))
    }

    packaging {
        resources {
            excludes += "/desktop.ini"
            excludes += "**/desktop.ini"
        }
    }

    aaptOptions {
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:desktop.ini"
    }

    packaging {
        resources {
            excludes += "**/desktop.ini"
        }
    }

    // NUCLEAR OPTION: Delete any auto-generated desktop.ini files 
    // exactly before the build starts to avoid Google Drive sync locks.
    tasks.register("cleanDesktopInit") {
        doLast {
            println("🛡️ Purging desktop.ini files from ALL directories...")
            projectDir.walkBottomUp().forEach { file ->
                if (file.name.equals("desktop.ini", ignoreCase = true)) {
                    try {
                        file.delete()
                    } catch (e: Exception) {
                        println("⚠️ Could not delete ${file.absolutePath}")
                    }
                }
            }
        }
    }

    tasks.all {
        if (this.name.startsWith("mergePro") || this.name == "preBuild") {
            this.dependsOn("cleanDesktopInit")
        }
    }

    buildTypes {
        release {
            // R8 minification disabled: AGP R8 task fails on Google Drive paths
            // (proguard-android-optimize.txt not found at intermediates path).
            // TODO: Enable when building via CI (GitHub Actions) on a normal filesystem.
            isMinifyEnabled = false
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


// Redirect build output to temp dir locally to avoid Google Drive sync locks.
// On CI (where GOOGLE_APPLICATION_CREDENTIALS is set), use the default location.
if (System.getenv("CI") == null) {
    project.layout.buildDirectory.set(file(System.getProperty("java.io.tmpdir") + "/homefrontalert/app/build"))
}
