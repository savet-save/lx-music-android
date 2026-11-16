import com.android.build.api.variant.impl.VariantOutputImpl
import com.android.build.api.variant.FilterConfiguration
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
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
        // The universal APK also includes the x86 QuickJS libraries from its AAR.
        // Build every matching encoder ABI even when separate ARM APKs are requested.
        externalNativeBuild { cmake { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64") } }
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
    kotlinOptions { jvmTarget = "17" }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output ->
            val abi=output.filters.firstOrNull { it.filterType == FilterConfiguration.FilterType.ABI }?.identifier
            if(abi in setOf("x86", "x86_64")) {
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
                output.versionName.map { "lx-music-native-v$it-$architecture.apk" }
            )
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" || it.name == "validateSigningRelease" }.configureEach {
    dependsOn(validateNativeReleaseSigning)
}

dependencies {
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
