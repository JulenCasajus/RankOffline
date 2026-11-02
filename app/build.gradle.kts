import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

val uploadStoreFile = providers.gradleProperty("RANKOFFLINE_UPLOAD_STORE_FILE").orNull
    ?: providers.environmentVariable("RANKOFFLINE_UPLOAD_STORE_FILE").orNull
val uploadStorePassword = providers.gradleProperty("RANKOFFLINE_UPLOAD_STORE_PASSWORD").orNull
    ?: providers.environmentVariable("RANKOFFLINE_UPLOAD_STORE_PASSWORD").orNull
val uploadKeyAlias = providers.gradleProperty("RANKOFFLINE_UPLOAD_KEY_ALIAS").orNull
    ?: providers.environmentVariable("RANKOFFLINE_UPLOAD_KEY_ALIAS").orNull
val uploadKeyPassword = providers.gradleProperty("RANKOFFLINE_UPLOAD_KEY_PASSWORD").orNull
    ?: providers.environmentVariable("RANKOFFLINE_UPLOAD_KEY_PASSWORD").orNull
val hasUploadSigning = listOf(
    uploadStoreFile, uploadStorePassword, uploadKeyAlias, uploadKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "org.rankoffline.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.rankoffline.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 9
        versionName = "0.2.2"

        buildConfigField("String", "PACK_INDEX_URL", "\"\"")
        buildConfigField("String", "PACK_ALLOWED_HOSTS", "\"\"")

        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (hasUploadSigning) {
            create("upload") {
                storeFile = rootProject.file(requireNotNull(uploadStoreFile))
                storePassword = uploadStorePassword
                keyAlias = uploadKeyAlias
                keyPassword = uploadKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField(
                "String",
                "PACK_INDEX_URL",
                "\"https://github.com/JulenCasajus/RankOffline/releases/download/v0.2.2-debug.1/pack-index.json\""
            )
            buildConfigField(
                "String",
                "PACK_ALLOWED_HOSTS",
                "\"github.com,release-assets.githubusercontent.com,objects.githubusercontent.com,github-releases.githubusercontent.com\""
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasUploadSigning) signingConfig = signingConfigs.getByName("upload")
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("src/AndroidManifest.xml")
            kotlin.directories.apply {
                clear()
                add("src/java")
            }
            res.directories.apply {
                clear()
                add("src/res")
            }
            assets.directories.apply {
                clear()
                add("src/assets")
            }
            resources.directories.apply {
                clear()
                add("src/resources")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/LICENSE.md",
            "/META-INF/LICENSE-notice.md"
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    ksp(libs.androidx.room.compiler)
}
