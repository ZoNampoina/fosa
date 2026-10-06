plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    namespace = "com.arizona.fosa"
    compileSdk = 35
    defaultConfig { applicationId = "com.arizona.fosa"; minSdk = 26; targetSdk = 35; versionCode = 15; versionName = "0.12.3"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("io.github.webrtc-sdk:android:150.7871.01")
    debugImplementation("androidx.compose.ui:ui-tooling")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.09.03"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    implementation("com.google.zxing:core:3.5.3")
}
