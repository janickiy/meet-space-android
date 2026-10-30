plugins { alias(libs.plugins.android.application); alias(libs.plugins.kotlin.android); alias(libs.plugins.kotlin.compose); id("com.google.gms.google-services") version "4.4.4" apply false }
val firebaseConfigured = file("google-services.json").isFile
if (firebaseConfigured) apply(plugin = "com.google.gms.google-services")
// Opt-in diagnostics only. Keep the measured baseline and all release artifacts free of LeakCanary.
val leakCanaryEnabled = providers.gradleProperty("meetspaceLeakCanary").map { it.toBooleanStrict() }.orElse(false).get()
// Supply only from a local shell/CI secret store. Never put signing credentials in Gradle files.
val releaseSigningKeys = listOf("MEETSPACE_KEYSTORE", "MEETSPACE_KEY_ALIAS", "MEETSPACE_STORE_PASSWORD", "MEETSPACE_KEY_PASSWORD")
val releaseSigningValues = releaseSigningKeys.associateWith { providers.environmentVariable(it).orNull }
val releaseSigningConfigured = releaseSigningValues.values.all { !it.isNullOrBlank() }
require(releaseSigningValues.values.all { it == null } || releaseSigningConfigured) {
    "Release signing needs all four MEETSPACE signing environment variables. See docs/android/08_RELEASE.md."
}
android {
    namespace = "com.meetspace.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.meetspace.app"
        minSdk = 26
        targetSdk = 36
        buildConfigField("boolean", "FCM_CONFIGURED", firebaseConfigured.toString())
        manifestPlaceholders["fcmConfigured"] = firebaseConfigured.toString()
        versionCode = 35
        versionName = "0.4.1-beta"
        testInstrumentationRunner = "com.meetspace.app.MeetSpaceTestRunner"
    }
    sourceSets["main"].assets.apply { srcDir(rootProject.file("installer/v1")); exclude("**/__pycache__/**") }
    signingConfigs {
        if (releaseSigningConfigured) create("meetspaceRelease") {
            storeFile = file(releaseSigningValues.getValue("MEETSPACE_KEYSTORE")!!)
            keyAlias = releaseSigningValues.getValue("MEETSPACE_KEY_ALIAS")
            storePassword = releaseSigningValues.getValue("MEETSPACE_STORE_PASSWORD")
            keyPassword = releaseSigningValues.getValue("MEETSPACE_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseSigningConfigured) signingConfig = signingConfigs.getByName("meetspaceRelease")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { jvmToolchain(17) }
dependencies {
    if (leakCanaryEnabled) {
        debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")
        androidTestImplementation("com.squareup.leakcanary:leakcanary-android-instrumentation:2.14")
    }
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("com.google.firebase:firebase-messaging:25.0.1")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:media"))
    implementation(project(":core:ssh"))
    implementation(project(":core:designsystem"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.compose.adaptive)
    implementation(libs.window.core)
    implementation(libs.android.core)
    implementation(libs.compose.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.coroutines)
    implementation(libs.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.test)
    androidTestImplementation(libs.android.test.junit)
    androidTestImplementation(libs.android.test.runner)
    androidTestImplementation(libs.espresso)
    androidTestImplementation(libs.android.test.uiautomator)
    androidTestImplementation(libs.okhttp)
    androidTestImplementation(libs.serialization.json)
    debugImplementation(libs.compose.tooling)
    debugImplementation(libs.compose.test.manifest)
}
