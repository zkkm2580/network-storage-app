plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.networkstorage"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.networkstorage"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/INDEX.LIST"
            )
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.security:security-crypto:1.0.0")

    // WebDAV over OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // SMB2/SMB3
    implementation("com.hierynomus:smbj:0.13.0")
    implementation("org.slf4j:slf4j-api:1.7.36")
    implementation("org.slf4j:slf4j-nop:1.7.36")

    // SFTP: mwiede fork 取代已停止维护的 com.jcraft:jsch:0.1.55（包名保持 com.jcraft.jsch）
    implementation("com.github.mwiede:jsch:0.2.26")
}
