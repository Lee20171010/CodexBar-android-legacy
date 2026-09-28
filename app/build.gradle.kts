import java.util.UUID

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.codexbar.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.codexbar.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 29
        versionName = "0.9.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        val releaseStoreFile = System.getenv("ANDROID_KEYSTORE_PATH")
        if (!releaseStoreFile.isNullOrBlank()) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            buildConfigField("Boolean", "IS_DEBUG", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("Boolean", "IS_DEBUG", "false")
            signingConfigs.findByName("release")?.let {
                signingConfig = it
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        generateLocaleConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    @Suppress("UnstableApiUsage")
    bundle {
        storeArchive {
            enable = true
        }
    }
}

tasks.register("generateReleaseSbom") {
    group = "reporting"
    description = "Generates a minimal CycloneDX SBOM for release runtime dependencies."

    val outputFile = layout.buildDirectory.file("reports/sbom/release-sbom.cdx.json")
    outputs.file(outputFile)

    doLast {
        val configuration = configurations.getByName("releaseRuntimeClasspath")
        val components = configuration.resolvedConfiguration.resolvedArtifacts
            .map { artifact ->
                val id = artifact.moduleVersion.id
                Triple(id.group, id.name, id.version)
            }
            .distinct()
            .sortedWith(compareBy({ it.first }, { it.second }, { it.third }))

        val json = buildString {
            appendLine("{")
            appendLine("  \"bomFormat\": \"CycloneDX\",")
            appendLine("  \"specVersion\": \"1.5\",")
            appendLine("  \"serialNumber\": \"urn:uuid:${UUID.randomUUID()}\",")
            appendLine("  \"version\": 1,")
            appendLine("  \"metadata\": {")
            appendLine("    \"component\": {")
            appendLine("      \"type\": \"application\",")
            appendLine("      \"name\": \"CodexBar Android\",")
            appendLine("      \"version\": \"${android.defaultConfig.versionName}\"")
            appendLine("    }")
            appendLine("  },")
            appendLine("  \"components\": [")
            components.forEachIndexed { index, (group, name, version) ->
                appendLine("    {")
                appendLine("      \"type\": \"library\",")
                appendLine("      \"group\": \"${group.jsonEscaped()}\",")
                appendLine("      \"name\": \"${name.jsonEscaped()}\",")
                appendLine("      \"version\": \"${version.jsonEscaped()}\",")
                appendLine("      \"purl\": \"pkg:maven/${group.jsonEscaped()}/${name.jsonEscaped()}@${version.jsonEscaped()}\"")
                append("    }")
                if (index < components.lastIndex) appendLine(",") else appendLine()
            }
            appendLine("  ]")
            appendLine("}")
        }

        val file = outputFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText(json)
    }
}

fun String.jsonEscaped(): String {
    return buildString {
        for (char in this@jsonEscaped) {
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    // AndroidX
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.appcompat)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.navigation.compose)
    implementation(libs.startup.runtime)
    implementation(libs.datastore.preferences)
    implementation(libs.browser)
    implementation(libs.play.services.code.scanner)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.compiler)

    // Network
    implementation(libs.retrofit)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit.kotlinx.serialization)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // WorkManager
    implementation(libs.work.runtime.ktx)

    // Glance (AppWidget)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.mockito.core)
}
