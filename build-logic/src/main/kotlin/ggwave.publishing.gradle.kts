plugins {
    id("com.vanniktech.maven.publish")
}

// Each published module declares these in its own gradle.properties, which only project properties see.
fun requireModuleProperty(name: String): String =
    findProperty(name)?.toString() ?: error("${project.path} must define $name in its gradle.properties")

val artifactId = requireModuleProperty("GGWAVE_ARTIFACT_ID")
val artifactDescription = requireModuleProperty("GGWAVE_ARTIFACT_DESCRIPTION")

mavenPublishing {
    coordinates(
        providers.gradleProperty("LIBRARY_GROUP").get(),
        artifactId,
        providers.gradleProperty("LIBRARY_VERSION").get(),
    )
    publishToMavenCentral()
    if (providers.gradleProperty("signingInMemoryKey").orNull != null) {
        signAllPublications()
    }
    pom {
        name.set(artifactId)
        description.set(artifactDescription)
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
