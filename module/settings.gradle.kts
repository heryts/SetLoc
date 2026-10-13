import kotlin.io.path.Path

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        mavenLocal()
        maven("https://jitpack.io")
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        mavenLocal()
        maven("https://jitpack.io")
    }
    versionCatalogs {
        // Needs external/AndroidVMTools (run setup.sh first)
        create("androidvmtools") {
            from(files(Path(rootDir.path, "external", "AndroidVMTools", "gradle", "libs.versions.toml")))
        }
    }
}

rootProject.name = "SetLocZygisk"
include(":zygote")
