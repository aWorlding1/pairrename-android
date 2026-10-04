plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.yuanbao.pairrename"
    compileSdk = 35

    signingConfigs {
        val propsFile = rootProject.file("app/keystore.properties")
        val props = if (propsFile.exists()) {
            propsFile.readLines()
                .map { it.split("=", limit = 2) }
                .filter { it.size == 2 }
                .associate { it[0].trim() to it[1].trim() }
        } else {
            emptyMap()
        }
        val storeFilePath = props["storeFile"]?.takeIf { it.isNotBlank() }
            ?: System.getenv("PAIRRENAME_STORE_FILE")
        val storePasswordValue = props["storePassword"]?.takeIf { it.isNotBlank() }
            ?: System.getenv("PAIRRENAME_STORE_PASSWORD")
        val keyAliasValue = props["keyAlias"]?.takeIf { it.isNotBlank() }
            ?: System.getenv("PAIRRENAME_KEY_ALIAS")
        val keyPasswordValue = props["keyPassword"]?.takeIf { it.isNotBlank() }
            ?: System.getenv("PAIRRENAME_KEY_PASSWORD")
        if (
            !storeFilePath.isNullOrBlank() && !storePasswordValue.isNullOrBlank() &&
            !keyAliasValue.isNullOrBlank() && !keyPasswordValue.isNullOrBlank()
        ) {
            create("release") {
                storeFile = file(storeFilePath)
                storePassword = storePasswordValue
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue
            }
        }
    }

    defaultConfig {
        applicationId = "com.yuanbao.pairrename"
        minSdk = 26
        targetSdk = 35
        versionCode = 61
        versionName = "6.2.3"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=androidx.compose.foundation.ExperimentalFoundationApi")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

// Release APK/AAB 必须使用已验证的签名材料；禁止静默产出无法覆盖安装的 unsigned 包。
tasks.configureEach {
    if (name == "packageRelease" || name == "bundleRelease") {
        doFirst {
            val releaseSigning = android.signingConfigs.findByName("release")
            if (
                releaseSigning == null || releaseSigning.storeFile?.isFile != true ||
                releaseSigning.storePassword.isNullOrBlank() ||
                releaseSigning.keyAlias.isNullOrBlank() ||
                releaseSigning.keyPassword.isNullOrBlank()
            ) {
                throw GradleException(
                    "Release signing is required. Configure app/keystore.properties or PAIRRENAME_* environment variables.",
                )
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.coil.compose)
    implementation(libs.androidx.exifinterface)
    implementation(libs.kotlinx.immutable)
}
