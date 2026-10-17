import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.dokka.javadoc) apply false
}

allprojects {
    group = "com.brudcast"
    version = providers.gradleProperty("sdkVersion").get()
}

subprojects {
    plugins.withId("com.vanniktech.maven.publish") {
        if (providers.gradleProperty("signingInMemoryKey").isPresent) {
            extensions.configure<MavenPublishBaseExtension> { signAllPublications() }
        }
    }

    plugins.withId("org.jetbrains.dokka-javadoc") {
        extensions.configure<MavenPublishBaseExtension> {
            configure(AndroidSingleVariantLibrary(variant = "release", sourcesJar = true, publishJavadocJar = false))
        }
        val javadocJar = tasks.register<Jar>("dokkaJavadocJar") {
            archiveClassifier.set("javadoc")
            from(tasks.named("dokkaGeneratePublicationJavadoc"))
        }
        extensions.configure<PublishingExtension> {
            publications.withType<MavenPublication>().configureEach { artifact(javadocJar) }
        }
    }
}
