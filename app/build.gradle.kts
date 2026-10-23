import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val releaseKeystorePropertiesFile = rootProject.file("keystore.properties")
val releaseKeystoreProperties = Properties().apply {
    if (releaseKeystorePropertiesFile.isFile) {
        releaseKeystorePropertiesFile.inputStream().use(::load)
    }
}
val releaseSigningPropertyNames = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val hasReleaseSigningProperties = releaseKeystorePropertiesFile.isFile &&
    releaseSigningPropertyNames.all { !releaseKeystoreProperties.getProperty(it).isNullOrBlank() }
val packagedMainAssets = layout.buildDirectory.dir("generated/rankoffline/mainAssets")
val categoryPackBaseUrl = providers.gradleProperty("rankoffline.categoryPackBaseUrl")
    .orElse(providers.environmentVariable("RANKOFFLINE_CATEGORY_PACK_BASE_URL"))
    .orElse("https://github.com/JulenCasajus/RankOffline/releases/download/v0.2.0/")
    .get()
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
val bundleProductionCatalogs = providers.gradleProperty("rankoffline.bundleProductionCatalogs")
    .orElse("false")
    .map(String::toBoolean)
    .get()
val stageMainAssets by tasks.registering(Sync::class) {
    if (bundleProductionCatalogs) {
        from("src/main/assets") {
            exclude("**/*.jsonl.gz")
        }
        from("src/main/assets") {
            include("**/*.jsonl.gz")
            rename { name ->
                if (name.endsWith(".jsonl.gz")) name.removeSuffix(".gz") + ".gzip" else name
            }
        }
    } else {
        from("src/main/assets") {
            exclude("**/*.jsonl.gz")
            exclude("anime_catalog.json")
            exclude("anime_catalog.version")
            exclude("anime_aliases.manifest.json")
            exclude("*_catalog.manifest.json")
            exclude("*_groups.manifest.json")
        }
    }
    into(packagedMainAssets)
}
val stagedAndroidTestCatalogAssets = layout.buildDirectory.dir("generated/rankoffline/androidTestAssets")
val stageAndroidTestCatalogAssets by tasks.registering(Sync::class) {
    from("src/main/assets") {
        include("anime_catalog.json", "anime_catalog.version")
    }
    into(stagedAndroidTestCatalogAssets)
}

android {
    namespace = "org.rankoffline.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.rankoffline.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.1.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "CATEGORY_PACK_BASE_URL", "\"$categoryPackBaseUrl\"")
        buildConfigField("boolean", "BUNDLED_PRODUCTION_CATALOGS", bundleProductionCatalogs.toString())
        buildConfigField("String", "PACK_API_VERSION", "\"0.2.0\"")
    }

    signingConfigs {
        if (hasReleaseSigningProperties) {
            create("release") {
                storeFile = rootProject.file(releaseKeystoreProperties.getProperty("storeFile"))
                storePassword = releaseKeystoreProperties.getProperty("storePassword")
                keyAlias = releaseKeystoreProperties.getProperty("keyAlias")
                keyPassword = releaseKeystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
        }

        getByName("release") {
            if (hasReleaseSigningProperties) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    // AGP expands .gz assets during merge. Package identical build-only .gzip copies so the
    // importers can hash and stream compressed bytes while committed catalogs stay unchanged.
    androidResources {
        noCompress += "gzip"
    }

    sourceSets.getByName("main") {
        assets.setSrcDirs(listOf(packagedMainAssets))
    }
    sourceSets.getByName("androidTest").assets.srcDirs("$projectDir/schemas", stagedAndroidTestCatalogAssets)
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.outputs.forEach { output ->
            output.versionName.set("0.2.0-debug.4")
        }
    }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(stageMainAssets)
    if (name.contains("AndroidTest")) dependsOn(stageAndroidTestCatalogAssets)
}

tasks.matching { it.name.contains("lint", ignoreCase = true) }.configureEach {
    dependsOn(stageMainAssets)
    if (name.contains("AndroidTest")) dependsOn(stageAndroidTestCatalogAssets)
}

val verifyReleaseSigning by tasks.registering {
    group = "verification"
    description = "Checks that local release signing is configured without exposing secrets."
    doLast {
        check(releaseKeystorePropertiesFile.isFile) {
            "Release signing is not configured. Create keystore.properties from keystore.properties.example."
        }
        val missingProperties = releaseSigningPropertyNames.filter {
            releaseKeystoreProperties.getProperty(it).isNullOrBlank()
        }
        check(missingProperties.isEmpty()) {
            "Release signing configuration is incomplete. Missing: ${missingProperties.joinToString()}."
        }
        val configuredKeystore = rootProject.file(releaseKeystoreProperties.getProperty("storeFile"))
        check(configuredKeystore.isFile) {
            "The release keystore configured in keystore.properties does not exist."
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyReleaseSigning)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    // Official backward-compatible per-app locale support for minSdk 26.
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // DataStore for preferences
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Room owns the existing anime_rank.db file. Migrations are explicit and non-destructive.
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    // Android 17 removed the reflective InputManager API used by Espresso 3.6.x.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.room:room-testing:$roomVersion")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}
