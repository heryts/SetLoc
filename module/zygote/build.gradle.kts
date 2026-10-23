import com.v7878.zygisk.gradle.ZygoteLoader
import kotlin.io.path.Path
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

    sourceSets {
        getByName("main") {
            java {
                directories.add(Path(rootDir.path, "external", "AndroidVMTools", "src", "main", "java").toString())
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        jniLibs {
            keepDebugSymbols += "**/libzygisk_loader.so"
            // libshadowhook.so datang dua kali: salinan CMake (prefab, obj/) dan dari AAR (jni/).
            // Keduanya sama (shadowhook 2.0.0), ambil satu supaya tidak ada peringatan AGP.
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

configurations.configureEach {
    exclude(group = "io.github.vova7878", module = "SunUnsafeWrapper")
}

dependencies {
    implementation(project(":unsafe"))
    implementation(libs.r8annotations)
    implementation(libs.lsplant)
    implementation(libs.shadowhook)

    api(androidvmtools.panama.core)
    api(androidvmtools.panama.unsafe)
    api(androidvmtools.panama.llvm)
    implementation(androidvmtools.sun.cleaner)
}
