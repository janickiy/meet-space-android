plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.android); alias(libs.plugins.kotlin.serialization) }
android {
    namespace = "com.meetspace.core.data"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { jvmToolchain(17) }
tasks.withType<Test>().configureEach {
    val live05 = System.getenv("MEETSPACE_STAGE05_LIVE") == "1"
    val live06 = System.getenv("MEETSPACE_STAGE06_LIVE") == "1"
    val live07 = System.getenv("MEETSPACE_STAGE07_LIVE") == "1"
    val live08 = System.getenv("MEETSPACE_STAGE08_LIVE") == "1"
    val liveNotifications = System.getenv("MEETSPACE_NOTIFICATION_LIVE") == "1"
    val liveApi = System.getenv("MEETSPACE_API_LIVE") == "1"
    val liveCurrentApi = System.getenv("MEETSPACE_CURRENT_API_FIXTURE") != null
    inputs.property("stage08Live", live08)
    inputs.property("stage05Live", live05)
    inputs.property("stage06Live", live06)
    inputs.property("stage07Live", live07)
    inputs.property("notificationPreferencesLive", liveNotifications)
    inputs.property("apiCompatibilityLive", liveApi)
    inputs.property("currentApiFixture", liveCurrentApi)
    outputs.upToDateWhen { !live05 && !live06 && !live07 && !live08 && !liveNotifications && !liveApi && !liveCurrentApi }
}
dependencies {
    api(project(":core:model"))
    implementation(libs.datastore)
    implementation(libs.okhttp)
    implementation(libs.coroutines)
    implementation(libs.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
}
