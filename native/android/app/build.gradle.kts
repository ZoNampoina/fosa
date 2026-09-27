plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    namespace = "com.arizona.fosa"
    compileSdk = 35
    defaultConfig { applicationId = "com.arizona.fosa"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "0.6.0" }
}
dependencies { implementation("com.google.android.gms:play-services-nearby:19.3.0") }
