plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "cn.liuzf.lighthouse"
    compileSdk = 35
    defaultConfig {
        applicationId = "cn.liuzf.lighthouse"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.5.0-test"
        val xiaomiId = providers.gradleProperty("xiaomiAppId").orElse("").get()
        buildConfigField("String", "XIAOMI_APP_ID", "\"$xiaomiId\"")
        manifestPlaceholders["xiaomiAppId"] = xiaomiId
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs {
        getByName("debug") {
            storeFile = file(System.getenv("LIGHTHOUSE_SIGNING_KEY") ?: rootProject.file("../../../work/private/android-test.jks").path)
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes { debug { manifestPlaceholders["xiaomiDebug"] = "true" }; release { manifestPlaceholders["xiaomiDebug"] = "false"; isMinifyEnabled = false; signingConfig = signingConfigs.getByName("debug") } }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
