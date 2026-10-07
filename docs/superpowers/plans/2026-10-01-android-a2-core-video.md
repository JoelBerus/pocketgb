# Android A2 Core and Video Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrar el núcleo C11 en Android mediante NDK/JNI, ejecutarlo en un hilo nativo único y presentar su framebuffer RGBA8888 en un `SurfaceView` sin introducir todavía audio, input, biblioteca ni persistencia.

**Architecture:** `libpocketgb.so` compila el núcleo existente sin modificarlo y añade una capa JNI pequeña. Un `NativeSession` en C posee exclusivamente `gb*`, el `pthread`, la superficie y el pacing; Kotlin conserva solo un handle opaco y publica estados tipados. `GameSurface` entrega y retira `ANativeWindow` sin destruir la sesión, de modo que una recreación de superficie o rotación no pierde el juego.

**Tech Stack:** Android NDK r27.3.13750724, CMake 3.22.1+, C11, JNI, pthread, `ANativeWindow`, Kotlin/JVM 17, Jetpack Compose, `AndroidView`, JUnit 4 y pruebas instrumentadas AndroidX.

**Spec:** `docs/diseno-android/SPEC.md` §§2.2–2.3, 3.3, 4.1 y 6; `core/include/pocketgb.h`.

## Global Constraints

- Un único módulo Gradle `:app`; no crear módulos auxiliares.
- No modificar la conducta de `core/`; solo compilar sus fuentes existentes.
- El ROM es entrada no confiable: rechazar menos de `0x150` bytes y más de 8 MiB antes de JNI; el núcleo vuelve a validar cabecera y tamaño real.
- `NativeSession` es el único dueño de `gb*`; después de arrancar, toda llamada al núcleo ocurre en su `pthread` o con el hilo confirmado como detenido.
- Nunca conservar punteros a arrays Java ni referencias JNI dentro de la sesión.
- El loop no reserva memoria, no realiza I/O y no llama a Compose/JVM por frame.
- La pérdida o recreación de `Surface` no destruye ni reinicia el core.
- Escalado entero centrado, vecino más cercano, fondo negro y píxeles RGBA8888 160×144.
- Sin permiso `INTERNET`, ROMs, boot ROMs, saves, estados ni imágenes reales en Git.
- Audio, controles, lifecycle/background y saves pertenecen a A3–A5 y quedan fuera de A2.

## Review Focus

- ROM de 8 MiB + 1 byte: Kotlin la rechaza sin copiarla a JNI; prueba en Task 2.
- Handle cerrado usado otra vez: Kotlin devuelve `CoreError.Closed` y no entra a nativo; prueba en Task 2.
- `surfaceDestroyed` mientras corre un frame: el lock nativo libera la ventana sin use-after-free; prueba repetida en Task 4.
- Pausa seguida de cierre inmediato: `pthread_join` termina y el core se destruye exactamente una vez; prueba en Task 3.
- Dimensión que no admite escala entera (p. ej. 319×287): viewport queda centrado, no negativo y sin escribir fuera; prueba pura en Task 4.

---

### Task 1: Compilar el núcleo con NDK sin divergencias

**Files:**
- Modify: `android/app/build.gradle.kts`
- Create: `android/app/src/main/cpp/CMakeLists.txt`
- Create: `android/app/src/main/cpp/pocketgb_jni.c`
- Create: `android/app/src/androidTest/java/com/joelbermudez/pocketgb/emulator/NativeLibraryTest.kt`

**Interfaces:**
- Consumes: todas las fuentes `core/src/*.c` y `core/include/pocketgb.h`.
- Produces: `libpocketgb.so` y `NativeLibrary.nativeCreate(): Long`, `nativeDestroy(Long)`.

- [ ] **Step 1: escribir el smoke test instrumental que carga la biblioteca**

```kotlin
class NativeLibraryTest {
    @Test fun createsAndDestroysCore() {
        val handle = NativeLibrary.nativeCreate()
        assertNotEquals(0L, handle)
        NativeLibrary.nativeDestroy(handle)
    }
}
```

- [ ] **Step 2: ejecutar RED**

Run: `cd android && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.joelbermudez.pocketgb.emulator.NativeLibraryTest`

Expected: FAIL porque `NativeLibrary`/`libpocketgb.so` no existen.

- [ ] **Step 3: configurar CMake y el stub JNI mínimo**

`externalNativeBuild` apunta a `src/main/cpp/CMakeLists.txt`, usa CMake 3.22.1 y compila ABI del dispositivo. CMake enumera explícitamente `pocketgb_jni.c` y cada `.c` de `core/src`, incluye `core/include` y `core/src`, fija C11 y activa `-Wall -Wextra -Werror -pedantic`.

```c
JNIEXPORT jlong JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeCreate(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return (jlong)(uintptr_t)gb_create();
}
```

`nativeDestroy(0)` es no-op y otro valor llama `gb_destroy` una vez.

- [ ] **Step 4: ejecutar GREEN y regresión del núcleo**

Run: `make -C core test && cd android && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.joelbermudez.pocketgb.emulator.NativeLibraryTest :app:assembleRelease`

Expected: 1/1 instrumental, 65/65 requerido y Release correctos.

- [ ] **Step 5: commit**

```bash
git add android/app/build.gradle.kts android/app/src/main/cpp android/app/src/androidTest
git commit -m "Android A2: compilar núcleo con NDK"
```

### Task 2: Contrato JNI tipado y validación del ROM

**Files:**
- Create: `android/app/src/main/java/com/joelbermudez/pocketgb/emulator/CoreError.kt`
- Create: `android/app/src/main/java/com/joelbermudez/pocketgb/emulator/RomInfo.kt`
- Create: `android/app/src/main/java/com/joelbermudez/pocketgb/emulator/CoreBridge.kt`
- Modify: `android/app/src/main/cpp/pocketgb_jni.c`
- Create: `android/app/src/androidTest/java/com/joelbermudez/pocketgb/emulator/CoreBridgeTest.kt`
- Create: `android/app/src/androidTest/java/com/joelbermudez/pocketgb/testing/SyntheticRom.kt`

**Interfaces:**
- Consumes: handle nativo de Task 1 y `gb_load_rom`/`gb_rom_info_get`.
- Produces: `CoreBridge.loadRom(ByteArray, CoreOptions): RomInfo`, `runFrame()`, `copyFrame(IntArray)` y `close()` idempotente.

- [ ] **Step 1: escribir pruebas de tamaño, metadatos y cierre**

```kotlin
@Test fun rejectsTooSmallBeforeNative() = assertFailsWith<CoreError.RomTooSmall> {
    CoreBridge().use { it.loadRom(ByteArray(0x14f)) }
}

@Test fun rejectsOverEightMiBBeforeNative() = assertFailsWith<CoreError.RomTooLarge> {
    CoreBridge().use { it.loadRom(ByteArray(8 * 1024 * 1024 + 1)) }
}

@Test fun readsSyntheticRomMetadataAndFrame() {
    CoreBridge().use { core ->
        val info = core.loadRom(SyntheticRom.romOnly(title = "A2 TEST"))
        assertEquals("A2 TEST", info.title)
        val pixels = IntArray(160 * 144)
        core.runFrame()
        core.copyFrame(pixels)
        assertTrue(pixels.any { it != 0 })
    }
}

@Test fun useAfterCloseIsTypedFailure() {
    val core = CoreBridge(); core.close()
    assertFailsWith<CoreError.Closed> { core.runFrame() }
}
```

`SyntheticRom` genera en memoria un ROM-only de 32 KiB con cabecera y checksums válidos; nunca escribe el `.gb` al disco.

- [ ] **Step 2: ejecutar RED**

Run: `cd android && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.joelbermudez.pocketgb.emulator.CoreBridgeTest`

Expected: FAIL por tipos y métodos ausentes.

- [ ] **Step 3: implementar errores exhaustivos y JNI sin retener arrays**

`CoreError` mapea todos los valores actuales de `gb_result`. `CoreBridge` valida longitud antes de `nativeLoadRom`; JNI usa `GetByteArrayElements`, llama al core y siempre libera con `JNI_ABORT`. Los metadatos regresan en arrays/primitivos copiados, incluida la huella completa de 32 bytes. `copyFrame` exige exactamente `160*144`, usa `GetPrimitiveArrayCritical` solo durante el `memcpy` y la libera antes de volver.

- [ ] **Step 4: ejecutar GREEN**

Run: `cd android && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.joelbermudez.pocketgb.emulator.CoreBridgeTest`

Expected: 4/4 PASS.

- [ ] **Step 5: commit**

```bash
git add android/app/src/main/cpp android/app/src/main/java/com/joelbermudez/pocketgb/emulator android/app/src/androidTest
git commit -m "Android A2: añadir puente JNI tipado"
```

### Task 3: Sesión con dueño nativo y máquina de estados

**Files:**
- Create: `android/app/src/main/cpp/native_session.h`
- Create: `android/app/src/main/cpp/native_session.c`
- Modify: `android/app/src/main/cpp/CMakeLists.txt`
- Modify: `android/app/src/main/cpp/pocketgb_jni.c`
- Create: `android/app/src/main/java/com/joelbermudez/pocketgb/emulator/SessionState.kt`
- Create: `android/app/src/main/java/com/joelbermudez/pocketgb/emulator/EmulatorSession.kt`
- Create: `android/app/src/androidTest/java/com/joelbermudez/pocketgb/emulator/EmulatorSessionTest.kt`

**Interfaces:**
- Consumes: ROM validado y `CoreOptions` de Task 2.
- Produces: `EmulatorSession.load`, `start`, `pause`, `resume`, `stop`, `close`, `state: StateFlow<SessionState>` y contador de frames solo para diagnóstico/test.

- [ ] **Step 1: escribir pruebas de transiciones y cierre**

```kotlin
@Test fun sessionTransitionsAreExplicit() {
    EmulatorSession().use { session ->
        session.load(SyntheticRom.romOnly())
        assertEquals(SessionState.Ready, session.state.value)
        session.start(); assertEquals(SessionState.Running, session.state.value)
        session.pause(); assertEquals(SessionState.Paused, session.state.value)
        session.resume(); assertEquals(SessionState.Running, session.state.value)
        session.stop(); assertEquals(SessionState.Stopped, session.state.value)
    }
}

@Test fun pauseThenCloseJoinsThread() {
    val session = EmulatorSession()
    session.load(SyntheticRom.romOnly()); session.start(); session.pause(); session.close()
    assertEquals(SessionState.Closed, session.state.value)
}
```

- [ ] **Step 2: ejecutar RED**

Run: `cd android && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.joelbermudez.pocketgb.emulator.EmulatorSessionTest`

Expected: FAIL por sesión ausente.

- [ ] **Step 3: implementar `NativeSession`**

El struct contiene `gb *core`, `pthread_t`, mutex/condición, enum de estado, flags de parada y contador atómico de frames. `start` crea un único hilo; el loop ejecuta `gb_run_frame`, presenta si hay superficie y usa deadlines absolutos `CLOCK_MONOTONIC` a `GB_CYCLES_PER_FRAME/GB_CLOCK_HZ`. `pause` espera confirmación en la condición; `stop/close` señalan, hacen `pthread_join`, liberan superficie y destruyen core. Comandos repetidos son idempotentes o devuelven transición inválida tipada.

- [ ] **Step 4: ejecutar GREEN y prueba de 300 frames**

Run: `cd android && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.joelbermudez.pocketgb.emulator.EmulatorSessionTest`

Expected: todas PASS; el contador avanza en Running y permanece estable en Paused.

- [ ] **Step 5: commit**

```bash
git add android/app/src/main/cpp android/app/src/main/java/com/joelbermudez/pocketgb/emulator android/app/src/androidTest
git commit -m "Android A2: ejecutar sesión en hilo nativo"
```

### Task 4: SurfaceView, viewport entero y recreación segura

**Files:**
- Create: `android/app/src/main/java/com/joelbermudez/pocketgb/video/IntegerViewport.kt`
- Create: `android/app/src/main/java/com/joelbermudez/pocketgb/video/GameSurface.kt`
- Modify: `android/app/src/main/cpp/native_session.c`
- Modify: `android/app/src/main/cpp/pocketgb_jni.c`
- Create: `android/app/src/test/java/com/joelbermudez/pocketgb/video/IntegerViewportTest.kt`
- Create: `android/app/src/androidTest/java/com/joelbermudez/pocketgb/video/GameSurfaceTest.kt`

**Interfaces:**
- Consumes: `EmulatorSession.attachSurface(Surface)`/`detachSurface()`.
- Produces: `GameSurface(session, modifier)` y `IntegerViewport.calculate(width, height)`.

- [ ] **Step 1: escribir pruebas de viewport y ciclo de superficie**

```kotlin
@Test fun centersLargestIntegerScale() {
    assertEquals(IntegerViewport(0, 64, 320, 288, 2), IntegerViewport.calculate(320, 416))
}

@Test fun undersizedSurfaceFallsBackWithoutNegativeBounds() {
    val viewport = IntegerViewport.calculate(159, 143)
    assertTrue(viewport.left >= 0 && viewport.top >= 0)
    assertTrue(viewport.width <= 159 && viewport.height <= 143)
}
```

La instrumental monta y desmonta `GameSurface` 25 veces mientras la sesión corre y verifica que el contador de frames continúa y no hay crash/ANR.

- [ ] **Step 2: ejecutar RED**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*IntegerViewportTest' :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.joelbermudez.pocketgb.video.GameSurfaceTest`

Expected: FAIL por clases ausentes.

- [ ] **Step 3: implementar presentación nativa**

`GameSurface` usa `AndroidView` y `SurfaceHolder.Callback`; no dibuja en Canvas Compose. JNI convierte `Surface` con `ANativeWindow_fromSurface`. Bajo mutex reemplaza la ventana reteniendo la nueva antes de liberar la anterior. El hilo bloquea la ventana, limpia a negro, calcula el mayor entero que quepa (o ajuste reducido para superficies menores), copia cada píxel a un bloque `scale×scale` sin leer/escribir fuera de `ANativeWindow_Buffer`, publica y desbloquea.

- [ ] **Step 4: ejecutar GREEN**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*IntegerViewportTest' :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.joelbermudez.pocketgb.video.GameSurfaceTest`

Expected: pruebas puras e instrumentadas PASS.

- [ ] **Step 5: commit**

```bash
git add android/app/src/main/cpp android/app/src/main/java/com/joelbermudez/pocketgb/video android/app/src/test android/app/src/androidTest
git commit -m "Android A2: presentar framebuffer en SurfaceView"
```

### Task 5: Pantalla de diagnóstico A2 y cierre verificable

**Files:**
- Create: `android/app/src/debug/java/com/joelbermudez/pocketgb/debug/NativeVideoCatalog.kt`
- Modify: `android/app/src/debug/java/com/joelbermudez/pocketgb/debug/DebugCatalog.kt`
- Modify: `android/app/src/debug/java/com/joelbermudez/pocketgb/debug/DebugIntent.kt`
- Modify: `tools/android-screenshots.sh`
- Modify: `android/README.md`
- Modify: `docs/ESTADO.md`
- Create: `docs/auditorias/A2-android-evidencia.md`

**Interfaces:**
- Consumes: sesión y superficie de Tasks 3–4.
- Produces: pantalla Debug `native-video` con ROM sintético generado en memoria y evidencia A2.

- [ ] **Step 1: escribir prueba instrumental de la pantalla**

```kotlin
@Test fun nativeVideoScreenRunsWithoutBundledRom() {
    launchDebugScreen("native-video").use {
        compose.onNodeWithTag("native-video-surface").assertIsDisplayed()
        compose.onNodeWithText("ROM sintético · solo Debug").assertIsDisplayed()
    }
}
```

- [ ] **Step 2: ejecutar RED**

Run: `cd android && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.joelbermudez.pocketgb.NativeVideoCatalogTest`

Expected: FAIL porque el ID no existe.

- [ ] **Step 3: implementar diagnóstico Debug sin artefactos binarios**

La pantalla construye el ROM en memoria, crea la sesión con `DisposableEffect`, muestra `GameSurface` en 10:9 y cierra sesión al salir. El script añade una captura clara y una oscura de `native-video`; Release sigue sin clases ni datos de catálogo.

- [ ] **Step 4: ejecutar verificación completa fresca**

```bash
make -C core test
cd android
./gradlew clean :app:testDebugUnitTest :app:connectedDebugAndroidTest \
  :app:assembleDebug :app:assembleRelease :app:lintDebug
cd ..
tools/android-screenshots.sh
```

Además: inspeccionar `aapt2 dump permissions`, buscar router/datos Debug en Release y ejecutar `make -C core asan` porque A2 incorpora las mismas fuentes al NDK.

- [ ] **Step 5: documentar y commit**

Registrar versiones NDK/CMake, conteos, prueba de recreación, capturas, manifiesto y limitaciones de A2. No versionar PNG, APK, ROM, save ni estado.

```bash
git add android tools/android-screenshots.sh android/README.md docs/ESTADO.md docs/auditorias/A2-android-evidencia.md
git commit -m "Android A2: verificar integración nativa y vídeo"
```

## Condición de cierre de A2

A2 queda listo cuando el mismo núcleo pasa 65/65 en host y compila para Android, el puente rechaza tamaños hostiles, una sesión corre/pausa/cierra sin carreras observables, la superficie puede destruirse y recrearse repetidamente sin reiniciar el core, el framebuffer aparece con escala entera, Release continúa sin red ni catálogo Debug y toda evidencia se obtuvo con comandos frescos. La auditoría conjunta continúa reservada para A8.
