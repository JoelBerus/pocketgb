## Veredicto: APROBAR CON CAMBIOS

## Hallazgos

| ID | Severidad (bloqueante/alta/media/baja) | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|---|---|---|---|---|---|
| A4-V2-01 | media | `android/app/src/main/java/com/joelbermudez/pocketgb/library/LibraryScanner.kt:74` | La corrección H2 no garantiza IDs estables cuando dos IDs de documento tienen el mismo `String.hashCode()`. | En líneas 78–80, ambos documentos reciben inicialmente el mismo sufijo y la colisión se resuelve añadiendo `~` al segundo según el orden del proveedor. Por ejemplo, `"Aa"` y `"BB"` tienen el mismo hash Java; invertir su orden intercambia qué URI recibe `#00000840` y cuál `#00000840~`. Favoritos, ocultos y huellas pueden quedar asociados al ROM equivocado. El test de líneas 143–172 invierte el orden, pero usa IDs sin colisión de hash, por lo que no detecta el caso. | Derivar el sufijo de un hash criptográfico suficientemente largo del ID completo o, ante colisión, ordenar el grupo por ID antes de asignar sufijos deterministas. Añadir un test con `"Aa"`/`"BB"` e inversión del proveedor que compare `URI → id`. |
| A4-V2-02 | baja | `android/app/src/test/java/com/joelbermudez/pocketgb/library/LibraryViewModelTest.kt:374` | Las pruebas de H1 no demuestran de forma determinista que fallen con la implementación anterior. | `aChangeInsideTheLoadWindowCannotLeaveTheDiskBehindMemory` ejecuta el cambio desde `loadHook`, antes de que `load()` termine. El código anterior ya acumulaba correctamente cambios en esa fase; la carrera original estaba después de publicar la carga y antes de encolar su snapshot. El test de 150 rondas puede descubrirla probabilísticamente, pero no fuerza esa ventana. La propia evidencia reconoce: “No se comprobó que fallen contra el código anterior”. | Añadir un seam/latch inmediatamente antes de encolar la persistencia de la carga, reproducir el orden exacto de H1 y comprobar mediante mutation test que el test falla al restaurar el escritor anterior. |
| A4-V2-03 | baja | `docs/auditorias/A4-android-evidencia.md:5` | A4-04/H12 sigue incompleto: la evidencia no fija el commit de las correcciones auditadas. | El documento todavía dice que las correcciones están “en el árbol de trabajo (sin commit)” y que posteriormente habrá que anotar el hash. El commit real es `5f62e059489feddceeadc3d52ac88d4aa99b34ce`. La sección de correcciones también atribuye las cifras al “árbol de trabajo corregido”. | Registrar explícitamente `5f62e059489feddceeadc3d52ac88d4aa99b34ce` como commit de las correcciones y asociar a él los resultados de 72 JVM, 70 instrumentados, builds, lint y core. |

## Criterios del hito

| Criterio | Verificado por el auditor (sí/no) | Resultado |
|---|---:|---|
| Historial sin ROMs comerciales, boot ROMs, `.sav`, estados ni APK | sí | Revisados `git log --all --stat`, nombres históricos y archivos versionados. Solo aparecen extensiones en documentación, reglas y ROMs sintéticos generados por tests. |
| Sin código GPL/AGPL incorporado | sí | El diff `44cc0cb..5f62e059` contiene implementación propia Android, tests y recursos; no encontré fragmentos o atribuciones procedentes de Gambatte, Delta, mGBA o `iOS/` de SameBoy. |
| Sin red ni permisos de red | sí | El manifiesto no declara `INTERNET` ni `ACCESS_NETWORK_STATE`; no se añadieron clientes o APIs de red. |
| `core/` sin estado global mutable ni regresiones funcionales | sí | El commit no modifica `core/`. No hay cambios de opcode, registro, ciclo, `gb_state_load` ni SRAM que contrastar contra Pan Docs o `docs/03-core-spec.md`. |
| A4-01: revocación en subcarpeta se propaga | sí | `LibraryScanner.kt:52–57` propaga `TreePermissionException`; los tests JVM, ViewModel e instrumentado ejercitan raíz válida y subcarpeta revocada. Fallarían con la captura anterior de todo `IOException`. |
| A4-02: fallo de preferencias queda pendiente y se informa | sí | `lastSaved` solo avanza tras `save`; `flushPreferences()` devuelve `Failed`; hay reintento en `ON_STOP` y persistencia final serializada por `persistLock`. Los tests inyectan suficientes fallos para comprobar memoria, disco y reintento. |
| A4-03/H7: atomicidad y recuperación realmente probadas | sí | Los tests inyectan fallo de escritura, fallo de rename, temporal completo y temporal parcial. Cancelación y lectura SAF >8 MiB usan latches/contenido real. |
| H1: preferencias nunca quedan detrás de memoria | parcialmente | La implementación nueva elimina el snapshot obsoleto y persiste `_prefs.value`, pero falta una prueba determinista que reproduzca exactamente la ventana original; véase A4-V2-02. |
| H2: IDs únicos, estables e independientes del orden | no | Son únicos, pero no estables ante colisiones controlables de `String.hashCode()`; véase A4-V2-01. |
| H3: proveedores SAF mal portados producen errores tipados | sí | Se preserva `CancellationException`; `RuntimeException` se convierte en `IOException`/`DocumentReadException`; columnas opcionales y tamaños no numéricos se toleran. |
| H4: I/O transitorio no se confunde con corrupción | sí | `load()` propaga I/O; solo errores de decodificación se ponen en cuarentena; nombres de cuarentena no se pisan; enums desconocidos usan valores por defecto. |
| H5: operaciones de carpeta con Mutex y generaciones | sí | Se cancela la operación previa, se serializan efectos y solo la generación vigente publica. Los tests fuerzan olvidar durante escaneo y durante selección. |
| H6: localidad remota consistente | sí | Árbol y `ContentResolverRomSource` comparten `ProviderLocality.isRemote`; `DetailsError.Remote` queda alcanzable y probado. |
| H8: formato/geometría de `ANativeWindow` validados | parcialmente | El código valida RGBA/RGBX, `bits`, dimensiones y stride, y descarta la ventana si falla `setBuffersGeometry`. No existe test dedicado que falle al retirar estas defensas; la evidencia lo reconoce. |
| H9: error diferenciado cuando no se conserva acceso | sí | `chooseFolder` publica `AccessNotKept` para fallos de concesión y conserva la carpeta anterior; existe cobertura JVM y Compose. |
| H10: estado inicial de carga | sí | El estado inicial es `Loading`; ViewModel, detalle, favoritos y UI tienen pruebas específicas. |
| H11: URI SAF excluido de backups | sí | Ambas políticas excluyen `sharedpref/library_folder.xml` de nube y transferencia; el manifiesto las referencia. A5 todavía deberá decidir la política de `saves/`. |
| H12/A4-04: evidencia fija el commit corregido | no | Sigue fijando solo `44cc0cb` y describe las correcciones como no commiteadas; véase A4-V2-03. |
| Navegación `singleTop` para detalle | sí | `AppNavigationState.push` ignora una ruta igual a la cima y existe test directo. |
| ROM hostil limitada antes de llegar al núcleo | sí | El detalle solicita como máximo 8 MiB + 1 y rechaza el byte adicional; tamaño SAF declarado como pequeño no evita el límite de lectura. |
| Partidas/SRAM seguras | sí | A4 no crea ni escribe SRAM; “Jugar” continúa deshabilitado hasta A5. No se introdujeron rutas de backup o sobrescritura de partidas. |
| Evidencia de 72 JVM, 70 instrumentados, Debug/Release/lint y core 65/65 | no | Es plausible y consistente con el código, pero no pude reejecutarla: el sandbox impidió incluso crear la copia requerida en `/tmp`. Esto no es un hallazgo del proyecto. |
| Selector SAF y permisos persistentes contra proveedor real | no | La propia evidencia indica que el selector y `takePersistableUriPermission` no se automatizaron. Queda para la prueba manual en dispositivo; no es un hallazgo. |

## Notas

- Commit auditado: `5f62e059489feddceeadc3d52ac88d4aa99b34ce`.
- Diff auditado: `44cc0cb8ecda6adec010c8a682e42ff1f813c2c2..5f62e059489feddceeadc3d52ac88d4aa99b34ce`.
- Los informes `A4-android-codex-v2.md` y `A4-android-deepseek-v2.md` están sin versionar y no se usaron como evidencia del commit.
- No encontré violaciones bloqueantes, riesgos nuevos de SRAM, lecturas ROM sin límite ni regresiones en el núcleo.
- El cambio puede aprobarse tras estabilizar los IDs frente a colisiones y corregir la trazabilidad documental.
