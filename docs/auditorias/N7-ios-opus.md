# N7 iOS · Auditoría (subagente Opus)

**Veredicto:** APROBAR CON CAMBIOS. Resumen fiel del informe que transmitió el coordinador. Las respuestas van en `N7-ios-respuesta.md`.

## Hallazgos
| Id | Gravedad | Dónde | Hallazgo |
|---|---|---|---|
| H1 | **Alta** (regla 6) | `SaveImport.swift` (`installContinuation`) | Sobrescribe el `.auto` sin guardar el propio cuando `relation == .same` y en juegos sin batería. El auditor lo reprodujo: el automático se reemplaza y el anterior no queda guardado. |
| H2 | Baja | `AppState` (`divergenceChoice`) | Si `open` sale antes, la elección de divergencia pendiente se aplica en la siguiente apertura de cualquier juego. |
| H3 | Baja | «Abrir con» de un `.sav` | Se asigna al primer juego con el mismo nombre base; la pregunta no nombra el juego; con `noLocal` se instala sin preguntar. |
| H4 | Baja | `PackageMeta` | META menos estricta que docs/12: con claves repetidas gana la primera (en Android, la última); acepta números como `1.0` o `1e3`; no exige hex de ancho completo; cuenta longitudes en grafemas. |
| H5 | Baja | `SaveExport` | `base_sav_sha256` es solo la última importación (Android usa la última partida recibida de fuera, incluidos el cambio externo y el espejo). |
| H6 | Baja | Exportar e importar | iOS no exporta `config` ni aplica los metadatos; un `STAT` con otra configuración se borra sin un aviso claro. |
| H7 | Baja | `SaveOpening.prepare` | La relación se calcula con una local de tamaño incorrecto: momento «Conflicto» inútil y aviso falso. |
| H8 | Baja | Guía y «Abrir con» | La guía dice que una copia en conflicto se abre con «Abrir con», pero falla por el nombre. |
| N1 | Nota | Importar | `appendBeforeLoad` sin `trimRing`: el anillo pasa de 3 y el siguiente guardado expulsa varias entradas de golpe. |
| Nota | — | Linaje del espejo | Al ignorar un espejo `ownEarlier`, conviene un aviso no bloqueante. Renombrar `newerExternalMirrorWinsAndBacksUpLocal`. |

Resto correcto: 339 tests en verde, sin red y vectores idénticos a los de Android.

H4, H5 y H6 se unificarán con Android cuando llegue su auditoría (decisión del coordinador).
