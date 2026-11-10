plugins {
    id("com.android.library") version "8.7.3" apply false
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.gms.google-services") version "4.4.2" apply false
}

// JitPack publishing: each Android library publishes its release AAR plus a sources jar.
// group and version come from gradle.properties; JitPack overrides version with the git tag.
subprojects {
    plugins.withId("com.android.library") {
        apply(plugin = "maven-publish")
        extensions.configure<com.android.build.gradle.LibraryExtension> {
            publishing { singleVariant("release") { withSourcesJar() } }
        }
        afterEvaluate {
            extensions.configure<PublishingExtension> {
                publications { create<MavenPublication>("release") { from(components["release"]) } }
            }
        }
    }
}
