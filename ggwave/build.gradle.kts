import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    id("ggwave.publishing")
}

val isMacOs = System.getProperty("os.name").orEmpty().startsWith("Mac")

kotlin {
    jvmToolchain(21)
    jvm()

    // jvm and android share java.util.concurrent based helpers in jvmSharedMain.
    applyDefaultHierarchyTemplate {
        common {
            group("jvmShared") {
                withJvm()
                withCompilations { it.platformType == KotlinPlatformType.androidJvm }
            }
        }
    }

    android {
        namespace = "io.github.moreflow.ggwave"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
    }

    if (isMacOs) {
        iosArm64()
        iosSimulatorArm64()
        iosX64()
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.ggwaveCore)
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
