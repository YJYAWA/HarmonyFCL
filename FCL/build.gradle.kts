import com.android.build.api.variant.FilterConfiguration.FilterType.ABI
import com.android.build.gradle.tasks.MergeSourceSetFolders
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    id("checkstyle")
}

checkstyle {
    // 规则集 config/checkstyle/checkstyle.xml 与 Android Studio 默认格式对齐，仅检查 Java 代码
    toolVersion = "10.12.5"
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
}

/**
 * `-Darch` 支持单个架构（arm/arm64/x86/x86_64）或逗号分隔的列表（如 `-Darch=arm64,arm`），
 * `all` 表示不过滤。列表形式下 AGP 的 ABI splits 会为每个 ABI 各产出一个 APK。
 */
val archTokens: List<String> = System.getProperty("arch", "all")
    .split(',')
    .map { it.trim() }
    .filter { it.isNotEmpty() }

/** 是否按架构裁剪（arch=all 时不裁剪） */
val filterByArch: Boolean = archTokens.isNotEmpty() && archTokens.none { it == "all" }

/** arch token（arm/arm64/x86/x86_64）→ ABI 名（armeabi-v7a/arm64-v8a/…） */
fun archTokenToAbi(token: String): String? = when (token) {
    "arm" -> "armeabi-v7a"
    "arm64" -> "arm64-v8a"
    "x86" -> "x86"
    "x86_64" -> "x86_64"
    else -> null
}

/** 本次构建保留的 ABI 集合；`-Darch=all` 时为 null（不裁剪） */
val targetAbis: Set<String>? =
    if (!filterByArch) null else archTokens.mapNotNull { archTokenToAbi(it) }.toSet()

// AGP 不提供 Java 插件的 SourceSetContainer，checkstyle 插件不会自动创建任务，
// 因此手动注册 checkstyle 任务，检查范围为主源码目录的 Java 文件
tasks.register<Checkstyle>("checkstyle") {
    description = "Run checkstyle on the FCL Java sources."
    group = "verification"
    source(layout.projectDirectory.dir("src/main/java"))
    include("**/*.java")
    classpath = files()
    maxErrors = 0
    maxWarnings = 0
}

/**
 * 本 fork 自签名密钥（`harmony-fcl.jks`）的查找顺序，取第一个存在的：
 *   1. Gradle 属性  `-PharmonyFclKeystore=<path>`
 *   2. 环境变量     `HARMONYFCL_KEYSTORE`
 *   3. 仓库根目录下的 `harmony-fcl.jks`
 *   4. 仓库上一级目录下的 `harmony-fcl.jks`
 *
 * 密钥**不随仓库分发**（见 .gitignore）：换密钥等于换签名，拿到它的人可以伪造出
 * 「同包名 + 同签名」的包让已安装的用户当成合法升级装上。要长期发布就自行生成一份并
 * 妥善保存，生成命令见 BUILD.md。口令与别名同样可覆盖（`-PharmonyFclStorePassword` /
 * `HARMONYFCL_STORE_PASSWORD`、`-PharmonyFclKeyAlias` / `HARMONYFCL_KEY_ALIAS`）。
 *
 * 一个都找不到时，构建不会失败：回落到仓库自带的 `debug-key.jks`（见下方 releaseSigning）。
 */
val harmonyKeystore: File? = listOfNotNull(
    System.getProperty("harmonyFclKeystore"),
    System.getenv("HARMONYFCL_KEYSTORE"),
    rootProject.file("harmony-fcl.jks").absolutePath,
    rootProject.file("../harmony-fcl.jks").absolutePath,
).map { File(it) }.firstOrNull { it.isFile }

val harmonyStorePassword: String = System.getProperty("harmonyFclStorePassword")
    ?: System.getenv("HARMONYFCL_STORE_PASSWORD")
    ?: "HarmonyFCL"

val harmonyKeyAlias: String = System.getProperty("harmonyFclKeyAlias")
    ?: System.getenv("HARMONYFCL_KEY_ALIAS")
    ?: "harmonyfcl"

android {
    namespace = "com.tungsten.fcl"
    compileSdk = libs.versions.compileSdk.get().toInt()

    var localProperty: Properties? = null
    if (file("${rootDir}/local.properties").exists()) {
        localProperty = Properties()
        file("${rootDir}/local.properties").inputStream().use { localProperty.load(it) }
    }
    val pwd = System.getenv("FCL_KEYSTORE_PASSWORD") ?: localProperty?.getProperty("pwd")
    val curseApiKey = System.getenv("CURSE_API_KEY") ?: localProperty?.getProperty("curse.api.key")
    val oauthApiKey = System.getenv("OAUTH_API_KEY") ?: localProperty?.getProperty("oauth.api.key")
    // 命令行 -Darch 优先；local.properties 仅在命令行未指定时生效
    if (System.getProperty("arch") == null && localProperty != null && localProperty.getProperty(
            "arch",
            "all"
        ) == "arm64"
    )
        System.setProperty("arch", "arm64")

    signingConfigs {
        create("FCLKey") {
            storeFile = file("../key-store.jks")
            storePassword = pwd
            keyAlias = "FCL-Key"
            keyPassword = pwd
        }
        create("FCLDebugKey") {
            storeFile = file("../debug-key.jks")
            storePassword = "FCL-Debug"
            keyAlias = "FCL-Debug"
            keyPassword = "FCL-Debug"
        }
        // 本 fork 的自签名密钥（有效期 10000 天 → 2054），路径按上面 harmonyKeystore 的顺序解析。
        // 生成命令见 BUILD.md「签名密钥」一节。
        create("HarmonyFCLSelfSigned") {
            harmonyKeystore?.let {
                storeFile = it
                storePassword = harmonyStorePassword
                keyAlias = harmonyKeyAlias
                keyPassword = harmonyStorePassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.harmony.fcl"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // 内置 MobileGlues 的 fork 版本。两条约定：
        //   versionName 与上游同步（FCL 自身有基于 versionName 的解析，不能自作主张）
        //   versionCode 取上游 +1，与官方包区分开——本 fork 包名是 com.harmony.fcl，
        //   本就可与官方版共存，versionCode 只用于自己这边的升级判定
        versionCode = 1337
        versionName = "1.3.3.6"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                arguments("-DANDROID_STL=c++_shared")
            }
        }
    }

    testBuildType = "debug"

    // 单元测试也要能读到 /assets/game/versions.txt。GameVersionNumber 的静态初始化会用它
    // （Android 上靠 APK 的 zip 条目解析，JVM 单测的 classpath 里没有）。
    // 不挂的话，任何碰 GameVersionNumber 的测试都会拿到 ExceptionInInitializerError，
    // 然后被各处的 runCatching 吞掉、悄悄跑到回落分支——看着是绿的，其实什么都没验到。
    //
    // 不能直接 srcDir("src/main/assets")：那样资源根变成 assets/ 本身，只会得到
    // /game/versions.txt，和代码要的 /assets/game/versions.txt 对不上。
    // 所以先生成一份只含 assets/game/ 的资源根。
    val unitTestGameAssets = tasks.register<Sync>("unitTestGameAssets") {
        from(layout.projectDirectory.dir("src/main/assets/game")) {
            include("versions.txt", "unlisted-versions.json", "version-alias.csv")
        }
        into(layout.buildDirectory.dir("unitTestAssets/assets/game"))
    }
    sourceSets.getByName("test") {
        resources.srcDir(layout.buildDirectory.dir("unitTestAssets"))
    }
    tasks.matching { it.name == "processDebugUnitTestJavaRes" || it.name == "processReleaseUnitTestJavaRes" }
        .configureEach { dependsOn(unitTestGameAssets) }

    // 本 fork 只出一个包（包名 com.harmony.fcl，桌面名 Harmony FCL）。包名与官方版不同，
    // 可以与官方版 FCL 共存安装。
    //
    // 找不到自签名密钥时回落到仓库自带的 debug-key.jks —— 这样任何人 clone 下来都能直接构建，
    // 不必先有密钥。代价是产物带的是上游的调试签名（CN=FCL-Debug），与正式发布包签名不同，
    // 不能覆盖安装正式包。要发布就自己生成一份 harmony-fcl.jks（见 BUILD.md）。
    val releaseSigning = if (harmonyKeystore != null) {
        signingConfigs.getByName("HarmonyFCLSelfSigned")
    } else {
        logger.warn(
            "==> 未找到 harmony-fcl.jks（已查找：-PharmonyFclKeystore 属性、" +
                "HARMONYFCL_KEYSTORE 环境变量、仓库根目录、仓库上一级目录）。" +
                "本次构建回落到仓库自带的 debug-key.jks，产物是调试签名，" +
                "不能覆盖安装正式发布包。"
        )
        signingConfigs.getByName("FCLDebugKey")
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = releaseSigning
        }
        getByName("debug") {
            isMinifyEnabled = false
            signingConfig = releaseSigning
        }
        configureEach {
            resValue("string", "app_version", defaultConfig.versionName.toString())
            resValue("string", "curse_api_key", curseApiKey.toString())
            resValue("string", "oauth_api_key", oauthApiKey.toString())
        }
    }



    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // core library desugaring：java.time / java.util.stream / Optional 等脱糖到 minSdk 26 可用
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += listOf("**/libbytehook.so")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        resValues = true
        prefab = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
        }
    }

    // 上游声明 27.0.12077973；本地只有 27.3.13750724（同一大版本），复用已装的这一个
    ndkVersion = "27.3.13750724"

    splits {
        if (targetAbis != null) {
            abi {
                isEnable = true
                reset()
                targetAbis.forEach { include(it) }
            }
        }
    }
}

/**
 * 按 -Darch 过滤 JRE 压缩包，生成当前架构需要的资产目录。
 * 非 all 构建只保留 version、universal 和 bin-<arch>.tar.xz（运行时两者都需要，见 RuntimeUtils#installJava）。
 */
abstract class FilterJreAssets : Sync() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty
}

val filterJreAssets = tasks.register<FilterJreAssets>("filterJreAssets") {
    val arch = System.getProperty("arch", "all")
    val keepJre = archTokens.map { "bin-$it.tar.xz" }.toSet()
    // Copy/Sync 的 up-to-date 检查不包含 copy spec 的过滤规则，必须显式声明 arch 输入，
    // 否则切换架构时任务不会重跑，产物会残留上一架构的 JRE 包
    inputs.property("arch", arch)
    from(layout.projectDirectory.dir("src/main/jreAssets"))
    into(outputDir)
    if (filterByArch) {
        exclude { it.name.startsWith("bin-") && !keepJre.contains(it.name) }
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            if (output is com.android.build.api.variant.impl.VariantOutputImpl) {
                (output.getFilter(ABI)?.identifier ?: "all").let { abi ->
                    output.outputFileName =
                        "HarmonyFCL-${project.android.defaultConfig.versionName}-${abi}.apk"
                }
            }
        }

        // JRE 资产不放在 src/main/assets 里（AGP 的 mergeAssets 不应用 source set 的 exclude 过滤），
        // 而是按架构注册为生成源；arch 变化会让 Sync 任务重新执行，mergeAssets 随之重跑，避免产物残留旧架构文件。
        if (System.getProperty("arch", "all") != "all") {
            variant.sources.assets?.addGeneratedSourceDirectory(filterJreAssets) { it.outputDir }
        } else {
            variant.sources.assets?.addStaticSourceDirectory("src/main/jreAssets")
        }

        // LWJGL natives 打包在 lwjgl-*-natives aar 的 assets/app_runtime/lwjgl/<版本>/natives/<abi> 下，
        // JNA natives 在 assets/app_runtime/jna/<版本>/natives/<abi> 下，
        // 均不走 AGP 的 abiFilters，需在 mergeAssets 后手动按架构删除其他 ABI 的 natives 目录。
        val variantName = variant.name.replaceFirstChar { it.uppercaseChar() }
        afterEvaluate {
            val mergeAssets =
                tasks.named("merge${variantName}Assets", MergeSourceSetFolders::class.java)
            val arch = System.getProperty("arch", "all")
            // 显式声明 arch 输入，arch 变化时任务重跑，避免产物残留上一架构的 natives
            mergeAssets.configure { inputs.property("lwjglArch", arch) }
            mergeAssets.configure {
                doLast {
                    val keepAbis = targetAbis ?: return@doLast
                    if (keepAbis.isEmpty()) return@doLast
                    val assetsDir = outputDir.get().asFile
                    // 版本列表从 libs 下的 lwjgl-*-natives-release.aar 文件名推导，避免新增版本时忘记同步
                    val lwjglVersions = project.file("libs").listFiles { f ->
                        f.name.matches(Regex("lwjgl-\\d+\\.\\d+\\.\\d+-natives-release\\.aar"))
                    }
                        ?.map { Regex("lwjgl-(\\d+\\.\\d+\\.\\d+)-natives-release\\.aar").find(it.name)!!.groupValues[1] }
                        ?: emptyList()
                    lwjglVersions.forEach { version ->
                        val nativesDir = File(assetsDir, "app_runtime/lwjgl/$version/natives")
                        if (nativesDir.isDirectory) {
                            nativesDir.listFiles()?.forEach { dir ->
                                if (dir.isDirectory && !keepAbis.contains(dir.name)) {
                                    logger.lifecycle("删除非目标架构 natives: $dir")
                                    dir.deleteRecursively()
                                }
                            }
                        }
                    }
                    // JNA natives 在 assets/app_runtime/jna/<版本>/natives/<abi> 下，同样按架构裁剪
                    File(assetsDir, "app_runtime/jna").listFiles()?.forEach { versionDir ->
                        val nativesDir = File(versionDir, "natives")
                        if (nativesDir.isDirectory) {
                            nativesDir.listFiles()?.forEach { dir ->
                                if (dir.isDirectory && !keepAbis.contains(dir.name)) {
                                    logger.lifecycle("删除非目标架构 natives: $dir")
                                    dir.deleteRecursively()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Room schema 导出（收藏库用，为将来迁移留底）
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
    implementation(project(":ZipFileSystem"))
    implementation(project(":Terracotta"))
    implementation(libs.commons.io)
    implementation(libs.jelf)
    implementation(libs.nanohttpd)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.opennbt)
    implementation(libs.lz4)
    implementation(libs.gson)
    implementation(libs.tomlj)
    implementation(libs.constant.pool.scanner)
    implementation(libs.jsoup)
    implementation(libs.chardet)
    implementation(libs.junrar)
    implementation(libs.bytehook)
    implementation(libs.appcompat)
    implementation(libs.androidx.viewpager2)
    implementation(libs.core.splashscreen)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.recyclerview)
    implementation(libs.coroutines.android)
    implementation(libs.glide)
    implementation(libs.touchcontroller)
    implementation(libs.palette.ktx)
    implementation(libs.gamepad.remapper)
    implementation(libs.segmented.button)
    implementation(libs.datastore)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

tasks.register("updateMap") {
    doLast {
        val list = mutableListOf<String>()
        val mapFile = file("${rootDir}/version_map.json")
        mapFile.forEachLine {
            list.add(
                when {
                    it.contains("versionCode") -> it.replace(
                        Regex("[0-9]+"),
                        android.defaultConfig.versionCode.toString()
                    )

                    it.contains("versionName") -> it.replace(
                        Regex("\\d+(\\.\\d+)+"),
                        android.defaultConfig.versionName.toString()
                    )

                    it.contains("date") -> it.replace(
                        Regex("\\d{4}\\.\\d{2}\\.\\d{2}"),
                        SimpleDateFormat("yyyy.MM.dd").format(Date())
                    )

                    it.contains("url") -> it.replace(
                        Regex("\\d+(\\.\\d+)+"),
                        android.defaultConfig.versionName.toString()
                    )

                    else -> it
                }
            )
        }
        mapFile.writeText(list.joinToString("\n"), Charsets.UTF_8)
    }
}