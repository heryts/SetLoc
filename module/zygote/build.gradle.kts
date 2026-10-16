import com.v7878.zygisk.gradle.ZygoteLoader
import kotlin.io.path.Path

plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.zygoteloader)
}

android {
    namespace = "io.gpsz.zygote"
    compileSdk = 37

    defaultConfig {
        applicationId = namespace
        minSdk = 26
        targetSdk = 36
        versionCode = 327
        versionName = "3.27"
    }

    sourceSets {
        getByName("main") {
            java {
                srcDirs(Path(rootDir.path, "external", "AndroidVMTools", "src", "main", "java"))
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

val targetPackages = (providers.gradleProperty("gpsz.packages").orNull ?: "")
    .split(",").map { it.trim() }.filter { it.isNotEmpty() }

zygisk {
    packages(*targetPackages.toTypedArray())

    id = "setloc_zygisk"
    name = "SetLoc Zygisk"
    author = "heryts"
    description = "Per-app + system-wide location spoof via Zygisk. Control: gpsz.sh"
    entrypoint = "io.gpsz.zygote.ZygoteEntry"
    archiveName = "SetLocZygisk"
    isAddVariantToArchiveName = true
}

dependencies {
    implementation(libs.androidx.annotation.jvm)
    implementation(libs.r8annotations)

    api(androidvmtools.panama.core)
    api(androidvmtools.panama.unsafe)
    api(androidvmtools.panama.llvm)
    implementation(androidvmtools.sun.cleaner)
}
