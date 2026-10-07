# Auditoría N1 iOS · identidad y carpetas (Opus, subagente sin historial)

Fecha: 2026-10-07. Rama `n1-ios-identidad` en `b216c2a` (diff `siguiente-nivel...n1-ios-identidad`, 25 archivos). Rol: solo lectura; ejecución en `/private/tmp/claude-501/audit-n1-ios` con el mutex `sim`. Sondas P1–P6 en un test añadido solo a la copia temporal. Informe transcrito (formato condensado, contenido sin cambios).

## Veredicto: APROBAR CON CAMBIOS
Nada bloqueante: la huella coincide con la del núcleo, el escaneo cumple ND11 y los 200 tests pasan. Antes de fusionar: H1–H3 (medios; H1 y H3 tocan la regla 6 y la exactitud de la identidad).

## Hallazgos
| ID | Severidad | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|---|---|---|---|---|---|
| H1 | media (regla 6; anterior, N1b lo expone más) | `Saves/SaveMirror.swift:157-161`, `Saves/SaveStore.swift:7,137-149`, `Library/LibraryScanner.swift:99`, `docs/guia/carpetas.md:45,52-56` | El `.sav` espejo no tiene identidad. Un duplicado con su propio `.sav` (otra partida), o un `.sav` de otro juego con el mismo nombre, se resuelve **por fecha** contra la partida local compartida por huella; el perdedor va solo al anillo rotativo de 5 backups y desaparece tras 5 guardados. Antes de N1 las copias profundas eran invisibles; la guía dice «si no lo mueves, no pasa nada». | Sonda P5: local = A; abrir la copia B con `.sav` más nuevo → `loaded=B? true localNow=B? true`; tras 5 guardados `playA in backups: false`. | Hasta N7a: si el espejo no se reconoce y difiere de la local, el perdedor va a una cuarentena que **no rota** (`backups/<huella>.mirror-<unix>.sav`, como `wrong-size`), al menos con entradas duplicadas. Corregir la guía (dos copias con partidas distintas acaban en una; copias a `_Revisar/`). Test con espejo **más nuevo**. |
| H2 | media | `Library/LibraryPreferences.swift:364-385` | Se decodifica antes de mirar la versión: un `preferences.json` de versión futura que v2 no entiende se aparta como `.corrupt`, la app arranca vacía y escribe un v2 nuevo; al volver a la versión nueva sus datos ya no están. La alerta dice «dañadas». | Sonda P2 `{"version":3,"games":[…]}` → `quarantined`, nuevo `preferences.json` v2. | `fileVersion(raw)` antes de decodificar; si > 2: `writesBlocked` con `.newerVersion`, sin cuarentena; test con v3 indecodificable. |
| H3 | media | `Library/RomFingerprint.swift:123-129`, `Library/LibraryPreferences.swift:212-214,407-419` | Caché validada solo con ruta, tamaño y fecha: otro ROM con el mismo tamaño y fecha en la misma ruta (sets TorrentZip; ROMs GB con tamaños potencia de 2) recibe una huella verificada y obsoleta; hasta abrirlo muestra metadatos, portada y «Continuar» del otro y guarda las ediciones en la huella del otro. La partida no se ve afectada. | Sonda P3: mismo tamaño y `mtime` 1996-12-24 → `entry=fpA stale=true`. | Añadir a la clave `attributeModificationDateKey` (ctime) o `fileResourceIdentifierKey`/inodo o la fecha de creación; test. |
| H4 | baja | `Library/LibraryPreferences.swift:126-129,322-325,370-376` | Formato detectado solo por la presencia de `version`: `null` → se lee como v1 y se reescribe vacío; `1` con contenido v1 → se lee como v2 y se sobrescribe sin copia ni aviso (requiere edición/daño). | P1 y P1b. | Migrar solo sin clave `version`; si no es entero ≥ 2, cuarentena. |
| H5 | baja | `App/AppState.swift:299,309-311,402` | `open(.resumeAutomatic)` busca el `.auto` con la huella de pista (caché) y no con `romFingerprint` ya calculada; `openLink` usa `overrides(for: entry)`. Sin pérdida (el núcleo rechaza un estado ajeno), pero «No se pudo continuar» falso y ajustes de color equivocados en el cable. | `core/src/state.c:508`, `CoreBridge.swift:37`. | Usar `romFingerprint`; en el cable calcular la huella de los bytes leídos. |
| H6 | baja | `Library/RomFingerprint.swift:15-31` frente a `core/src/cart.c:97-105` | Faltan las comprobaciones de código de RAM y MBC no soportado: huella para ROMs que el núcleo rechaza. | P4: MBC6 y RAM `0x09`. | Añadir las comprobaciones (o corregir el comentario) y casos en el test. |
| H7 | baja | `Library/LibraryStore.swift:223-285`, `App/AppState.swift:208-212,703-719` | Primer cálculo de huellas de una biblioteca grande: por lote, en el hilo principal, índice O(n), `markDuplicates`, reasignación de `entries` y `refreshContinuations()` que mira el `.auto` de todas las huellas (O(lotes × n)); tareas desordenadas; sigue durante la partida. | Lectura de código. | Refrescar continuaciones solo para huellas nuevas o al terminar, en serie; pausar el cálculo con sesión abierta. |
| H8 | baja | `Library/RomFingerprint.swift:47-52` | `isLocallyAvailable` lee `ubiquitousItemDownloadingStatus` de una URL con valores precargados al escanear; si iOS expulsa el archivo entre medias, la lectura coordinada forzaría la descarga. | Lectura de código. | `removeAllCachedResourceValues()` o URL nueva antes de comprobar. |
| H9 | baja | `Library/LinkPartnerPicker.swift:19` | El selector del cable ofrece la otra copia de un duplicado, que `LinkSession` rechaza con `sameGame`. | `LinkSession.swift:117`. | Excluir entradas con la misma huella. |
| H10 | baja | `Library/LibraryPreferences.swift:372-375` | Si `copyItem` falla o ya existía un `preferences.v1.json` parcial, la migración sobrescribe el v1 original sin copia válida. | P6. | Si la copia falla, no migrar en disco (bloquear y avisar); validar/versionar el nombre. |
| H11 | baja | `Library/LibraryScanner.swift:88-110`, `Library/LibraryStore.swift:43,45` | El tope de 5 000 cuenta solo candidatos ROM; un árbol enorme sin ROMs se recorre entero. `limitReached` e `isHashing` no se usan en la UI. | Lectura de código. | Tope también de entradas visitadas o documentarlo; mostrar `limitReached` o retirar las propiedades. |
| H12 | baja | Tests | Huecos: espejo de duplicado más nuevo (H1), versión futura indecodificable (H2), mismo tamaño y fecha (H3), `version` `null`/`1` (H4), MBC/RAM rechazados (H6), cableado `wireLibraryIdentity` → alerta al arrancar. | — | Añadir los casos. |

## Criterios del hito
| Criterio | Verificado | Resultado |
|---|---|---|
| Árbol de 5 niveles con `.oculta`, `_apartada`, `PocketGB/` | sí | `LibraryFoldersTests`: 5 sí, 6 no; `_` en cualquier nivel; `PocketGB` solo en la raíz; sin seguir enlaces a carpetas; placeholders sin leer. |
| Mover un ROM conserva todo | sí, con matiz | `movingARomBetweenFoldersKeepsEverything` pasa; depende de calcular la huella (H3). |
| Migración sin pérdida; corruptas apartadas y avisadas | sí, con salvedades | Fixture v1 fiel; `UserDefaults` intacto; migración repetible. Salvedades H2, H4, H10. |
| Huella = núcleo | sí | dmg-acid2 `464e14b7…`, cgb-acid2 `197fb0bc…`, arm.gba `77ee8866…`; relleno, truncado, > tope o sin `0x96` sin lecturas fuera de rango. Divergencia: H6. |
| Sin forzar iCloud; sin bloquear el hilo principal | sí (código/simulador) | Solo `.current`/`.downloaded`; `Task.detached(.utility)`. Pendientes H7 y H8. |
| Sin regresión | sí, en parte | 200 tests en 20 suites; catálogo N1 6/6 regenerado; catálogo completo no reejecutado (evidencia verificada por fechas). |
| Release `generic/platform=iOS` | sí | BUILD SUCCEEDED. |
| Reglas duras | sí | Sin binarios; sin red; `core/` y `Saves/*` sin cambios. |
| Guía y consejo | sí | Necesita los ajustes de H1. |

## Notas
- Regla 6: la partida local, los estados y la portada se eligen siempre con la huella que calcula el núcleo; ningún camino con huella de pista u obsoleta carga o borra la partida/AUTO de otro juego. El único riesgo real es el espejo sin identidad (H1). Cada copia de un duplicado escribe solo su propio `.sav`.
- No verificado: iCloud real, rendimiento con cientos de GBA de 32 MiB, migración de las preferencias reales de Joel, mitad Android.
- Menores: con preferencias en cuarentena se reimportan los ajustes antiguos de `UserDefaults`; «Nuevo» por ruta; tarjetas de duplicado indistinguibles hasta N4; proveedores sin fecha recalculan todo al volver a primer plano.
