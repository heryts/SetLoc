import com.v7878.zygisk.gradle.ZygoteLoader
import java.util.Properties

plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.zygoteloader)
}

val ver = Properties().apply { file("../../version.properties").inputStream().use { load(it) } }

android {
    namespace = "io.gpsz.zygote"
    compileSdk = 37

    defaultConfig {
        applicationId = namespace
        minSdk = 33
        targetSdk = 36
        versionCode = ver.getProperty("versionCode").toInt()
        versionName = ver.getProperty("versionName")

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments("-DANDROID_STL=c++_static")
            }
        }
    }

    buildFeatures {
        prefab = true
    }

    externalNativeBuild {
        cmake {
            path = file("../../native/hook/CMakeLists.txt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        jniLibs {
            keepDebugSymbols += "**/libzygisk_loader.so"
            pickFirsts += "**/libshadowhook.so"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:deprecation")
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
    implementation(libs.r8annotations)
    implementation(libs.lsplant)
    implementation(libs.shadowhook)
}
