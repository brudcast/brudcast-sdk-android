plugins {
    `java-platform`
    alias(libs.plugins.maven.publish)
}

dependencies {
    constraints {
        api(project(":sdk-core"))
        api(project(":sdk-inapp"))
        api(project(":sdk-push"))
        api(project(":sdk-push-firebase"))
    }
}
