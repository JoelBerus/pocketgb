plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.joelbermudez.pocketgb"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "com.joelbermudez.pocketgb"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets.getByName("androidTest").assets.srcDir("build/generated/screenManifest")

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// El manifiesto de capturas vive en tools/ (lo lee el script); CatalogCoverageTest lo recibe como asset de prueba.
val copyScreenManifest = tasks.register<Copy>("copyScreenManifest") {
    from(layout.projectDirectory.file("../../tools/android-screens.txt"))
    into(layout.buildDirectory.dir("generated/screenManifest"))
}
tasks.configureEach {
    if (name != "copyScreenManifest" && (name.contains("AndroidTest") || name.startsWith("lint"))) dependsOn(copyScreenManifest)
}

// N8: ROMs libres de GBA para los instrumentados JNI, solo si ya están en disco (nunca se versionan, regla dura 1):
// jsmolka/gba-tests (MIT, tools/fetch-gba-test-roms.sh --solo-jsmolka) y las homebrew propias de gba/tests/homebrew
// (MIT, make -C gba homebrew). Los tests que las necesitan se saltan con un aviso si faltan.
android.sourceSets.getByName("androidTest").assets.srcDir("build/generated/gbaTestRoms")
val copyGbaTestRoms = tasks.register<Copy>("copyGbaTestRoms") {
    from(layout.projectDirectory.dir("../../gba/tests/roms/gba-tests")) {
        include("arm/arm.gba", "thumb/thumb.gba", "save/flash128.gba", "ppu/stripes.gba", "ppu/shades.gba")
        eachFile { path = name }
    }
    from(layout.projectDirectory.dir("../../gba/build/hb")) {
        include("eeprom.gba", "eeprom8k.gba", "ppu_scene_11.gba")
    }
    includeEmptyDirs = false
    into(layout.buildDirectory.dir("generated/gbaTestRoms/gba"))
}
tasks.configureEach {
    if (name != "copyGbaTestRoms" && (name.contains("AndroidTest") || name.startsWith("lint"))) dependsOn(copyGbaTestRoms)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    // A7 R14: solo AndroidX (versiones del BOM). Rail en ventanas anchas y lista-detalle en expanded.
    implementation("androidx.compose.material3:material3-adaptive-navigation-suite")
    implementation("androidx.compose.material3.adaptive:adaptive")
    implementation("androidx.compose.material3.adaptive:adaptive-layout")
    implementation("androidx.compose.material3.adaptive:adaptive-navigation")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.navigation3:navigation3-runtime:1.2.0")
    implementation("androidx.navigation3:navigation3-ui:1.2.0")
    implementation("androidx.customview:customview:1.1.0") // ExploreByTouchHelper (TalkBack en los controles)
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.lifecycle:lifecycle-runtime-testing:2.11.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// N7b: vectores cruzados y códec de referencia del `.pgbm`, compartidos por los tests JVM y los instrumentados.
android.sourceSets.getByName("test").kotlin.srcDir("src/sharedTest/java")
android.sourceSets.getByName("androidTest").kotlin.srcDir("src/sharedTest/java")
