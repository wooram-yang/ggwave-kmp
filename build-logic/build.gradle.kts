plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation("com.vanniktech:gradle-maven-publish-plugin:${libs.versions.mavenPublish.get()}")
    // Applied through the root project's `apply false` declarations; only the DSL types are needed here.
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    compileOnly("com.android.tools.build:gradle-api:${libs.versions.agp.get()}")
}
