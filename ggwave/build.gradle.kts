plugins {
    id("ggwave.kmp-library")
    id("ggwave.publishing")
}

kotlin {
    android {
        namespace = "io.github.moreflow.ggwave"
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.ggwaveCore)
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
