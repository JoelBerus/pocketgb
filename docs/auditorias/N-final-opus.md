# Auditoría conjunta final del nivel N (Opus, subagente sin historial)

Fecha: 2026-10-10. Alcance: `siguiente-nivel` @ `0102392` frente a `origin/main` (`51416b4`, PR #18): 507 archivos. Ejecución en `git archive` (`/private/tmp/claude-501/audit-final`). Resumen fiel del informe.

## Veredicto: APROBAR CON CAMBIOS

Nada bloqueante. Reglas duras 1–5 cumplidas (sin ROMs/.sav/.pgbm/estados/BIOS/imágenes con copyright en git; núcleo sin globales; iOS sin red; Android sin INTERNET). Ninguna ruta pierde el `.sav` sin copia.

| ID | Sev. | Plataforma | Problema |
|---|---|---|---|
| H1 | alta | iOS | «Continuar» borra el AUTO sin copia si es de otra configuración o su RAM no coincide (contradice ND21). |
| H2 | alta | Android | `siblingRomsSharingBase` no cuenta `.gba`: `Juego.gb` y `Juego.gba` comparten `Juego.sav` sin modo compartido. |
| H3 | media | Android | `recordReceivedHash` pierde `location` del historial del espejo. |
| H4 | media | ambas | Paridad del linaje: iOS cuenta las recibidas como historial propio; Android no. |
| H5 | media | Android | «Abrir con» quita el sufijo de conflicto antes de probar el nombre exacto. |
| H6 | media | Android | Momento «Conflicto» en KEEP_LOCAL sin estado ni miniatura; estado entrante descartado. |
| H7 | baja | iOS | Precedencia de ternario: falta el aviso de configuración en `.alreadyCurrent`. |
| H8 | baja | Android | El anillo expulsa del índice antes de confirmar; un fallo del anillo se traga al importar. |
| H9 | baja | Android | Espejo SAF `"wt"` no atómico: un prefijo podría instalarse como cambio externo. |
| H10 | baja | iOS | Instalar SRAM de momento/backup deja el AUTO no vigente sin copia. |
| H11 | baja | iOS | Restaurar en Ajustes › Partidas sin `withExclusive`. |
| H12 | baja | Android | Cadenas sin uso y condiciones siempre verdaderas tras las fusiones. |
| H13 | baja | ambas | META: tags en UTF-16 (Android); `null` en opcionales (iOS). |
| H14 | baja | docs | Tablas de hitos y ESTADO.md desfasados; N9 guía sin auditoría propia (cubierta por esta). |
| H15 | baja | docs | ND20 (i) pide fusionar colección y nota, que no viajan a nivel de juego. |

Paridad menor: carril ND17 (orden filtrar/tomar), botón «Continuar donde lo dejaste» en el detalle de Android, formato de Dropbox en copias en conflicto, ALREADY_CURRENT, prioridad de tamaños de cabecera (ND20 j).

## Verificado por el auditor
- `make -C core test` y `asan`: 11 349 comprobaciones, 65/65 requeridos. `check-globals` OK. Fuzz de humo pgbm y progress 60 s sin fallos.
- Android: 818 JVM, lint 0 errores, Release sin INTERNET (aapt2).
- iOS: 371 tests en iPhone 17 Pro; Release `generic/platform=iOS` OK; binario sin Network/WebKit/CFNetwork; guía del bundle = docs/guia.
- No ejecutado: instrumentadas y kill-test Android (evidencia N9), `make -C gba test` (falta ld.lld), pruebas de Joel.

## Respuesta del orquestador
- H1–H13 y la paridad menor: corregidos en `fin-ios` y `fin-android` (ver `N-final-respuesta-ios.md` y `N-final-respuesta-android.md`).
- H4: se adopta la regla de iOS, anotada como ND20 (b′).
- H14: tablas, ESTADO.md y PRUEBAS-JOEL actualizados al cierre; la guía N9 queda auditada por este informe.
- H15: ND20 (i) enmendado.
