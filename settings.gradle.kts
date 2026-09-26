pluginManagement {
    repositories {
        // 国内镜像放最前，见下方 dependencyResolutionManagement 的注释
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 国内镜像放在最前：Maven Central 上有一部分构件（如
        // org.jetbrains.kotlin:kotlin-compiler-embeddable）会 301 跳到 github.com 的
        // release 资源，国内网络常常取不到（部分网络下还会因证书链不被信任而 PKIX 失败）。
        // 镜像里没有的构件返回 404，Gradle 会自动回落到下面的 google()/mavenCentral()。
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
rootProject.name = "Fold Craft Launcher"
include(":FCL")
include(":Terracotta")
include(":ZipFileSystem")
include(":LWJGL")
// 子项目名以数字开头会导致 IntelliJ/Android Studio 无法正确解析模块依赖，
// 因此使用合法名称并重定向 projectDir（目录本身保持版本号命名）
include(":LWJGL:lwjgl-3.3.3")
project(":LWJGL:lwjgl-3.3.3").projectDir = file("LWJGL/3.3.3")
include(":LWJGL:lwjgl-3.4.1")
project(":LWJGL:lwjgl-3.4.1").projectDir = file("LWJGL/3.4.1")
