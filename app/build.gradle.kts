import com.android.build.api.variant.impl.VariantOutputImpl
import com.android.build.api.variant.FilterConfiguration
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

abstract class StageMdxModel : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val model: RegularFileProperty
    @get:Input abstract val fileName: Property<String>
    @get:Input abstract val size: Property<Long>
    @get:Input abstract val sha256: Property<String>
    @get:OutputDirectory abstract val assets: DirectoryProperty

    @TaskAction
    fun stage() {
        val source = model.get().asFile
        require(source.length() == size.get()) { "Unexpected size for the bundled MDX-Net model" }
        val digest = MessageDigest.getInstance("SHA-256")
        source.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        require(hash == sha256.get()) { "Incorrect SHA-256 for the bundled MDX-Net model" }
        source.copyTo(assets.file("karaoke-models/${fileName.get()}").get().asFile, overwrite = true)
    }
}

val mdxModelSpec = Properties().apply {
    rootProject.file("gradle/mdx-model.properties").inputStream().use(::load)
}
val bundledMdxModel = providers.gradleProperty("nativeBundledMdxModel")
val stageMdxModel = tasks.register<StageMdxModel>("stageMdxModel") {
    model.fileProvider(bundledMdxModel.map { rootProject.file(it) })
    fileName.set(mdxModelSpec.getProperty("file"))
    size.set(mdxModelSpec.getProperty("size").toLong())
    sha256.set(mdxModelSpec.getProperty("sha256"))
    assets.set(layout.buildDirectory.dir("generated/mdx-model/assets"))
}

val localProperties = Properties().apply {
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.isFile) propertiesFile.reader(Charsets.UTF_8).use { load(it) }
}
val releaseSigningValues = listOf("NATIVE_STORE_PASSWORD", "NATIVE_KEY_ALIAS", "NATIVE_KEY_PASSWORD")
    .associateWith { localProperties.getProperty(it) }
val releaseKeystore = rootProject.file(
    localProperties.getProperty("NATIVE_KEYSTORE_PATH")?.takeIf { it.isNotBlank() } ?: "NewKeyStrore.jks"
)
val onnxRuntimeVersion = "1.29.0"
val onnxRuntimeOnline = providers.gradleProperty("nativeOnnxRuntimeOnline").map { it.toBooleanStrict() }.orElse(false)
val onnxRuntime = configurations.create("onnxRuntime") { isTransitive = false }
val onnxRuntimeDir = layout.buildDirectory.dir("generated/onnxruntime")
val extractOnnxRuntime = tasks.register<Sync>("extractOnnxRuntime") {
    from(onnxRuntime.elements.map { files -> zipTree(files.single().asFile) }) {
        include("headers/**", "jni/**/libonnxruntime.so")
    }
    into(onnxRuntimeDir)
}
val validateNativeReleaseSigning = tasks.register("validateNativeReleaseSigning") {
    group = "verification"
    description = "Checks local.properties and the keystore before building a release."
    val missingProperties = releaseSigningValues.filterValues { it.isNullOrBlank() }.keys.toList()
    val keystore = releaseKeystore
    doLast {
        if (missingProperties.isNotEmpty() || !keystore.isFile) {
            throw GradleException(buildString {
                appendLine("Release 签名配置不完整，无法构建。请检查仓库根目录的 local.properties：")
                if (missingProperties.isNotEmpty()) {
                    appendLine("缺少或为空的配置项：${missingProperties.joinToString()}")
                }
                if (!keystore.isFile) {
                    appendLine("签名文件不存在：$keystore")
                    appendLine("请放入 NewKeyStrore.jks，或设置 NATIVE_KEYSTORE_PATH（相对仓库根目录或绝对路径）。")
                }
                append("配置示例见 RELEASING.md；local.properties 和密钥文件已被 Git 忽略。")
            })
        }
    }
}

android {
    ndkVersion = "27.2.12479018"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    namespace = "cn.toside.music.nativeapp"
    compileSdk = 36
    defaultConfig {
        // Keep the migration build separate from existing installations and their data.
        applicationId = "cn.toside.music.nativeapp"
        minSdk = 28
        targetSdk = 36
        versionCode = providers.gradleProperty("nativeVersionCode").get().toInt().also { require(it > 0) }
        versionName = providers.gradleProperty("nativeVersionName").get().also { require(it.matches(Regex("\\d+\\.\\d+\\.\\d+"))) }
        testInstrumentationRunner = "cn.toside.music.nativeapp.NativeTestRunner"
        resValue("string", "onnx_runtime_version", onnxRuntimeVersion)
        // The universal APK also includes the x86 QuickJS libraries from its AAR.
        // Build every matching encoder ABI even when separate ARM APKs are requested.
        externalNativeBuild {
            cmake {
                abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
                arguments += "-DONNX_HEADERS=${onnxRuntimeDir.get().asFile.resolve("headers").invariantSeparatorsPath}"
            }
        }
    }
    // CI enables this only after testing first-use permission dialogs.
    if (providers.gradleProperty("nativeTestGrantPermissions").map { it.toBooleanStrict() }.orElse(false).get()) {
        installation { installOptions.add("-g") }
    }
    signingConfigs.create("nativeRelease") {
        storeFile = releaseKeystore
        storePassword = releaseSigningValues["NATIVE_STORE_PASSWORD"]
        keyAlias = releaseSigningValues["NATIVE_KEY_ALIAS"]
        keyPassword = releaseSigningValues["NATIVE_KEY_PASSWORD"]
    }
    buildTypes.getByName("release") {
        signingConfig = signingConfigs.getByName("nativeRelease")
    }
    buildTypes.create("modelRelease") {
        initWith(buildTypes.getByName("release"))
        matchingFallbacks += "release"
    }
    splits.abi {
        isEnable = providers.gradleProperty("nativeSplitApks").map { it.toBooleanStrict() }.orElse(false).get()
        reset()
        include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        isUniversalApk = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("main").jniLibs.srcDir(onnxRuntimeDir.map { it.dir("jni") })
    kotlinOptions { jvmTarget = "17" }
}

androidComponents {
    beforeVariants(selector().withBuildType("modelRelease")) { variant ->
        variant.enable = bundledMdxModel.isPresent
    }
    onVariants { variant ->
        val editionSuffix = when (variant.buildType) {
            "release" -> ""
            "modelRelease" -> {
                variant.sources.assets?.addGeneratedSourceDirectory(stageMdxModel, StageMdxModel::assets)
                "-with-model"
            }
            else -> return@onVariants
        }
        variant.outputs.forEach { output ->
            val abi = output.filters.firstOrNull { it.filterType == FilterConfiguration.FilterType.ABI }?.identifier
            if (abi == "x86" || abi == "x86_64") {
                output.enabled.set(false)
                return@forEach
            }
            val architecture = when (abi) {
                "armeabi-v7a" -> "armv7"
                "arm64-v8a" -> "armv8"
                null -> "all"
                else -> error("Unexpected release APK ABI")
            }
            // AGP 8.13 exposes APK filenames on its output implementation.
            (output as VariantOutputImpl).outputFileName.set(
                output.versionName.map { "lx-music-native-v$it-$architecture$editionSuffix.apk" }
            )
        }
    }
}

tasks.matching {
    it.name in setOf("preReleaseBuild", "validateSigningRelease", "preModelReleaseBuild", "validateSigningModelRelease")
}.configureEach {
    dependsOn(validateNativeReleaseSigning)
}
tasks.matching {
    it.name.startsWith("configureCMake") || it.name.startsWith("generateJsonModel") || it.name.endsWith("JniLibFolders")
}.configureEach {
    dependsOn(extractOnnxRuntime)
}

dependencies {
    if (onnxRuntimeOnline.get()) {
        add(onnxRuntime.name, "com.microsoft.onnxruntime:onnxruntime-android:$onnxRuntimeVersion@aar")
    } else {
        add(onnxRuntime.name, files(rootProject.file("third_party/onnxruntime/onnxruntime-android-$onnxRuntimeVersion.aar")))
    }
    implementation(libs.jaudiotagger)
    implementation(libs.androidx.core)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.zxing.core)
    implementation(libs.quickjs)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.datasource.okhttp)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
