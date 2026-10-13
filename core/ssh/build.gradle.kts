plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.android) }
android {
    namespace = "com.meetspace.core.ssh"
    compileSdk = 36
    defaultConfig { minSdk = 26; consumerProguardFiles("consumer-rules.pro") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":core:model"))
    implementation(libs.serialization.json)
    implementation(libs.coroutines)
    implementation(libs.datastore)
    implementation(libs.okhttp)
    implementation(libs.jsch)
    implementation(libs.bouncycastle)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

// InstallerProtocolTest reads these signed recipe files outside the module's source sets.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    inputs.files(listOf("runner.py", "release.json", "release.sig", "trust-root.pem", "docker.asc")
        .map { rootProject.file("installer/v1/$it") })
        .withPropertyName("signedInstallerBundle")
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
}
