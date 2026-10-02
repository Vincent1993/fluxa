import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    kotlin("kapt")
}

val localConfig = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun configString(name: String): String {
    val value = providers.environmentVariable(name).orNull ?: localConfig.getProperty(name, "")
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

val appVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val baseVersion = appVersion.getProperty("VERSION_NAME")
require(baseVersion.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) { "Invalid VERSION_NAME" }
val appVersionCode = appVersion.getProperty("VERSION_CODE").toInt()
require(appVersionCode in 1..2100000000) { "Invalid VERSION_CODE" }
val previewNumber = appVersion.getProperty("PREVIEW_NUMBER").toInt()
require(previewNumber > 0) { "Invalid PREVIEW_NUMBER" }
val buildCommit = providers.environmentVariable("FLUXA_BUILD_COMMIT").orElse("local").get()
require(buildCommit == "local" || buildCommit.matches(Regex("[a-f0-9]{40}"))) { "Invalid build commit" }
val commitSuffix = buildCommit.take(7)
val signingChannel = providers.environmentVariable("FLUXA_SIGNING_CHANNEL").orNull
val signingPath = providers.environmentVariable("FLUXA_KEYSTORE_PATH").orNull
val signingRequired = providers.gradleProperty("fluxa.requireSigning").orNull == "true"
if (signingPath != null || signingRequired) {
    require(signingChannel in listOf("preview", "stable")) { "Signing channel must be preview or stable" }
    require(!signingPath.isNullOrBlank() && file(signingPath).isFile) { "A persistent signing keystore is required" }
    listOf("FLUXA_KEYSTORE_PASSWORD", "FLUXA_KEY_ALIAS", "FLUXA_KEY_PASSWORD").forEach {
        require(!providers.environmentVariable(it).orNull.isNullOrEmpty()) { "Missing signing configuration: $it" }
    }
}

android {
    namespace = "com.fluxa.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fluxa.app"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = baseVersion
        manifestPlaceholders["appLabel"] = "Fluxa"
        buildConfigField("String", "BUILD_COMMIT", "\"$buildCommit\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "INOREADER_CLIENT_ID", configString("INOREADER_CLIENT_ID"))
        buildConfigField("String", "INOREADER_CLIENT_SECRET", configString("INOREADER_CLIENT_SECRET"))
        buildConfigField("String", "INOREADER_REDIRECT_URI", "\"fluxa://oauth/callback\"")
    }

    if (signingPath != null) {
        signingConfigs.create("distribution") {
            storeFile = file(signingPath)
            storePassword = providers.environmentVariable("FLUXA_KEYSTORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("FLUXA_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("FLUXA_KEY_PASSWORD").get()
        }
    }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-dev+$commitSuffix"
            manifestPlaceholders["appLabel"] = "Fluxa Debug"
            buildConfigField("String", "CHANNEL", "\"debug\"")
        }
        release {
            isMinifyEnabled = false
            isDebuggable = false
            buildConfigField("String", "CHANNEL", "\"stable\"")
            if (signingChannel == "stable" && signingPath != null) {
                signingConfig = signingConfigs.getByName("distribution")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("preview") {
            initWith(getByName("release"))
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview.$previewNumber+$commitSuffix"
            manifestPlaceholders["appLabel"] = "Fluxa Preview"
            matchingFallbacks += "release"
            buildConfigField("String", "CHANNEL", "\"preview\"")
            signingConfig = if (signingChannel == "preview" && signingPath != null)
                signingConfigs.getByName("distribution") else null
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

android.testOptions.unitTests.isIncludeAndroidResources = true
android.testOptions.unitTests.all {
    // Keep Robolectric's downloaded SDK artifacts inside this checkout.
    it.systemProperty("maven.repo.local", rootProject.file(".gradle/robolectric").absolutePath)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
    implementation("androidx.activity:activity-compose:1.9.2")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.navigation:navigation-compose:2.8.0")

    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")

    implementation("com.google.dagger:hilt-android:2.52")
    kapt("com.google.dagger:hilt-android-compiler:2.52")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    testImplementation("androidx.work:work-testing:2.9.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

kapt {
    correctErrorTypes = true
}
