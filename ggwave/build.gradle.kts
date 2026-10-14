import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.mavenPublish)
}

val isMacOs = System.getProperty("os.name").orEmpty().startsWith("Mac")

kotlin {
    jvmToolchain(21)
    jvm()

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

mavenPublishing {
    coordinates(
        providers.gradleProperty("LIBRARY_GROUP").get(),
        "ggwave-kmp",
        providers.gradleProperty("LIBRARY_VERSION").get(),
    )
    publishToMavenCentral()
    if (providers.gradleProperty("signingInMemoryKey").orNull != null) {
        signAllPublications()
    }
    pom {
        name.set("ggwave-kmp")
        description.set("Kotlin Multiplatform audio sessions for sending and receiving data with ggwave.")
        inceptionYear.set("2024")
        url.set("https://github.com/wooram-yang/ggwave-kmp")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("wooram-yang")
                name.set("Wooram Yang")
                url.set("https://github.com/wooram-yang")
            }
        }
        scm {
            url.set("https://github.com/wooram-yang/ggwave-kmp")
            connection.set("scm:git:git://github.com/wooram-yang/ggwave-kmp.git")
            developerConnection.set("scm:git:ssh://git@github.com/wooram-yang/ggwave-kmp.git")
        }
    }
}

publishing.repositories.maven {
    name = "GitHubPackages"
    url = uri("https://maven.pkg.github.com/wooram-yang/ggwave-kmp")
    credentials {
        username = providers.environmentVariable("GITHUB_ACTOR")
            .orElse(providers.gradleProperty("gpr.user"))
            .orElse("wooram-yang")
            .get()
        password = providers.environmentVariable("GITHUB_TOKEN")
            .orElse(providers.gradleProperty("gpr.key"))
            .orElse("")
            .get()
    }
}
