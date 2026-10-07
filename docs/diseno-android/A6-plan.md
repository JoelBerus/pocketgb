# Android A6 — Plan de paridad visual

Plan elaborado por un subagente Opus el 2026-10-06 sobre `codex/android-port` @ `8953d65`. Complementa [SPEC.md](SPEC.md) §3.3, §3.4, §4, §5.1, §7 (A6) y §8. La lista de trabajo sale de [A6-A8-huecos.md](A6-A8-huecos.md): todo lo marcado **A6** entra; lo marcado A7/A8 queda fuera, salvo la preparación indicada en «Preparado para A7». Rutas abreviadas: `A/` = `android/app/src/main/java/com/joelbermudez/pocketgb/`, `D/` = `android/app/src/debug/java/com/joelbermudez/pocketgb/debug/`, `I/` = `ios/PocketGB/`, `C/` = `android/app/src/main/cpp/`.

Referencia iOS: `I/Input/ControlsSettings.swift` (`GameplaySettingsData` :32-82, `GameplaySettings` :86-149), `I/Settings/*.swift`, `I/Library/{GameArtworkStore,GamePlaceholderView,GameCard,LibraryView,GameDetailsView}.swift`, `I/Gameplay/{GameplayHUD,PauseView,GameScreen}.swift`, `I/Input/ControlsOverlayView.swift`, `I/App/AppState.swift` (:259-266 emulación al abrir, :337-366 portada al cerrar), `ios/PocketGBUITests/screens.txt`.

## Decisiones (por defecto; Joel puede cambiar cualquiera)
| # | Decisión |
|---|---|
| K1 | **«Sonar con el modo silencio» no existe en Android.** No hay interruptor de silencio con semántica de sesión de audio; el audio usa `USAGE_GAME` y respeta el volumen multimedia del sistema y el foco (`A/audio/AudioFocusController.kt`). La fila se omite y Ajustes › Audio lleva un pie: «El sonido sigue el volumen multimedia del teléfono». El campo no se añade al modelo. Se documenta como desviación en la evidencia. |
| K2 | **Volumen del juego** 0,0–1,0 (paso 0,05 en el slider), por defecto 1,0. Ganancia lineal aplicada en nativo al convertir a `int16` dentro del callback (`C/audio_output.c`, `render_audio`), leída con un atómico; nunca se reconstruye el stream. Se aplica en caliente. |
| K3 | **Escalado (Ajustes › Pantalla).** Un único ajuste «Escala entera en horizontal» como iOS (por defecto activo). Vertical usa siempre **Llenar** (10:9 que ocupa el ancho, vecino más cercano, sin suavizado; SPEC §4.1). Horizontal: Entero si el ajuste está activo, si no Llenar. El nativo recibe un `ScaleMode` (`INTEGER`/`FILL`) por sesión y lo puede cambiar en caliente con la superficie viva. Fila informativa «Filtro: Píxeles nítidos». |
| K4 | **Gameplay siempre oscuro.** `GameplayHost` envuelve todo (superficie, HUD, `PauseSheet`, `StatesSheet`, diálogos de `GameDialogs.kt`) en `PocketGBTheme(forceDark = true, dynamicColor = false)` y fija iconos claros de barras con `WindowInsetsControllerCompat.isAppearanceLightStatusBars = false` mientras hay sesión; al cerrar restaura lo que dicta el tema de la app. Cierra la observación de A5 sobre la barra de estado en claro. Apariencia gana el pie «El juego siempre se muestra sobre fondo oscuro». |
| K5 | **Pantalla completa en gameplay.** Barras del sistema ocultas con `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`; `layoutInDisplayCutoutMode = SHORT_EDGES` solo durante la sesión; exclusión de gestos (`systemGestureExclusionRects`) sobre los rectángulos de la cruceta y A/B, recortada al tope de 200 dp vertical por borde que impone Android. En vertical el framebuffer queda **debajo** del cutout (padding `displayCutout`), en horizontal los controles quedan dentro de `safeDrawing`. La verificación fina de cutouts y rotación sin pausa es A7. |
| K6 | **Ajustes por juego se indexan por huella SHA-256** (la misma de `LibraryPreferencesData.fingerprints`), nunca por ruta ni `RomEntry.id`. Un juego sin huella conocida (nunca abierto) no ofrece la hoja hasta que se calcule; la hoja de detalle calcula la huella bajo demanda en I/O (como `GameLauncher`) si falta. |
| K7 | **Persistencia:** un archivo `filesDir/gameplay-settings.json`, JSON atómico con el mismo patrón que `LibraryPreferencesFile` (`A/library/LibraryPreferences.kt:177-230`: `.tmp` + `writeSynced` + `rename`, recuperación del temporal, cuarentena del corrupto, `PreferencesFileOps` inyectable :150-165) y `Json { ignoreUnknownKeys; encodeDefaults; coerceInputValues }` (:183). Validación campo a campo al decodificar (valor inválido ⇒ por defecto de ese campo, nunca todo el archivo). Se versiona con `schema = 1`. No se usa DataStore (evita dependencia nueva). |
| K8 | **Cambiar Color/Paleta con una sesión abierta** se aplica en la **próxima apertura** (como iOS, pie «Se aplica la próxima vez que abras el juego»). Excepción: si la sesión ya está en modo compatibilidad CGB (`RomInfo.cgbCompat`), un cambio de **paleta** se aplica en caliente con `gb_set_compat_palette`; el modelo nunca cambia en caliente (cambiaría el estado de la máquina y rompería los estados). Volumen, escalado, opacidad, visibilidad, cruceta, háptica y disposición se aplican en caliente. |
| K9 | **Portadas:** último fotograma al **cerrar** la sesión con éxito (`CloseResult` limpio o con fallo de espejo), copiado con `EmulatorSession.copyFramebuffer` (sesión aparcada) y codificado con `FramePng.encode` (`A/saves/FramePng.kt:9`) tras `FrameThumbnail.swapRedBlue` (`A/saves/FrameThumbnail.kt:9`). Se **descarta** si todos los píxeles son del mismo color (pantalla en blanco/negro). PNG atómico en `filesDir/artwork/<huella>.png` (`.tmp` + fsync + rename) en un executor propio de baja prioridad, nunca en el hilo de guardado ni en el principal. Es best-effort: un fallo se registra y no afecta al cierre ni a la partida. Fuera del backup en la nube (`data_extraction_rules.xml` `<exclude domain="file" path="artwork/"/>`). |
| K10 | **Carril «Continuar jugando»:** hasta **5** juegos recientes **solo con portada capturada** (orden `lastPlayed` desc.). La primera tarjeta grande (240 dp) y el resto 170 dp; botón «Continuar» encima de la portada; tocar la portada abre el detalle; «Jugado <relativo>». Sin juegos con captura ⇒ no hay carril. `LibraryQuery.recent` (`LibraryPreferences.kt:122`) baja su límite a 5 y recibe el filtro de portada. |
| K11 | **Portada generada determinista** (placeholder) portada de `I/Library/GamePlaceholderView.swift:1-119`: semilla FNV-1a de 32 bits sobre la huella (o `RomEntry.id` si no la hay), 4 colores, glifo 5×5 simétrico, iniciales, chip GB/GBC y descripción accesible «Sin captura, portada generada para <título>». Se dibuja en Compose `Canvas` sin suavizado (`FilterQuality.None`). |
| K12 | **Controles:** opacidad {30, 50, 70, 100} %, por defecto 70, solo visual (el área táctil no cambia); escala global {0,85, 1, 1,15}; escala por control 0,6–1,6 en pasos de 10 %; visibilidad Siempre / Al tocar (desvanecer a los 3 s) / Ocultos (pista de 3 s); cruceta Game Boy / Flechas separadas; háptica sí/no. Colores fijos sobre capa oscura localizada (no `primaryContainer` ni color dinámico): relleno neutro oscuro, etiqueta blanca con alfa ≥ 70 %, anillos A cálido y B frío. |
| K13 | **HUD:** botón Pausa (48 dp, «Pausa y opciones») y botón de avance rápido que cicla ×1→×2→×4→×1 con texto, `stateDescription` y háptica `CONFIRM`. El HUD queda fijo (decisión iOS 30-09); el desvanecimiento a 3 s es de los **controles** en «Al tocar». El control `MENU` deja de dibujarse en los controles (sigue en `ControlId` para no romper geometría; ver «Preparado para A7»). |
| K14 | **Confirmaciones de estados:** «Guardar actual y cargar» / «Cargar sin guardar» / Cancelar; reemplazar ranura ocupada pide confirmación con la fecha. «Cargar sin guardar» llama a `GameSession.loadState(slot, saveCurrentToAuto = false)`; la ruta de guardado (I4, rollback) no cambia. |
| K15 | **Aviso «Cabecera dañada»**: si `RomInfo.headerChecksumOk == false` (`A/emulator/RomInfo.kt:11`), `GameLauncher` devuelve `Opened` con un `GameNotice.HeaderDamaged` que se muestra como snackbar al empezar; se puede jugar. Cierra la desviación 9 de A5. |
| K16 | **Almacenamiento:** tamaños de «Partidas y copias» (`saves/`), «Estados guardados» (`states/`) y «Portadas» (`artwork/`) medidos en `Dispatchers.IO`; «Borrar portadas» con confirmación (solo `artwork/`, nunca `saves/` ni `states/`); pie «Los ROM no ocupan espacio de la app». |
| K17 | **Acerca de:** versión `BuildConfig.VERSION_NAME (VERSION_CODE)`, Núcleo PocketGB (C11), Consolas, Privacidad («Sin red»), «Juegos: solo tus ROM» y **Licencias de terceros** (texto Expat de SameBoy por las tablas de `core/src/cgb.c`) en `res/raw/licenses_sameboy.txt`. El texto se copia del encabezado de `cgb.c`, no se redacta. |
| K18 | **Ajustes de biblioteca en Ajustes:** Vista y Orden también en Ajustes › Biblioteca (misma fuente `LibraryPreferencesData`), ocultos con nombre de archivo y «Mostrar», pies explicativos. |
| K19 | **`isNew`:** «Nuevo» = entrada no vista en el escaneo anterior; `LibraryPreferencesData` gana `knownIds: Set<String>` (campo nuevo con `= emptySet()`, compatible). El primer escaneo de una carpeta nunca marca nada como nuevo. Se limpia al abrir el juego. |
| K20 | **`mirrorSaveDate`:** fecha del `.sav` junto al ROM leída en el escaneo (SAF `COLUMN_LAST_MODIFIED`, sin abrir el archivo). Solo informativa: no participa en `SaveResolution`. |

## Invariantes (no se pueden romper)
- **Todo A5:** I1 (transiciones de `EmulatorSession` solo en el hilo principal), I2 (único otro hilo, `pocketgb-saves`), I3 (cierre con compuerta y reaper), I4 (copiar y escribir SRAM en el mismo hilo), `FingerprintOwnership` (una huella, un dueño), `SaveCoordinator`, `AtomicSaveWriter`, `SaveResolution`, espejo SAF y la ruta de guardado completa **intactos**. A6 no modifica ningún archivo de `A/saves/` salvo leer `FramePng`/`FrameThumbnail`. La portada se toma **después** del flush y antes de `nativeSessionDestroy`, por la ruta ya existente de `copyFramebuffer` bajo `handleLock.read`.
- **`core/` no se toca.** Se usan `gb_options.model`, `gb_options.compat_palette`, `gb_set_compat_palette` y `gb_rom_info.cgb_mode/cgb_compat` tal como están en `core/include/pocketgb.h:47-58, 80-101, 127-129`.
- **Sin red, sin permisos nuevos, sin dependencias nuevas de runtime.** `aapt2 dump permissions` igual que en A5.
- **Release sin catálogo:** nada de `D/` ni sus `strings` llega al DEX de Release.
- Reglas duras 3 y 4 en nativo: rangos validados en JNI y en `native_session` antes de tocar el núcleo; sin `malloc` nuevo en el bucle de frame; la ganancia y el modo de escala son atómicos leídos por el hilo nativo.
- Ajustes por juego nunca por ruta. Borrar portadas nunca toca partidas ni estados.

## Modelo de datos (L1)
Paquete `A/settings/`.

```kotlin
@Serializable enum class ControlsVisibility { ALWAYS, ON_TOUCH, HIDDEN }
@Serializable enum class DpadStyle { CROSS, ARROWS }

@Serializable data class StoredControlLayout(            // espejo serializable de ControlLayout
    val positions: Map<ControlId, NormalizedPoint> = emptyMap(), // 0..1 relativos a la zona de controles
    val scales: Map<ControlId, Float> = emptyMap(),              // 0.6..1.6
)

@Serializable data class GameOverrides(
    val colorForGameBoy: Boolean? = null,   // null = global
    val compatPalette: Int? = null,         // null = global; 0 = automática; 1..12
) { val isEmpty get() = colorForGameBoy == null && compatPalette == null }

@Serializable data class GameplaySettingsData(
    val schema: Int = 1,
    val opacity: Int = 70,                       // {30, 50, 70, 100}
    val visibility: ControlsVisibility = ALWAYS,
    val haptics: Boolean = true,
    val sizeScale: Float = 1f,                   // 0.85..1.15 (recortado)
    val portraitLayout: StoredControlLayout = StoredControlLayout(),
    val landscapeLayout: StoredControlLayout = StoredControlLayout(),
    val integerScaleLandscape: Boolean = true,
    val dpadStyle: DpadStyle = CROSS,
    val volume: Float = 1f,                      // 0..1 (recortado)
    val colorForGameBoy: Boolean = false,
    val compatPalette: Int = 0,                  // 0..GB_COMPAT_PALETTES (fuera ⇒ 0)
    val perGame: Map<String, GameOverrides> = emptyMap(), // clave: huella SHA-256 hex minúscula de 64
    val controllerMapping: ControllerMappingData? = null, // reservado A7; null = por defecto
)
```

- `GameplaySettingsData.sanitized()` aplica las reglas al decodificar: opacidad fuera del conjunto ⇒ 70; escalas recortadas; posiciones fuera de [0,1] ⇒ se descarta esa posición (vuelve a la de fábrica); claves de `perGame` que no son hex de 64 ⇒ descartadas; `GameOverrides` vacíos ⇒ descartados; `compatPalette` > 12 ⇒ 0.
- Operaciones puras (testables en JVM): `update { }`, `move(orientation, id, point)`, `resize(orientation, id, delta)` (paso 0,1, recorte 0,6–1,6), `resetLayout(orientation)`, `isFactoryLayout(orientation)`, `setOverrides(fingerprint, overrides)` (borra la entrada si `isEmpty`), `emulation(fingerprint, isCgbRom): EmulationOptions`.
- `ControlLayout` (`A/input/ControlGeometry.kt:38-43`) gana `fun from(stored, orientation)`: posición/escala guardada o la de `defaults(orientation)` (:45). `ControlGeometry` recibe además `sizeScale` global (multiplica la escala por control; producto recortado a 0,6–1,6×1,15).
- `GameplaySettingsStore` (interfaz `load()`/`save()`) y `GameplaySettingsFile` (implementación como `LibraryPreferencesFile`), más `GameplaySettingsRepository` con `StateFlow<GameplaySettingsData>` en la `Application`/`PocketGBApp` igual que el de apariencia (`A/settings/AppearancePreferences.kt`). El guardado ocurre en `Dispatchers.IO`, serializado por un `Mutex`; un `PersistResult.Failed` se expone como aviso no bloqueante.
- **Preparado para A7:** `controllerMapping` existe en el esquema como `null` (A7 lo rellena sin migración). `ControlId.MENU` se conserva en el enum y en `ControlLayout`, oculto por `ControlsRenderOptions.showMenu = false`, para que A7 pueda reutilizarlo como acción accesible.

### `EmulationOptions` y contrato JNI (L2)
```kotlin
enum class GbModel(val native: Int) { AUTO(0), DMG(1), CGB(2) }  // = gb_model
enum class ScaleMode(val native: Int) { INTEGER(0), FILL(1) }
data class EmulationOptions(val model: GbModel = GbModel.AUTO, val compatPalette: Int = 0) {
    init { require(compatPalette in 0..CoreBridge.COMPAT_PALETTES) }
}
```
Resolución (`GameplaySettingsData.emulation`): ROM con `0x143 & 0x80` ⇒ `AUTO` siempre (los GBC van en color, `GameSettingsSheet` deshabilitada); ROM DMG ⇒ `CGB` si `colorForGameBoy` efectivo (por juego ?: global), si no `DMG`; paleta = por juego ?: global.

| Función nueva/cambiada (`A/emulator/NativeLibrary.kt`, `C/pocketgb_jni.c`) | Validación Kotlin | Validación C |
|---|---|---|
| `nativeSessionLoad(handle, rom, unixTime, model: Int, compatPalette: Int): Int` (sustituye la firma de `NativeLibrary.kt:48`; JNI :200-225 rellena `options.model`/`options.compat_palette` tras `gb_options_default`) | `EmulationOptions.init` | `model` ∉ {0,1,2} o paleta ∉ 0..`GB_COMPAT_PALETTES` ⇒ `GB_ERR_INVALID_ARGUMENT` sin crear el core. `GB_ERR_CGB_ONLY` (DMG pedido para ROM solo-CGB) se traduce a `CoreError` existente |
| `nativeSessionSetCompatPalette(handle, id: Int): Int` | sesión cargada y `cgbCompat` | rango; aplicado por el hilo nativo entre frames (petición atómica como `set_speed`), nunca con el mutex del núcleo cruzando JNI |
| `nativeSessionSetVolume(handle, gain: Float)` | 0..1 | `isfinite` y recorte a [0,1]; atómico `_Atomic float` leído en `render_audio` |
| `nativeSessionSetScaleMode(handle, mode: Int)` | enum | ∉ {0,1} ⇒ ignorado; `native_session.c:174-199` elige entero o llenar (10:9 que quepa, centrado, vecino más cercano vía `ANativeWindow_setBuffersGeometry` + blit con paso fraccional precalculado al cambiar la superficie, sin `malloc` por frame) |

`EmulatorSession.load(rom, unixTimeSeconds, options: EmulationOptions = EmulationOptions())` (`A/emulator/EmulatorSession.kt:77`) y nuevos `setCompatPalette`, `setVolume`, `setScaleMode`, todos con `handleLock.read` y en el hilo principal (I1). `IntegerViewport` (`A/video/IntegerViewport.kt`) pasa a `Viewport.calculate(width, height, mode)` conservando `IntegerViewport` como alias para no tocar sus llamadores en el mismo commit.

## Lotes
Orden: **L1** primero (contrato del modelo y rutas). Después **L2 ∥ L3**, luego **L4** (o **L3 ∥ L4** si L2 va por delante; nunca tres a la vez, por memoria del Mac). **L5** al final. Cada lote es uno o varios commits sobre `codex/android-port`, con tests en verde antes del siguiente.

**Regla anticolisión.** Textos nuevos solo en `res/values/strings_settings.xml` (L1), `strings_library.xml` (L3), `strings_gameplay.xml` (L2 y L4; L2 solo añade el aviso de cabecera y las claves `emulation_*` al final del archivo, L4 el resto). `strings.xml` no se toca salvo mover a esos archivos lo que el propio lote reescribe. Pantallas del catálogo en archivos propios bajo `D/catalog/` (uno por lote: `SettingsCatalog.kt`, `LibraryCatalog.kt`, `GameplayCatalog.kt`); `D/DebugCatalog.kt` solo se edita en L5 para registrarlos. `AppDestination.kt` lo edita solo L1.

### L1 · Modelo de ajustes y pantallas de Ajustes
- **Nuevos:** `A/settings/{GameplaySettings.kt, GameplaySettingsStore.kt, GameplaySettingsRepository.kt, StorageUsage.kt}`; `A/ui/settings/{ControlsSettingsScreen, DisplaySettingsScreen, EmulationSettingsScreen, AudioSettingsScreen, StorageSettingsScreen, LicensesScreen, SettingRowLabel}.kt`; `A/ui/settings/components/{SettingsGroup, ChoiceRow, SliderRow}.kt`; `res/values/strings_settings.xml`; `res/raw/licenses_sameboy.txt`.
- **Modificados:** `A/app/AppDestination.kt` (rutas `SettingsControls`, `SettingsDisplay`, `SettingsEmulation`, `SettingsAudio`, `SettingsStorage`, `SettingsLicenses`), `A/app/PocketGBApp.kt` (repositorio y entradas), `A/ui/settings/SettingsScreen.kt` (tres grupos con iconos como `I/Settings/SettingsView.swift:10-43`; desaparece «Disponible en próximos hitos» :62-64), `AppearanceScreen.kt` (pie K4), `A/ui/about/AboutScreen.kt` (K17), `LibrarySettingsScreen.kt` (K18; nombre de archivo en vez de `entry.id` :171), `SavesScreen.kt` (fecha de la partida actual, pies, «Copia más reciente/Copia n», margen inferior con `contentPadding` de insets).
- La vista previa de Pantalla usa el placeholder de K11: L1 incluye `A/ui/components/GamePlaceholder.kt` (lo reutiliza L3).
- Ajustes › Controles muestra «Restablecer disposición vertical/horizontal» (deshabilitado si `isFactoryLayout`). Storage usa `StorageUsage` sobre `filesDir` (portadas = 0 hasta L3).
- **No tocar:** `A/saves/**`, `A/game/**`, `C/**`, `A/input/**` (salvo `ControlLayout.from`, aditivo).

### L2 · Nativo, JNI y apertura
- **Modificados:** `C/pocketgb_jni.c`, `C/native_session.{c,h}`, `C/audio_output.{c,h}`, `A/emulator/{NativeLibrary, EmulatorSession, CoreBridge, CoreError}.kt`, `A/video/{IntegerViewport.kt → Viewport.kt, GameSurface.kt}`, `A/game/GameLauncher.kt` (recibe `GameplaySettingsData` + huella; aplica `emulation(...)`; devuelve `HeaderDamaged`), `A/game/GameplayViewModel.kt` (`GameNotice.HeaderDamaged`; observa el repositorio para volumen/escala/paleta en caliente según K8).
- **Nuevo:** `A/emulator/EmulationOptions.kt`.
- Las nuevas llamadas en caliente pasan por `GameSession` con el mismo patrón de delegación que `setSpeed`; **no** se añade nada a la ruta de flush/close.
- **No tocar:** `A/saves/**`, lógica de `GameSession` relacionada con guardado/estados, UI de biblioteca.

### L3 · Biblioteca y detalle
- **Nuevos:** `A/library/artwork/{ArtworkStore.kt, ArtworkCapture.kt}` (K9; `ArtworkStore` con `save(fp, pixels)`, `load(fp): ImageBitmap?` con tope de 512 KiB, `removeAll()`, `sizeBytes()`; `ArtworkCapture.isUniform(pixels)`), `A/ui/components/{GameArtwork.kt, ContinueRail.kt, GameContextMenu.kt}`, `A/ui/details/GameSettingsSheet.kt` (hoja de `EmulationSettingsView.swift:37-97`: Color «Global (…)»/En color/Sin color, Paleta «Global (…)»/Automática/1..12 deshabilitada sin color, GBC con cabecera «siempre en color» y sección deshabilitada, «Usar los ajustes globales», pie K8), `res/values/strings_library.xml`.
- **Modificados:** `A/ui/components/GameCard.kt` (portada 10:9 atenuada si hay problema, título en 2 líneas, chip GB/GBC, estrella, «Nuevo», «Sin jugar»/relativo/«Partida <fecha>»; fila de lista con miniatura), `A/ui/library/{LibraryContent, LibraryScreen}.kt` (cabecera «Todos los juegos» + carpeta; carril K10; menú «Más opciones» con Vista, Orden, Volver a escanear, Cambiar carpeta; `PullToRefreshBox`; búsqueda con «N resultados» en lista, «Buscar en todos», «Ver todos» en filtro vacío; progreso «Buscando juegos… X de Y»; resumen de nuevos; «Reintentar» en `PermissionRevoked`; menú contextual Jugar/Ver detalle/Favorito/Estados (deshabilitado)/Ajustes del juego/Ocultar destructivo), `A/library/{LibraryViewModel.kt (pasa el progreso de `LibraryScanner.scan` :44-47, hoy ignorado en :199), LibraryPreferences.kt (K10, K19), RomEntry.kt (`isNew`, `mirrorSaveDate`), LibraryScanner.kt (K20)}`, `A/ui/favorites/FavoritesScreen.kt` (carril recientes en vez de `emptyList()` :78-84, pies), `A/ui/details/GameDetailsScreen.kt` (portada real en vez del marcador :247-265; datos Jugado/Partida/Tamaño; Continuar si `lastPlayed` o `mirrorSaveDate`; fila Favorito/Estados/Ajustes; estrella en la barra superior), `A/ui/components/HideGameDialog.kt` (botón destructivo), `A/game/GameplayViewModel.kt` **solo** el gancho `onClosed(fingerprint, pixels)` que llama a `ArtworkStore` (coordinar con L2 si van en paralelo: L3 añade el gancho al final de la clase).
- La captura la orquesta `GameplayViewModel`, no `ArtworkStore` ni `GameSession`: se pide el fotograma a la sesión aún aparcada (`CoreBridge.copyFrame`, `A/emulator/CoreBridge.kt:44`, o su equivalente de sesión) mediante un parámetro opcional `close(onParkedFrame)` que solo lee. Como obliga a tocar `GameSession.tryClose`, la única edición permitida es invocar el callback en el hilo principal tras el flush correcto y antes de `nativeSessionDestroy`, envuelto en `runCatching` (un fallo nunca altera el `CloseResult`).
- **No tocar:** `A/saves/**`, `C/**`, controles.

### L4 · Gameplay
- **Nuevos:** `A/ui/gameplay/{GameplayHud.kt, ControlsEditor.kt, ImmersiveMode.kt, LoadStateConfirmDialog.kt}`, `A/input/{ControlsRenderOptions.kt, ControlsFadeController.kt}`, `res/values/strings_gameplay.xml` (el resto de textos).
- **Modificados:** `A/ui/gameplay/{GameplayHost, GameplayScreen, PauseSheet, StatesSheet, GameDialogs}.kt` (K4, K5, K13; `PauseSheet` con título = nombre del juego, Continuar, Estados guardados, **Personalizar controles**, Salir destructivo, pies, fotograma atenuado detrás; `StatesSheet` con K14), `A/ui/theme/Theme.kt` (parámetro `forceDark`), `A/input/{GameControlsView, GameControlsOverlay, ControlGeometry, TouchInputEngine}.kt` (opacidad visual, visibilidad, `DpadStyle.ARROWS` dibujado como cuatro flechas con la misma geometría de sectores, escala global y por control, capa oscura localizada, anillos A/B, háptica de impacto en A/B/Start/Select y `CLOCK_TICK` al cambiar de sector de la cruceta, área segura como zona de geometría, `showMenu = false`), `A/game/GameSession.kt` **solo** el parámetro `saveCurrentToAuto` de `loadState` (por defecto `true`, comportamiento A5 intacto).
- `ControlsEditor`: con el juego en pausa; arrastrar mueve (ajuste a bordes con margen de 8 dp, posiciones normalizadas), tocar selecciona, botones − / + (10 %, 0,6–1,6), «Restablecer» y «Listo»; persiste en la orientación actual.
- **No tocar:** `A/saves/**`, `C/**`, biblioteca.

### L5 · Catálogo, script y verificación
- **Nuevos:** `D/catalog/{SettingsCatalog, LibraryCatalog, GameplayCatalog}.kt` (si cada lote no los creó ya), `android/app/src/androidTest/.../CatalogCoverageTest.kt`, `docs/diseno-android/VERIFICACION.md`.
- **Modificados:** `D/DebugCatalog.kt` (registro; IDs :81-158), `D/DebugIntent.kt` (args `opacity`, `visibility`, `dpad`, `scaleMode`, `color`, `palette`, `orientation`, `dynamicSeed`), `tools/android-screenshots.sh` (lista desde un único archivo `tools/android-screens.txt` que también lee el test; rota todo lo marcado `landscape`, no solo `*-dialog` :35; añade pasada de color dinámico).
- Datos sintéticos: ROM de `D/DebugSyntheticRom.kt`, portadas generadas con patrones deterministas (nunca capturas de juegos comerciales).

## Catálogo (L5)
Tema: C = claro, O = oscuro, Dyn = color dinámico con semilla fija (2 semillas: verde y violeta) en Android 12+ emulado. Gameplay siempre oscuro (K4), una sola variante.

| ID Android | ID iOS | Argumentos debug | Orientación / tema |
|---|---|---|---|
| `launch` | `launch` | `screen=launch` (splash de sistema + biblioteca) | V · C/O (icono real es A8) |
| `library-folder-empty` | `library-empty` | carpeta sintética sin ROM | V · C/O |
| `library-cloud-pending` | `library-cloud-pending` | entrada `REMOTE_UNAVAILABLE` | V · C/O |
| `library-cloud-downloading` | `library-cloud-downloading` | apertura con lectura remota en curso | V · C/O |
| `library-scan-progress` | `library-scan-progress` | escaneo congelado en 3 de 8 | V · C/O |
| `library-scan-summary` | `library-scan-summary` | 2 nuevos | V · C/O |
| `library-continue` | `library-continue` | 3 recientes con portada | V · C/O/Dyn×2 |
| `library-grid` (ampliada) | `library-grid` | con carril y portadas | V · C/O/Dyn×2 |
| `search-active` | `search-active` | campo enfocado, IME | V · C/O |
| `search-results` | `search-results` | `query=te` | V · C/O |
| `library-search` | `search-no-results` | sin resultados + «Buscar en todos» | V · C/O |
| `game-context-menu` | `game-context-menu` | menú abierto sobre tarjeta 1 | V · C/O |
| `remove-game-confirm` | `remove-game-confirm` | diálogo ocultar | V · C/O |
| `game-settings` | `game-settings` | hoja con personalizado | V · C/O |
| `library-detail` (ampliada) | `game-details` | con portada y Ajustes | V · C/O/Dyn×2 |
| `favorites` (ampliada) | `favorites` | con carril | V · C/O |
| `settings-controls` | `settings-controls` | — | V · C/O/Dyn×2 |
| `settings-display` | `settings-display` | — | V · C/O |
| `settings-emulation` | `settings-emulation` | `color=1 palette=5` | V · C/O |
| `settings-audio` | `settings-audio` | `volume=0.6` | V · C/O |
| `settings-storage` | `settings-storage` | tamaños sintéticos | V · C/O |
| `settings-licenses` | (dentro de `settings-about`) | — | V · C/O |
| `gameplay-landscape` | `game-acid` horizontal / `gameplay-landscape` | `orientation=landscape` | H · O |
| `gameplay-landscape-clear` | `gameplay-landscape-clear` | `visibility=on_touch` tras 3 s | H · O |
| `gameplay-landscape-hidden` | `gameplay-landscape-hidden` | `visibility=hidden` (pista) | H · O |
| `gameplay-portrait-arrows` | `gameplay-portrait-arrows` | `dpad=arrows` | V · O |
| `gameplay-landscape-arrows` | `gameplay-landscape-arrows` | `dpad=arrows orientation=landscape` | H · O |
| `gameplay-fill` | (iOS vertical por defecto) | `scaleMode=fill orientation=landscape` | H · O |
| `customize-controls-portrait` | `customize-controls-portrait` | editor abierto | V · O |
| `customize-controls-landscape` | `customize-controls-landscape` | editor abierto | H · O |
| `customize-controls-size` | `customize-controls-size` | A seleccionado, escala 1,3 | V · O |
| `load-state-confirm` | `load-state-confirm` | slot 2 ocupado | V · O |
| `replace-state-confirm` | `replace-state-confirm` | slot 1 ocupado | V · O |
| `gameplay-header-damaged` | (aviso de `AppState.swift:287`) | ROM sintética con checksum malo | V · O |
| `pause-sheet` (ampliada) | `gameplay-pause` | título del juego, Personalizar controles | V · O |

Quedan para A7: `gameplay-controller`, `*-reduce-transparency` (contraste alto), `library-ax5`. Las 31 IDs existentes se conservan; `gameplay-controls`, `pause-*`, `states-*`, `exit-*` pasan a renderizarse en oscuro forzado.

## Tests por lote
- **L1 JVM:** `GameplaySettingsTest` (por defecto; `sanitized` con cada campo inválido; opacidad fuera del conjunto; recortes de escala y volumen; paleta > 12 ⇒ 0; claves `perGame` inválidas; `setOverrides` borra entrada vacía; `move`/`resize`/`resetLayout`/`isFactoryLayout` por orientación sin cruzarse; `emulation()` para DMG/GBC × global/por juego), `GameplaySettingsFileTest` (ida y vuelta; `.tmp` huérfano recuperado; corrupto en cuarentena; campo desconocido ignorado; `controllerMapping` ausente ⇒ null), `StorageUsageTest`, `AppNavigationStateTest` ampliado. **Instrumentados:** `SettingsUiTest` (navegar a las 9 pantallas, persistencia tras recrear, Restablecer deshabilitado de fábrica), `AppShellTest` ampliado.
- **L2 JVM:** `ViewportTest` (entero y llenar, 10:9 exacto, sin bandas en vertical, tamaños degenerados), `EmulationOptionsTest`. **Instrumentados:** `EmulationOptionsBridgeTest` (ROM sintética DMG con `CGB` ⇒ `RomInfo.cgbCompat`; paleta en caliente cambia el framebuffer de forma determinista; rangos inválidos ⇒ error sin handle filtrado; `DMG` pedido para ROM solo-CGB ⇒ `CoreError` tipado), `NativeAudioTest` ampliado (volumen 0 ⇒ muestras 0), `OpenFromLibraryTest` ampliado (cabecera dañada ⇒ aviso y juego abierto; ajustes por juego aplicados). `make -C core test` y `make -C core asan` sin cambios en `core/` como regresión.
- **L3 JVM:** `ArtworkStoreTest` (fotograma uniforme descartado; escritura atómica con fallo inyectado deja el anterior; `removeAll` solo borra `artwork/`; tope de lectura), `PlaceholderSeedTest` (FNV-1a estable con vectores fijos; mismos 4 colores para la misma huella), `LibraryQueryTest` (recientes ≤ 5 solo con portada), `LibraryPreferencesTest` ampliado (`knownIds`, `isNew`, compatibilidad con JSON de A5), `LibraryViewModelTest` ampliado (progreso X de Y, resumen de nuevos). **Instrumentados:** `LibraryUiTest` ampliado (carril, menú contextual completo, búsqueda con recuento, «Buscar en todos», «Ver todos», pull-to-refresh), `GameSettingsSheetTest` (GBC deshabilitado; volver a global borra entrada), `ArtworkCaptureTest` (cerrar sesión sintética escribe `artwork/<fp>.png`; fallo de escritura no cambia `CloseResult`).
- **L4 JVM:** `ControlGeometryTest` ampliado (escala global × por control, área segura, cruceta en flechas con mismos sectores), `ControlsFadeControllerTest` (reloj inyectado: 3 s, toque reaparece, Ocultos con pista), `SpeedCycleTest`. **Instrumentados:** `GameplayUiTest` ampliado (HUD pausa, ciclo ×1→×2→×4, tema oscuro con app en claro, iconos de barra claros), `ControlsEditorTest` (mover, − / +, restablecer, persistencia por orientación), `GameControlsViewTest` ampliado (háptica por sector, opacidad no cambia hit-test), `StatesConfirmTest` (Guardar actual y cargar vs Cargar sin guardar; AUTO intacto en el segundo), `ImmersiveModeTest` (barras ocultas en sesión y restauradas al salir).
- **L5 instrumentado:** `CatalogCoverageTest` (cada ID de `tools/android-screens.txt` existe en `DebugCatalog`, y viceversa; cada ID A6 de la tabla de arriba presente; ninguno duplicado), `DebugCatalogTest` ampliado.

## Criterios de aceptación (comando que lo verifica)
1. `cd android && ./gradlew --no-daemon --max-workers=1 testDebugUnitTest` en verde; recuento ≥ 241 + nuevos.
2. Emulador sin ventana (`emulator -avd <avd> -no-window -no-audio -gpu swiftshader_indirect`) y `./gradlew --no-daemon --max-workers=1 connectedDebugAndroidTest` en verde; recuento ≥ 191 + nuevos.
3. `./gradlew --no-daemon --max-workers=1 assembleDebug assembleRelease` sin avisos nuevos de lint de error.
4. `aapt2 dump permissions app/build/outputs/apk/release/app-release-unsigned.apk` sin `android.permission.INTERNET` (salida pegada).
5. DEX de Release sin clases Debug: `unzip -p app-release-unsigned.apk 'classes*.dex' | strings | grep -c 'pocketgb/debug/'` = 0.
6. `make -C core test && make -C core asan` en verde y `git diff --stat main -- core/` vacío.
7. `git diff 8953d65 -- android/app/src/main/java/com/joelbermudez/pocketgb/saves/` vacío; cambios en `GameSession.kt` limitados a `saveCurrentToAuto` y al callback de portada, revisados en auditoría.
8. `tools/android-screenshots.sh` genera todas las IDs × variantes; `CatalogCoverageTest` en verde; `VERIFICACION.md` con la revisión visual firmada por el auditor.
9. Prueba de regresión A5: `tools/android-save-kill-test.sh` 50 iteraciones sin pérdida.
10. Auditoría con informe `docs/auditorias/A6-android-evidencia.md` (Codex o subagente Opus si Codex implementó).

## Riesgos y cómo se acotan
- **Contraste de controles sobre fotogramas claros:** capa oscura localizada detrás de cada control (no a pantalla completa), colores fijos independientes del color dinámico, etiqueta ≥ 70 %; captura `gameplay-landscape` con ROM sintética de fondo blanco para revisarlo. El cálculo formal de contraste y el modo alto contraste son A7.
- **Memoria del Mac durante las corridas:** nunca más de dos lotes en paralelo; Gradle siempre con `--no-daemon --max-workers=1`; un solo emulador sin ventana; cerrar el emulador entre lotes; capturas por tandas.
- **Rotación que hoy pausa** (desviación 6 de A5): se mantiene; el catálogo horizontal se genera arrancando ya en horizontal. Rotación sin pausa es A7.
- **Exclusión de gestos limitada** (200 dp por borde): se excluyen solo la cruceta y A/B; si no cabe, prioridad a la cruceta. Se documenta.
- **Llenar con escala no entera** puede producir columnas de ancho desigual: vecino más cercano con paso precalculado; aceptable por SPEC §4.1 («sin suavizado perceptible»).
- **Portada en el cierre retrasa la salida:** la copia del framebuffer es 92 KiB en el hilo principal (sesión aparcada) y la codificación va a otro executor; si la sesión no está aparcada se omite.
- **Colisión de lotes paralelos** en `GameplayViewModel.kt` y `strings_gameplay.xml`: regla anticolisión arriba; el segundo en fusionar rebasa.
- **Huella ausente** para ajustes por juego: K6 la calcula bajo demanda; tests cubren el caso.
