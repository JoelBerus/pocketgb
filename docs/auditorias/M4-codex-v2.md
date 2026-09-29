## Veredicto: RECHAZAR

## Hallazgos

| ID | Severidad (bloqueante/alta/media/baja) | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|---|---|---|---|---|---|
| H1 | bloqueante | [EmulatorSession.swift:154](/Users/joelbermudez/Documents/workspace/pocketgb/ios/PocketGB/Emulator/EmulatorSession.swift:154), [cart.c:222](/Users/joelbermudez/Documents/workspace/pocketgb/core/src/cart.c:222) | La corrección no implementa realmente la red de seguridad cada 60 s cuando el juego escribe SRAM sin deshabilitarla. | `dirtySinceFirst` solo se inicia cuando `core.sramDirty` es verdadero, pero el núcleo solo activa esa señal al deshabilitar RAM. Si el juego escribe y la mantiene habilitada, la línea 163 retorna indefinidamente. La propia evidencia §4 confirma que, sin el flanco, no se guarda periódicamente. Un cierre abrupto puede perder toda la sesión. | Comparar la SRAM con el último contenido confirmado cada 60 s independientemente de `gb_sram_dirty()`, o exponer desde el núcleo una señal de escrituras pendientes diferente del flanco de guardado. Añadir una prueba que mantenga RAM habilitada más de 60 s. |
| H6 | bloqueante | [EmulatorSession.swift:107](/Users/joelbermudez/Documents/workspace/pocketgb/ios/PocketGB/Emulator/EmulatorSession.swift:107), [EmulatorSession.swift:186](/Users/joelbermudez/Documents/workspace/pocketgb/ios/PocketGB/Emulator/EmulatorSession.swift:186) | Un fallo de escritura asíncrono seguido de pausa o salida puede omitir el reintento y perder la partida. | `lastSaved` se actualiza antes de que `saves.save(data)` termine. Si esa escritura falla y se solicita pausa/salida antes del siguiente ciclo, las ramas de líneas 107/121 se procesan antes de `saveFailed`. El flush síncrono ve `data == lastSaved`, espera la cola y retorna; si el fallo se produjo durante esa espera, la sesión queda aparcada o termina sin reintentar. Esto contradice la afirmación de `M4-respuesta.md:5`. | Separar contenido encolado de contenido confirmado. Después de drenar la cola en un flush síncrono, comprobar el resultado y reintentar antes de aparcar/terminar. Cubrir mediante fallo inyectado seguido inmediatamente de `pause()` y `stop()`. |
| H2 | bloqueante | [M4-evidencia.md:56](/Users/joelbermudez/Documents/workspace/pocketgb/docs/auditorias/M4-evidencia.md:56) | Continúa pendiente el criterio obligatorio en el iPhone. | No hay confirmación de dmg-acid2, Pokémon Rojo hasta el menú ni funcionamiento de los botones en hardware. La captura del simulador es coherente, pero no sustituye el criterio. | Joel debe ejecutar y documentar las pruebas en el iPhone. |
| H3 | bloqueante | [M4-evidencia.md:18](/Users/joelbermudez/Documents/workspace/pocketgb/docs/auditorias/M4-evidencia.md:18) | Sigue sin demostrarse el comando literal del criterio de compilación. | El build con `CODE_SIGNING_ALLOWED=NO` termina correctamente; el intento firmado falla por no haber un dispositivo registrado ni perfil. | Registrar el iPhone, generar el perfil y ejecutar literalmente el comando de [M4-ios-minima.md:17](/Users/joelbermudez/Documents/workspace/pocketgb/docs/hitos/M4-ios-minima.md:17). |

## Criterios del hito

| Criterio | Verificado por el auditor (sí/no) | Resultado |
|---|---|---|
| `xcodebuild ... build` → `BUILD SUCCEEDED`, sin warnings nuevos | no | La evidencia sin firma es plausible, pero no corresponde al comando literal. La propia evidencia registra que el build firmado falla por falta del iPhone/perfil. El sandbox de solo lectura no permite reconstruir; eso no constituye un hallazgo del proyecto. |
| Sin `URLSession`, `NWConnection`, `Network` ni `http` | sí | PASS: grep reejecutado, código 1 sin coincidencias. Tampoco aparecen ATS, claves de red, dependencias SPM ni frameworks de terceros. |
| En iPhone: dmg-acid2 correcto y Pokémon Rojo llega al menú y responde | no | Pendiente de Joel. La captura disponible corresponde al simulador. |

## Notas

- Commit auditado: `f5acc6fb61b8a14b9c03ddd1a98a66264c5ef424`; base `main`: `51d20763382f17570642790e66467e9211572ff7`.
- H4 está corregido: `CoreError.description` cubre explícitamente todos los casos y ya no interpola recursivamente `self`.
- H5 está corregido en código: los rellenos verticales usan alfa `1.0`.
- La evidencia §4 es plausible para el flush al pasar a background, la ausencia de rotación sin cambios y la creación del backup. No cubre H1 ni H6.
- Reejecuté los binarios existentes normal y ASan: ambos informaron `1080 comprobaciones, 0 fallos`. No fueron reconstruidos por las restricciones del sandbox.
- `Info.plist` y el esquema Xcode son válidos. `git diff --check` solo señaló espacios finales en `Shaders.swift` y una línea vacía final en el informe anterior.
- `git log --all --stat`, nombres rastreados y escaneo de objetos por cabecera Nintendo/PGBS no muestran ROMs, boot ROMs, partidas ni estados. No encontré indicios de código GPL/AGPL copiado.
- M4 no cambia `core/`; no hay nuevos índices derivados del ROM, cambios en `gb_state_load` ni comportamiento de CPU/PPU que contrastar con Pan Docs.
- Hay modificaciones locales no committeadas en `docs/ESTADO.md` y `docs/hitos/README.md`; quedaron fuera de `git diff main...HEAD`.
