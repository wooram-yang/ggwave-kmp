import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

// Shared setup for the published libraries: jvm, android and (on macOS hosts) iOS targets.
pluginManager.apply("org.jetbrains.kotlin.multiplatform")
pluginManager.apply("com.android.kotlin.multiplatform.library")

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val isMacOs = System.getProperty("os.name").orEmpty().startsWith("Mac")

extensions.configure<KotlinMultiplatformExtension> {
    jvmToolchain(21)
    jvm()

    targets.withType<KotlinMultiplatformAndroidLibraryTarget>().configureEach {
        compileSdk = libs.findVersion("android-compileSdk").get().requiredVersion.toInt()
        minSdk = libs.findVersion("android-minSdk").get().requiredVersion.toInt()
        compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
    }

    if (isMacOs) {
        iosArm64()
        iosSimulatorArm64()
        iosX64()
    }

    // jvm and android share java/JNI based code in jvmSharedMain.
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jvmShared") {
                withJvm()
                withCompilations { it.platformType == KotlinPlatformType.androidJvm }
            }
        }
    }

    sourceSets.named("commonTest") {
        dependencies {
            implementation(libs.findLibrary("kotlin-test").get())
        }
    }
}
