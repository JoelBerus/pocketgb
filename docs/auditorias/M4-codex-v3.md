## Veredicto: RECHAZAR

## Hallazgos

| ID | Severidad (bloqueante/alta/media/baja) | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|---|---|---|---|---|---|
| H2 | bloqueante | `docs/auditorias/M4-evidencia.md:78` | Sigue pendiente la prueba obligatoria en el iPhone. | No consta dmg-acid2 en hardware, Pokémon Rojo llegando al menú ni respuesta de los botones. La captura del simulador es coherente, pero no sustituye el criterio. | Joel debe ejecutar y documentar las pruebas en el iPhone. |
| H3 | bloqueante | `docs/auditorias/M4-evidencia.md:18` | El comando literal de compilación firmado continúa sin demostrarse para el commit auditado. | El build documentado usa `CODE_SIGNING_ALLOWED=NO` y ocurrió a las 08:43, antes del commit final `7612751` de las 08:54. El intento firmado falló por falta de dispositivo y perfil. Las pruebas posteriores del simulador demuestran que las correcciones finales compilan para simulador, no el criterio exacto para dispositivo. | Registrar el iPhone, generar el perfil y ejecutar literalmente el comando de `docs/hitos/M4-ios-minima.md:17` sobre `7612751`. |
| H7 | alta | `ios/PocketGB/App/PocketGBApp.swift:19`, `ios/PocketGB/App/AppState.swift:57`, `docs/04-ios-spec.md:120` | No se guarda la SRAM al recibir un aviso de memoria baja. | La especificación exige guardar ante `didReceiveMemoryWarning`, pero la app solo reacciona a cambios de `scenePhase`. Si existe SRAM modificada sin flanco de deshabilitación y el proceso termina bajo presión de memoria antes del sondeo de 60 s, esos cambios se pierden. | Observar `UIApplication.didReceiveMemoryWarningNotification`, solicitar un flush síncrono en el hilo de emulación y continuar. Añadir una prueba con SRAM modificada sin flanco dirty. |

## Criterios del hito

| Criterio | Verificado por el auditor (sí/no) | Resultado |
|---|---|---|
| `xcodebuild ... -destination 'generic/platform=iOS' build` → `BUILD SUCCEEDED`, sin warnings nuevos | no | El sandbox no permite crear DerivedData. La evidencia sin firma es plausible, pero antecede al commit final y no corresponde al comando literal. El proyecto y esquema sí son reconocidos por `xcodebuild -list`. |
| Sin `URLSession`, `NWConnection`, `Network` ni `http` | sí | PASS: grep reejecutado con salida vacía y código 1. Tampoco aparecen ATS, claves de red, paquetes SPM ni SDKs externos. |
| En iPhone: dmg-acid2 correcto y Pokémon Rojo llega al menú y responde | no | Pendiente de Joel. El código parece preparado para la prueba: dmg-acid2 se ve correctamente en la captura del simulador, existen controles multitáctiles y configuración para iPhone. El modo horizontal tampoco está probado todavía. |

## Notas

- Commit auditado: `7612751fbb38dfcb590d4c9646bb51ed64a7cf9a`; base `main`: `51d20763382f17570642790e66467e9211572ff7`. Árbol limpio.
- H1 queda corregido: el sondeo de 60 s compara la SRAM aunque no exista flanco de `gb_sram_dirty()`. La evidencia T1 concuerda con el código.
- H6 queda corregido: el flush síncrono drena la cola y compara con `confirmed`, actualizado únicamente tras una escritura exitosa. La evidencia de fallo asíncrono seguido de background es coherente.
- H4 y H5 permanecen corregidos: no hay recursión en `CoreError.description` y los controles verticales usan alfa `1.0`.
- Los binarios existentes normal y ASan pasaron: `1080 comprobaciones, 0 fallos`. No pudieron reconstruirse debido al sandbox de solo lectura; esto no es un hallazgo del proyecto.
- `make -C core check-globals` informó `Sin estado global mutable: OK`. M4 no modifica `core/`, `gb_state_load`, opcodes ni temporización; no hay cambios nuevos que contrastar con Pan Docs.
- `git log --all --stat`, nombres de objetos y búsqueda de cabeceras Nintendo/PGBS no detectaron ROMs, boot ROMs, partidas ni estados. No encontré indicios de código GPL/AGPL copiado.
- `AtomicFile` mantiene el `.sav` vigente hasta el `rename(2)` final, hace `fsync` y crea backups. Las correcciones H1/H6 ya alcanzan esa ruta.
- `git diff --check` solo señala una línea vacía adicional al final de `docs/auditorias/M4-codex.md`; no afecta al producto.
