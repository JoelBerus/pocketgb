## Veredicto: APROBAR CON CAMBIOS

## Hallazgos

| ID | Severidad (bloqueante/alta/media/baja) | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|---|---|---|---|---|---|
| A4-01 | media | `android/app/src/main/java/com/joelbermudez/pocketgb/library/LibraryScanner.kt:49` | Una revocación de permiso al enumerar una subcarpeta se oculta y el escaneo termina como `Ready` con una biblioteca parcial. | `SafDocumentTree.children()` convierte `SecurityException` en `TreePermissionException`, que hereda de `IOException`. `candidates()` captura cualquier `IOException` en líneas 51–55 y continúa, impidiendo que `LibraryViewModel.scan()` publique `PermissionRevoked`. Las pruebas solo ejercitan la revocación al listar la raíz. | Propagar explícitamente `TreePermissionException`; ignorar únicamente errores recuperables de una subcarpeta. Añadir una prueba donde la raíz se enumera correctamente y el permiso se revoca antes de listar una subcarpeta. |
| A4-02 | media | `android/app/src/main/java/com/joelbermudez/pocketgb/library/LibraryViewModel.kt:78` | Los fallos al persistir preferencias se silencian y `flushPreferences()` informa éxito aunque la última versión no haya llegado al disco. | El escritor captura `IOException` en líneas 80–84 y siempre completa `request.done` en la línea 86. Un fallo en la última escritura antes de salir puede perder favoritos, ocultos u orden; el supuesto “siguiente cambio reintenta” no cubre la salida inmediata. | Mantener la última escritura fallida como pendiente, hacer que `flushPreferences()` propague el error o devuelva un resultado tipado y reintentar al detener la aplicación. Añadir una prueba con fallo de I/O inyectado. |
| A4-03 | baja | `android/app/src/test/java/com/joelbermudez/pocketgb/library/LibraryPreferencesTest.kt:82` | El test denominado `writesAtomically` no demuestra atomicidad ni recuperación frente a una escritura interrumpida. | Solo comprueba round-trip y ausencia de `.tmp`. No verifica que el destino previo sobreviva a un fallo, el comportamiento del rename ni la recuperación de un temporal completo. | Inyectar las operaciones de archivo o usar una abstracción probada: simular fallo antes del rename, comprobar conservación del archivo anterior y recuperación del temporal. |
| A4-04 | baja | `docs/auditorias/A4-android-evidencia.md:5` | La evidencia identifica incorrectamente el estado del hito auditado. | El documento dice “sin commit; pendiente de revisión”, pero está contenido en `44cc0cb8ecda6adec010c8a682e42ff1f813c2c2`. Los resultados podrían corresponder al árbol actual, pero el documento no fija el commit verificado. | Sustituirlo por el hash completo `44cc0cb…` y, cuando se repitan verificaciones tras correcciones, registrar también el nuevo hash. |

## Criterios del hito

| Criterio | Verificado por el auditor (sí/no) | Resultado |
|---|---:|---|
| Historial sin ROMs comerciales, boot ROMs, `.sav`, estados ni APK | sí | `git log --all --stat`, nombres históricos y archivos versionados revisados. No encontré artefactos prohibidos. Las ROMs de diagnóstico son arrays sintéticos generados en memoria. |
| Sin código copiado de GPL/AGPL | sí | No encontré atribuciones ni fragmentos identificables de Gambatte, Delta, mGBA o `iOS/` de SameBoy en el port Android. |
| Manifiesto sin red | sí | `AndroidManifest.xml` no declara `INTERNET`, `ACCESS_NETWORK_STATE`, WebView ni componentes de red. |
| A1: Compose, Material 3, Navigation 3, edge-to-edge y separación Debug/Release | sí | El código y source sets corresponden a la evidencia. No pude reejecutar build/lint. |
| A2: ROM limitada a 8 MiB antes del núcleo | sí | `ContentResolverRomSource` lee como máximo el límite solicitado; `loadDetails()` solicita 8 MiB + 1 y rechaza el byte adicional. `CoreBridge` y `EmulatorSession` vuelven a comprobar el máximo. |
| A2: propiedad del handle y acceso al core desde el hilo nativo de sesión | sí | `native_session` conserva el `gb*`; `gb_run_frame`, framebuffer y audio se operan en su hilo. Pausa y stop usan barreras con mutex/condition. |
| A2: límites JNI y arrays | sí | Se validan handles nulos y tamaño del framebuffer; los bytes JNI se liberan con `JNI_ABORT`. El núcleo conserva sus propias validaciones de cabecera y tamaño real. |
| A3: ring SPSC sin bloqueos en callback | sí | Índices atómicos release/acquire, capacidad fija y silencio en underrun; el callback no reserva memoria, bloquea, registra logs ni usa JNI. |
| A3: AudioFocus y lifecycle no reanudan automáticamente | sí | Pérdida de foco, `ON_PAUSE` y `ON_STOP` pausan. No existe reanudación automática al recuperar foco o foreground. |
| A4: selector SAF y permisos persistentes | parcialmente | La implementación usa `ACTION_OPEN_DOCUMENT_TREE` y `takePersistableUriPermission`; el flujo real no está cubierto por los tests instrumentados y existe A4-01. |
| A4: raíz y un nivel de subcarpetas, solo `.gb`/`.gbc` | sí | El escáner respeta profundidad y extensiones; ignora archivos y carpetas ocultos. |
| A4: estados diferenciados de permiso, carpeta y lectura | parcialmente | Funcionan en la raíz, pero una revocación durante una subcarpeta puede degradarse silenciosamente a biblioteca parcial. |
| A4: preferencias privadas y atómicas | parcialmente | Se escribe temporal, se sincroniza y se renombra; no se verifica recuperación y los fallos finales se ocultan. |
| A4: búsqueda, filtros, favoritos, ocultos, orden y detalle | sí | La lógica y la UI están conectadas al `LibraryViewModel`; hay cobertura JVM y Compose relevante. |
| A4: detalle mediante núcleo real y SHA-256 | sí | La lectura acotada se entrega a una instancia efímera de `CoreBridge`, cerrada con `use`; la huella se registra en preferencias. |
| Regla dura 6: no jugar antes de implementar partidas seguras | sí | El botón usa `enabled = false`, no tiene acción y la biblioteca no crea una `EmulatorSession`. |
| Evidencia: 49 JVM, 61 instrumentados, Debug/Release/lint | no | La evidencia es detallada y plausible, pero no pude reejecutarla: el sandbox denegó incluso crear la copia temporal en `/tmp`. No es un hallazgo del proyecto. |
| Pruebas instrumentadas contra selector/proveedor SAF real | no | La propia evidencia reconoce que no ejercita `takePersistableUriPermission` ni revocación real. Requiere emulador/teléfono y prueba manual. |
| Corrección Pan Docs / `docs/03-core-spec.md` | sí | El hito no modifica `core/`. La lectura de título CGB, flag `0x143` y checksum `0x134…0x14D` coincide con la especificación existente. |
| Pérdida de SRAM | sí | A1–A4 no crean, cargan ni escriben SRAM. El único acceso de juego permanece bloqueado hasta A5. |

## Notas

- Commit auditado: `44cc0cb8ecda6adec010c8a682e42ff1f813c2c2`.
- No encontré hallazgos bloqueantes, red, estado global mutable añadido a `core/`, ROMs fuera de rango entregadas al núcleo ni rutas capaces de sobrescribir partidas.
- No se modificó `core/`, por lo que no hay opcodes, registros o ciclos nuevos que contrastar contra Pan Docs.
- No pude validar visualmente las 32 capturas porque permanecen correctamente ignoradas y no están disponibles en el árbol auditado.
- Antes de A5 conviene corregir A4-01 y A4-02: ambos afectan la fiabilidad de la biblioteca y de los metadatos privados, aunque todavía no ponen SRAM en riesgo.
