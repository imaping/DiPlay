import java.util.Properties

plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.compose)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use(::load)
}

fun optionalR2Config(envKey: String, propKey: String): String =
    providers.environmentVariable(envKey).orNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: localProperties.getProperty(propKey)?.trim().orEmpty()

fun escapeBuildConfigString(value: String): String =
    value.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "")
        .replace("\n", "")

val r2UploadEndpoint = optionalR2Config("DIPLAY_R2_UPLOAD_ENDPOINT", "r2.upload.endpoint")
val r2UploadToken = optionalR2Config("DIPLAY_R2_UPLOAD_TOKEN", "r2.upload.token")

android {
    namespace = "com.shilapi.xcertplay.host"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 24
        buildConfigField("String", "R2_UPLOAD_ENDPOINT", "\"${escapeBuildConfigString(r2UploadEndpoint)}\"")
        buildConfigField("String", "R2_UPLOAD_TOKEN", "\"${escapeBuildConfigString(r2UploadToken)}\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // The UI suite covers several SDKs and locale-specific resource sandboxes. 1g sat right at the
        // ceiling: adding one more Robolectric class pushed a full run into OutOfMemoryError in
        // unrelated tests, on CI and locally both, which is what BydSettingsDecoyTest measured.
        unitTests.all { it.maxHeapSize = "2g" }
    }
}

dependencies {
    api(project(":shared"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    implementation(libs.zxing.core)
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("org.mockito:mockito-core:5.20.0")
    testImplementation(libs.jmdns)
}
