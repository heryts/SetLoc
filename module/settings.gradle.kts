import kotlin.io.path.Path

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
    versionCatalogs {

        create("androidvmtools") {
            from(files(Path(rootDir.path, "external", "AndroidVMTools", "gradle", "libs.versions.toml")))
        }
    }
}

rootProject.name = "SetLocZygisk"
include(":zygote")
