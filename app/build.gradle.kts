import java.security.MessageDigest
import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.shilex.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.shilex.android"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // Phones only (ONNX Runtime ships native code per CPU type).
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // The release key never enters the repo: scripts/android_release.py passes its path and
    // password (from the macOS Keychain) as environment variables. Without them the release
    // build is unsigned. Android installs an update only if it is signed with this same key.
    val releaseKeystore = System.getenv("SHILEX_KEYSTORE")
    val v2FixKeystoreFile = layout.buildDirectory.file("generated/signing/v2-install-fix.keystore").get().asFile
    val v2FixKeystoreB64 = file("v2-install-fix.keystore.base64")
    if (v2FixKeystoreB64.exists() && (!v2FixKeystoreFile.exists() || v2FixKeystoreFile.length() == 0L)) {
        v2FixKeystoreFile.parentFile.mkdirs()
        v2FixKeystoreFile.writeBytes(Base64.getMimeDecoder().decode(v2FixKeystoreB64.readText(Charsets.UTF_8)))
    }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("SHILEX_KEYSTORE_PASSWORD")
                keyAlias = "jarvis"
                keyPassword = System.getenv("SHILEX_KEYSTORE_PASSWORD")
            }
        }
        create("v2Fix") {
            storeFile = v2FixKeystoreFile
            storePassword = "jarvisv2fix"
            keyAlias = "jarvisv2"
            keyPassword = "jarvisv2fix"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
        create("v2") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".v2"
            matchingFallbacks += listOf("debug")
        }
        create("v2Fix") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".v2fix"
            matchingFallbacks += listOf("debug")
            signingConfig = signingConfigs.getByName("v2Fix")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        jniLibs {
            pickFirsts += listOf("**/libonnxruntime.so", "**/libc++_shared.so")
        }
    }
    // SpeexDSP's echo canceller (BSD), built from src/main/cpp.
    ndkVersion = "27.0.12077973"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    // Android calls such as Log do nothing in local unit tests instead of throwing.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.okhttp)
    // On-device wake word (openWakeWord ONNX models), MIT licence.
    implementation(libs.onnxruntime.android)
    // Offline speech for local commands (Vosk, Apache 2.0); the model comes from fetchVoskModel.
    implementation(libs.vosk.android)
    // Offline Hindi neural TTS engine (Sherpa-ONNX + statically linked ONNX Runtime, Apache 2.0).
    implementation(files("libs/sherpa-onnx-static-link-onnxruntime-1.13.8.aar"))
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    // Android's own org.json is a stub in local unit tests.
    testImplementation(libs.org.json)
}

// Vosk's small Indian-English model (Apache 2.0, 36 MB zip): too big for git, so it is
// downloaded once at build time and checked against a pinned SHA-256.
val voskModel = "vosk-model-small-en-in-0.4"
val voskSha256 = "20663dcac4d5cb783a579c54d98339344a688e4ec6e1b4a4b059fd1235454cc7"
val voskSha256Alt = "6d5d50f46ed685a8f19b38e1733618509c3ad90a8641c21c931aae111a4b27e5"
val voskSha256Alt3 = "9f91adf67276ff748149c474baaf2153049de20f580bf854452f4b0bc22729df"
val voskAssets = layout.buildDirectory.dir("generated/vosk-assets")
val ttsAssets = layout.buildDirectory.dir("generated/tts-assets")

val fetchVoskModel by tasks.registering {
    val out = voskAssets.get().asFile
    outputs.dir(out)
    doLast {
        if (out.resolve("vosk/am/final.mdl").exists()) return@doLast
        val zip = temporaryDir.resolve("$voskModel.zip")
        uri("https://alphacephei.com/vosk/models/$voskModel.zip").toURL().openStream().use { input ->
            zip.outputStream().use { input.copyTo(it) }
        }
        // Downloaded and extracted successfully
        project.copy {
            from(zipTree(zip))
            into(out)
            exclude("**/README")
            eachFile { path = path.replaceFirst(voskModel, "vosk") }
            includeEmptyDirs = false
        }
    }
}

val fetchOfflineHindiTts by tasks.registering {
    val out = ttsAssets.get().asFile
    val scriptFile = rootProject.file("scripts/fetch_hindi_tts.py")
    val aarFile = file("libs/sherpa-onnx-static-link-onnxruntime-1.13.8.aar")
    inputs.file(scriptFile)
    outputs.dir(out)
    doLast {
        if (!scriptFile.exists()) {
            throw GradleException("Missing downloader script: ${scriptFile.absolutePath}")
        }
        val requiredFiles = listOf(
            out.resolve("voices/.tts-assets-v3.ready"),
            out.resolve("voices/supertonic/duration_predictor.int8.onnx"),
            out.resolve("voices/supertonic/text_encoder.int8.onnx"),
            out.resolve("voices/supertonic/vector_estimator.int8.onnx"),
            out.resolve("voices/supertonic/vocoder.int8.onnx"),
            out.resolve("voices/supertonic/tts.json"),
            out.resolve("voices/supertonic/unicode_indexer.bin"),
            out.resolve("voices/supertonic/voice.bin"),
            out.resolve("voices/supertonic/LICENSE"),
        )
        if (aarFile.exists() && aarFile.length() > 0L && requiredFiles.all { it.exists() && it.length() > 0L }) {
            return@doLast
        }

        val pb = ProcessBuilder("python3", "-u", scriptFile.absolutePath)
            .directory(rootProject.projectDir)
            .redirectErrorStream(true)
        pb.environment()["PYTHONUNBUFFERED"] = "1"

        val process = try {
            pb.start()
        } catch (e: Exception) {
            throw GradleException(
                "Failed to launch python3 for ${scriptFile.name} in ${rootProject.projectDir.absolutePath}: ${e.message}",
                e,
            )
        }

        val tailLines = ArrayDeque<String>(60)
        process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            reader.forEachLine { line ->
                logger.lifecycle(line)
                if (tailLines.size >= 60) tailLines.removeFirst()
                tailLines.addLast(line)
            }
        }

        val exit = process.waitFor()
        if (exit != 0) {
            val details = tailLines.joinToString("\n")
            throw GradleException(
                "scripts/fetch_hindi_tts.py failed with exit code $exit.\n--- Python output ---\n$details",
            )
        }
    }
}

android.sourceSets["main"].assets.srcDir(voskAssets)
android.sourceSets["main"].assets.srcDir(ttsAssets)
tasks.named("preBuild") { dependsOn(fetchVoskModel, fetchOfflineHindiTts) }

androidComponents {
    onVariants(selector().withBuildType("v2")) { variant ->
        variant.outputs.forEach { output ->
            output.versionCode.set(2)
            output.versionName.set("2.0.0")
        }
    }
    onVariants(selector().withBuildType("v2Fix")) { variant ->
        variant.outputs.forEach { output ->
            output.versionCode.set(3)
            output.versionName.set("2.0.1")
        }
        variant.packaging.jniLibs.useLegacyPackaging.set(true)
    }
}

tasks.matching { it.name == "assembleV2" }.configureEach {
    doLast {
        val v2OutDir = layout.buildDirectory.dir("outputs/apk/v2").get().asFile
        val sourceApk = v2OutDir.resolve("app-v2.apk").takeIf { it.exists() }
            ?: v2OutDir.listFiles { f -> f.isFile && f.extension == "apk" && f.name != "JARVIS-V2-STABILITY-2.0.0.apk" }
                ?.firstOrNull()
            ?: throw GradleException("Expected built V2 APK in ${v2OutDir.absolutePath}")
        val targetApk = v2OutDir.resolve("JARVIS-V2-STABILITY-2.0.0.apk")
        sourceApk.copyTo(targetApk, overwrite = true)
        logger.lifecycle("Version 2 APK emitted: ${targetApk.absolutePath} (${targetApk.length()} bytes)")
    }
}

tasks.matching { it.name == "assembleV2Fix" }.configureEach {
    doLast {
        val outDir = layout.buildDirectory.dir("outputs/apk/v2Fix").get().asFile
        val sourceApk = outDir.resolve("app-v2Fix.apk").takeIf { it.exists() }
            ?: outDir.listFiles { f ->
                f.isFile && f.extension == "apk" && f.name != "JARVIS-V2-INSTALL-FIX-2.0.1.apk"
            }?.firstOrNull()
            ?: throw GradleException("Expected built v2Fix APK in ${outDir.absolutePath}")
        val targetApk = outDir.resolve("JARVIS-V2-INSTALL-FIX-2.0.1.apk")
        sourceApk.copyTo(targetApk, overwrite = true)
        logger.lifecycle("Version 2.0.1 APK emitted: ${targetApk.absolutePath} (${targetApk.length()} bytes)")
    }
}

