# Android A1 Foundations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Crear una app Android compilable con Material 3 Expressive, color dinámico, Navigation 3, edge-to-edge, tres destinos principales y un catálogo visual determinista que sirva de base verificable para A2–A8.

**Architecture:** Un único módulo `:app` contiene paquetes por responsabilidad. `MainActivity` solo configura la ventana y monta `PocketGBApp`; el estado de navegación y apariencia se mantiene fuera de los composables de pantalla. A1 usa contenido de demostración y no integra todavía JNI, SAF ni emulación.

**Tech Stack:** Gradle 8.13, Android Gradle Plugin 8.13.2, Kotlin 2.2.20, Compose BOM 2026.09.00, Material 3, Navigation 3 1.2.0, Activity Compose 1.13.0, Lifecycle 2.11.0, DataStore 1.2.1, JUnit 4 y AndroidX Compose UI Test.

**Spec:** `docs/diseno-android/SPEC.md`

## Global Constraints

- Un único módulo Gradle `:app`; package/application ID `com.joelbermudez.pocketgb`.
- `minSdk = 26`, `compileSdk = 35` y `targetSdk = 35`, las plataformas más recientes instaladas al iniciar A1.
- Java/Kotlin JVM 17; APIs y dependencias estables, sin Compose Styles experimental.
- Sin permiso `INTERNET`, telemetría, WebView, frameworks híbridos ni dependencias runtime fuera de AndroidX/Jetpack.
- UI en español; identificadores en inglés. Material 3 Expressive, color dinámico por defecto y fallback claro/oscuro.
- A1 no lee ROMs ni crea saves; todo dato de catálogo es sintético y solo está disponible en builds debug.
- No modificar `core/` ni `ios/` durante A1.

## Review Focus

- Proceso recreado tras elegir Oscuro y desactivar color dinámico: DataStore debe restaurar ambas preferencias sin flash de tema incorrecto.
- Rotación o cambio de configuración dentro de una pestaña: destino seleccionado y back stack deben conservarse.
- Back en la raíz de una pestaña secundaria: no debe cerrar una ruta inexistente ni corromper los stacks de otras pestañas.
- Fuente al 200 %: títulos, navegación y estados vacíos no deben cortarse ni superponerse.
- Navegación por tres botones y por gestos: ningún elemento debe quedar bajo las barras del sistema y no debe aplicarse inset doble.

---

### Task 1: Proyecto Gradle y política sin red

**Files:**
- Create: `android/settings.gradle.kts`
- Create: `android/build.gradle.kts`
- Create: `android/gradle.properties`
- Create: `android/gradle/wrapper/gradle-wrapper.properties`
- Create: `android/gradlew`
- Create: `android/gradlew.bat`
- Create: `android/app/build.gradle.kts`
- Create: `android/app/proguard-rules.pro`
- Create: `android/app/src/main/AndroidManifest.xml`
- Create: `android/app/src/main/res/values/strings.xml`
- Create: `android/app/src/main/res/values/themes.xml`
- Create: `android/app/src/main/java/com/joelbermudez/pocketgb/MainActivity.kt`
- Create: `android/app/src/test/java/com/joelbermudez/pocketgb/ManifestPolicyTest.kt`

**Interfaces:**
- Consumes: Android SDK platform/build-tools 35 y JDK 17 de Android Studio.
- Produces: `:app`, variantes Debug/Release y actividad lanzable `MainActivity`.

El emulador de referencia ya instalado es `Small_Phone_API_35`. Si no está arrancado, usar:

```bash
"$HOME/Library/Android/sdk/emulator/emulator" -avd Small_Phone_API_35 -no-snapshot-load
```

- [ ] **Step 1: Generar el wrapper reproducible**

Run:

```bash
cd android
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" gradle wrapper --gradle-version 8.13
```

Expected: se crean `gradlew`, `gradlew.bat` y `gradle/wrapper/*`; `distributionUrl` termina en `gradle-8.13-bin.zip`.

- [ ] **Step 2: Escribir primero la prueba de política del manifiesto**

```kotlin
package com.joelbermudez.pocketgb

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestPolicyTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test fun manifestHasLauncherActivity() {
        assertTrue(manifest.contains(".MainActivity"))
        assertTrue(manifest.contains("android.intent.action.MAIN"))
    }

    @Test fun manifestHasNoNetworkPermission() {
        assertFalse(manifest.contains("android.permission.INTERNET"))
        assertFalse(manifest.contains("android.permission.ACCESS_NETWORK_STATE"))
    }
}
```

- [ ] **Step 3: Ejecutar RED**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*ManifestPolicyTest'`

Expected: FAIL porque el proyecto/módulo o el manifiesto aún no existen.

- [ ] **Step 4: Crear configuración mínima**

`settings.gradle.kts`:

```kotlin
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "PocketGB"
include(":app")
```

`build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.20" apply false
}
```

`app/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}
android {
    namespace = "com.joelbermudez.pocketgb"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.joelbermudez.pocketgb"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.navigation3:navigation3-runtime:1.2.0")
    implementation("androidx.navigation3:navigation3-ui:1.2.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.9.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
```

`AndroidManifest.xml` declara únicamente `MainActivity`, `android:theme="@style/Theme.PocketGB"`, `android:enableOnBackInvokedCallback="true"` y `android:windowSoftInputMode="adjustResize"`; no contiene `uses-permission`.

`MainActivity.kt`:

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        super.onCreate(savedInstanceState)
        setContent { Text("PocketGB") }
    }
}
```

- [ ] **Step 5: Ejecutar GREEN y builds**

Run: `cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease`

Expected: `BUILD SUCCESSFUL`; prueba 2/2 PASS; APK Debug y Release creados.

- [ ] **Step 6: Commit**

```bash
git add android
git commit -m "Android A1: crear proyecto Compose sin red"
```

### Task 2: Preferencias de apariencia y tema Material 3

**Files:**
- Create: `android/app/src/main/java/com/joelbermudez/pocketgb/settings/AppearancePreferences.kt`
- Create: `android/app/src/main/java/com/joelbermudez/pocketgb/ui/theme/Color.kt`
- Create: `android/app/src/main/java/com/joelbermudez/pocketgb/ui/theme/Type.kt`
- Create: `android/app/src/main/java/com/joelbermudez/pocketgb/ui/theme/Theme.kt`
- Create: `android/app/src/test/java/com/joelbermudez/pocketgb/settings/AppearancePreferencesTest.kt`

**Interfaces:**
- Consumes: Android context y DataStore Preferences.
- Produces: `ThemeMode`, `AppearanceState`, `AppearanceRepository.state`, `setThemeMode`, `setDynamicColor` y `PocketGBTheme`.

- [ ] **Step 1: Escribir pruebas de resolución de apariencia**

```kotlin
class AppearancePreferencesTest {
    @Test fun systemModeFollowsDevice() {
        assertFalse(ThemeMode.SYSTEM.resolveDark(false))
        assertTrue(ThemeMode.SYSTEM.resolveDark(true))
    }
    @Test fun explicitModesIgnoreDevice() {
        assertFalse(ThemeMode.LIGHT.resolveDark(true))
        assertTrue(ThemeMode.DARK.resolveDark(false))
    }
    @Test fun defaultsUseSystemAndDynamicColor() {
        assertEquals(AppearanceState(ThemeMode.SYSTEM, true), AppearanceState.DEFAULT)
    }
}
```

- [ ] **Step 2: Ejecutar RED**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*AppearancePreferencesTest'`

Expected: FAIL por símbolos ausentes.

- [ ] **Step 3: Implementar modelo y repositorio DataStore**

```kotlin
enum class ThemeMode {
    SYSTEM, LIGHT, DARK;
    fun resolveDark(systemDark: Boolean) = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }
}
data class AppearanceState(val themeMode: ThemeMode, val dynamicColor: Boolean) {
    companion object { val DEFAULT = AppearanceState(ThemeMode.SYSTEM, true) }
}
class AppearanceRepository(private val dataStore: DataStore<Preferences>) {
    private object Keys {
        val themeMode = stringPreferencesKey("appearance_theme")
        val dynamicColor = booleanPreferencesKey("appearance_dynamic_color")
    }
    val state: Flow<AppearanceState> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> AppearanceState(
            prefs[Keys.themeMode]?.let(ThemeMode::valueOf) ?: ThemeMode.SYSTEM,
            prefs[Keys.dynamicColor] ?: true,
        ) }
    suspend fun setThemeMode(mode: ThemeMode) = dataStore.edit { it[Keys.themeMode] = mode.name }
    suspend fun setDynamicColor(enabled: Boolean) = dataStore.edit { it[Keys.dynamicColor] = enabled }
}
```

`PocketGBTheme` selecciona esquemas dinámicos desde API 31 cuando corresponde; si no, usa paletas completas generadas desde verde `#4E6B45`. Ningún componente fuera de `ui/theme` contiene colores hexadecimales.

- [ ] **Step 4: Ejecutar GREEN**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*AppearancePreferencesTest'`

Expected: 3/3 PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/joelbermudez/pocketgb/settings android/app/src/main/java/com/joelbermudez/pocketgb/ui/theme android/app/src/test
git commit -m "Android A1: añadir apariencia Material You"
```

### Task 3: Estado y rutas de Navigation 3

**Files:**
- Create: `android/app/src/main/java/com/joelbermudez/pocketgb/app/AppDestination.kt`
- Create: `android/app/src/main/java/com/joelbermudez/pocketgb/app/AppNavigationState.kt`
- Create: `android/app/src/test/java/com/joelbermudez/pocketgb/app/AppNavigationStateTest.kt`

**Interfaces:**
- Consumes: `NavKey` de Navigation 3.
- Produces: `TopLevelDestination`, rutas tipadas y `AppNavigationState` con `selected`, `currentBackStack`, `select`, `push`, `pop` y `snapshot`.

- [ ] **Step 1: Escribir pruebas de stacks independientes**

```kotlin
class AppNavigationStateTest {
    @Test fun tabsKeepIndependentStacks() {
        val state = AppNavigationState()
        state.push(LibraryRoute.Details("demo-red"))
        state.select(TopLevelDestination.SETTINGS)
        state.push(SettingsRoute.Appearance)
        state.select(TopLevelDestination.LIBRARY)
        assertEquals(LibraryRoute.Details("demo-red"), state.currentBackStack.last())
        state.select(TopLevelDestination.SETTINGS)
        assertEquals(SettingsRoute.Appearance, state.currentBackStack.last())
    }
    @Test fun popAtRootReturnsFalseAndKeepsRoot() {
        val state = AppNavigationState()
        assertFalse(state.pop())
        assertEquals(listOf(LibraryRoute.Root), state.currentBackStack)
    }
    @Test fun stateRoundTripPreservesSelectionAndStacks() {
        val original = AppNavigationState().apply {
            push(LibraryRoute.Details("demo-yellow"))
            select(TopLevelDestination.FAVORITES)
        }
        assertEquals(original.snapshot(), AppNavigationState(original.snapshot()).snapshot())
    }
}
```

- [ ] **Step 2: Ejecutar RED**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*AppNavigationStateTest'`

Expected: FAIL por tipos ausentes.

- [ ] **Step 3: Implementar rutas y estado**

```kotlin
enum class TopLevelDestination { LIBRARY, FAVORITES, SETTINGS }
@Serializable sealed interface AppRoute : NavKey
@Serializable sealed interface LibraryRoute : AppRoute {
    @Serializable data object Root : LibraryRoute
    @Serializable data class Details(val gameId: String) : LibraryRoute
}
@Serializable sealed interface FavoritesRoute : AppRoute {
    @Serializable data object Root : FavoritesRoute
}
@Serializable sealed interface SettingsRoute : AppRoute {
    @Serializable data object Root : SettingsRoute
    @Serializable data object Appearance : SettingsRoute
    @Serializable data object About : SettingsRoute
}
@Serializable data class NavigationSnapshot(
    val selected: TopLevelDestination,
    val stacks: Map<TopLevelDestination, List<AppRoute>>,
)
```

`AppNavigationState` mantiene tres `SnapshotStateList<AppRoute>`, nunca elimina la raíz y devuelve copias serializables. `select` solo cambia el stack visible; `push` rechaza una ruta que no pertenece a la pestaña seleccionada.

- [ ] **Step 4: Ejecutar GREEN**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*AppNavigationStateTest'`

Expected: 3/3 PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/joelbermudez/pocketgb/app android/app/src/test/java/com/joelbermudez/pocketgb/app
git commit -m "Android A1: modelar navegación con stacks independientes"
```

### Task 4: Shell Material y pantallas base

**Files:**
- Create: `android/app/src/main/java/com/joelbermudez/pocketgb/app/PocketGBApp.kt`
- Create: `android/app/src/main/java/com/joelbermudez/pocketgb/ui/navigation/AppNavigation.kt`
- Create: `android/app/src/main/java/com/joelbermudez/pocketgb/ui/library/LibraryScreen.kt`
- Create: `android/app/src/main/java/com/joelbermudez/pocketgb/ui/favorites/FavoritesScreen.kt`
- Create: `android/app/src/main/java/com/joelbermudez/pocketgb/ui/settings/SettingsScreen.kt`
- Create: `android/app/src/main/java/com/joelbermudez/pocketgb/ui/settings/AppearanceScreen.kt`
- Create: `android/app/src/main/java/com/joelbermudez/pocketgb/ui/about/AboutScreen.kt`
- Modify: `android/app/src/main/java/com/joelbermudez/pocketgb/MainActivity.kt`
- Modify: `android/app/src/main/res/values/strings.xml`
- Create: `android/app/src/androidTest/java/com/joelbermudez/pocketgb/AppShellTest.kt`

**Interfaces:**
- Consumes: `PocketGBTheme`, `AppearanceRepository` y `AppNavigationState`.
- Produces: shell visible con tres destinos, navegación interna de Ajustes y acciones semánticas estables.

- [ ] **Step 1: Escribir UI tests del shell**

```kotlin
@RunWith(AndroidJUnit4::class)
class AppShellTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun showsThreeLabeledDestinations() {
        compose.onNodeWithText("Biblioteca").assertIsDisplayed()
        compose.onNodeWithText("Favoritos").assertIsDisplayed()
        compose.onNodeWithText("Ajustes").assertIsDisplayed()
    }
    @Test fun settingsOpensAppearanceAndBackReturns() {
        compose.onNodeWithText("Ajustes").performClick()
        compose.onNodeWithText("Apariencia").performClick()
        compose.onNodeWithText("Color dinámico").assertIsDisplayed()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Emulación").assertIsDisplayed()
    }
}
```

- [ ] **Step 2: Ejecutar RED en emulador API 35**

Run: `cd android && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.joelbermudez.pocketgb.AppShellTest`

Expected: FAIL porque el shell no existe.

- [ ] **Step 3: Implementar raíz y navegación**

```kotlin
PocketGBTheme(appearance = appearance) {
    PocketGBApp(
        navigationState = rememberSaveable(saver = AppNavigationState.Saver) { AppNavigationState() },
        appearance = appearance,
        onThemeModeChange = { scope.launch { repository.setThemeMode(it) } },
        onDynamicColorChange = { scope.launch { repository.setDynamicColor(it) } },
    )
}
```

`PocketGBApp` usa `Scaffold`, `NavigationBar` y `NavDisplay`. Las listas reciben `innerPadding` como `contentPadding` y lo consumen una sola vez. A1 muestra: Biblioteca vacía con botón deshabilitado `Elegir carpeta`; Favoritos vacío; Ajustes completo con Apariencia y Acerca de activos; rutas futuras producen snackbar `Disponible en próximos hitos`; Apariencia permite Sistema/Claro/Oscuro y color dinámico; Acerca de declara app nativa, sin red y privacidad local.

- [ ] **Step 4: Ejecutar GREEN y fuente grande**

Run: `cd android && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.joelbermudez.pocketgb.AppShellTest`

Expected: 2/2 PASS. Repetir con `adb shell settings put system font_scale 2.0`; los mismos tests pasan sin nodos cortados.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main android/app/src/androidTest
git commit -m "Android A1: construir shell Material 3"
```

### Task 5: Catálogo visual determinista Debug

**Files:**
- Create: `android/app/src/debug/java/com/joelbermudez/pocketgb/debug/DebugCatalog.kt`
- Create: `android/app/src/debug/java/com/joelbermudez/pocketgb/debug/DebugIntent.kt`
- Create: `android/app/src/main/java/com/joelbermudez/pocketgb/ui/components/GameCard.kt`
- Create: `android/app/src/main/java/com/joelbermudez/pocketgb/ui/components/EmptyState.kt`
- Create: `android/app/src/androidTest/java/com/joelbermudez/pocketgb/DebugCatalogTest.kt`
- Create: `tools/android-screenshots.sh`

**Interfaces:**
- Consumes: extras `screen`, `theme`, `dynamicColor` y `fontScale` solo en Debug.
- Produces: IDs `library-empty`, `library-grid`, `favorites-empty`, `settings-main`, `appearance`, `about` y PNGs reproducibles.

- [ ] **Step 1: Escribir pruebas del router Debug**

```kotlin
@Test fun knownScreenShowsExpectedSemantics() {
    launch<MainActivity>(bundleOf("screen" to "library-grid")).use {
        compose.onNodeWithTag("debug-screen-library-grid").assertIsDisplayed()
        compose.onAllNodesWithTag("game-card").assertCountEquals(4)
    }
}
@Test fun unknownScreenIsVisibleFailure() {
    launch<MainActivity>(bundleOf("screen" to "missing")).use {
        compose.onNodeWithText("Pantalla desconocida").assertIsDisplayed()
        compose.onNodeWithText("missing").assertIsDisplayed()
    }
}
```

- [ ] **Step 2: Ejecutar RED**

Run: `cd android && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.joelbermudez.pocketgb.DebugCatalogTest`

Expected: FAIL porque los extras se ignoran.

- [ ] **Step 3: Implementar router, datos y capturas**

```kotlin
data class DemoGame(val id: String, val title: String, val subtitle: String, val favorite: Boolean, val placeholderSeed: Int)
val demoGames = listOf(
    DemoGame("red", "POKÉMON RED", "Game Boy · MBC3", true, 0),
    DemoGame("yellow", "POKÉMON YELLOW", "Game Boy Color · MBC5", true, 1),
    DemoGame("demo-a", "DEMO ADVENTURE", "Game Boy · ROM", false, 2),
    DemoGame("demo-b", "COLOR DEMO", "Game Boy Color · MBC5", false, 3),
)
```

El router existe solo en `src/debug`; Release no contiene extras ni datos demo. Un ID desconocido muestra un error visible. `GameCard` genera placeholder con roles del tema, sin imágenes ni red.

`tools/android-screenshots.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail
adb_bin="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}/platform-tools/adb"
out_dir="${1:-android/build/screenshots}"
mkdir -p "$out_dir"
screens=(library-empty library-grid favorites-empty settings-main appearance about)
for theme in light dark; do
  for screen in "${screens[@]}"; do
    "$adb_bin" shell am force-stop com.joelbermudez.pocketgb
    "$adb_bin" shell am start -W -n com.joelbermudez.pocketgb/.MainActivity --es screen "$screen" --es theme "$theme" --ez dynamicColor false >/dev/null
    "$adb_bin" exec-out screencap -p > "$out_dir/${screen}-${theme}.png"
  done
done
```

- [ ] **Step 4: Ejecutar GREEN y generar capturas**

Run:

```bash
cd android
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.joelbermudez.pocketgb.DebugCatalogTest
cd ..
tools/android-screenshots.sh
find android/build/screenshots -name '*.png' | wc -l
```

Expected: 2/2 PASS y `12` capturas.

- [ ] **Step 5: Revisar visualmente y commit**

Abrir hojas de contacto claro/oscuro y comprobar insets, recortes, roles de color, etiquetas y ausencia de datos reales. No versionar PNGs.

```bash
git add android/app/src/debug android/app/src/main/java/com/joelbermudez/pocketgb/ui/components android/app/src/androidTest tools/android-screenshots.sh
git commit -m "Android A1: añadir catálogo visual determinista"
```

### Task 6: Regresión, documentación y evidencia

**Files:**
- Modify: `android/README.md`
- Modify: `docs/05-android-spec.md`
- Modify: `docs/ESTADO.md`
- Create: `docs/auditorias/A1-android-evidencia.md`
- Modify: `.gitignore`

**Interfaces:**
- Consumes: Tasks 1–5.
- Produces: instrucciones reproducibles y evidencia exacta de A1.

- [ ] **Step 1: Ejecutar verificación completa fresca**

```bash
make -C core test
cd android
./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:assembleDebug :app:assembleRelease :app:lintDebug
cd ..
if "$HOME/Library/Android/sdk/build-tools/35.0.0/aapt2" dump permissions \
  android/app/build/outputs/apk/release/app-release-unsigned.apk \
  | rg 'android.permission.(INTERNET|ACCESS_NETWORK_STATE)'; then exit 1; fi
tools/android-screenshots.sh
```

Expected: núcleo 65/65 requerido; tests/builds/lint PASS; sin permisos de red; 12 capturas.

- [ ] **Step 2: Documentar resultado exacto**

`android/README.md` incluye requisitos, JDK, build, tests, instalación y capturas. `docs/05-android-spec.md` enlaza la nueva spec y elimina divergencias. `A1-android-evidencia.md` registra SHA, versiones, conteos, salida relevante, capturas y revisión visual. `.gitignore` cubre `.gradle`, `build`, `local.properties`, `.idea`, keystores y capturas, no el wrapper.

- [ ] **Step 3: Comprobar que Release excluye Debug**

Run: `cd android && ./gradlew :app:assembleRelease && (unzip -l app/build/outputs/apk/release/app-release-unsigned.apk | rg 'DebugCatalog|DebugIntent|demoGames' && exit 1 || true)`

Expected: ninguna coincidencia.

- [ ] **Step 4: Validar y commit**

Run: `git diff --check && git status --short`

Expected: ningún APK, ROM, save, estado, keystore o PNG staged.

```bash
git add android/README.md docs/05-android-spec.md docs/ESTADO.md docs/auditorias/A1-android-evidencia.md .gitignore
git commit -m "Android A1: registrar verificación de fundamentos"
```

## Condición de cierre de A1

A1 queda listo para revisión solo si el shell funciona en un emulador API 35, apariencia y stacks sobreviven a recreación, las 12 capturas fueron inspeccionadas, Release no contiene permisos de red ni router Debug y todos los comandos de Task 6 tienen evidencia real. La auditoría independiente se reserva para A8; cualquier fallo de A1 se corrige con una prueba de regresión antes de continuar a A2.
