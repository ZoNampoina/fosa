plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    namespace = "com.arizona.fosa"
    compileSdk = 35
    defaultConfig { applicationId = "com.arizona.fosa"; minSdk = 26; targetSdk = 35; versionCode = 10; versionName = "0.10.0"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
}
dependencies {
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    implementation("com.google.zxing:core:3.5.3")
}
