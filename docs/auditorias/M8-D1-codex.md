## Veredicto: APROBAR CON CAMBIOS

## Hallazgos

| ID | Severidad (bloqueante/alta/media/baja) | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|---|---|---|---|---|---|
| H1 | media | [core/src/cpu.c:19](/Users/joelbermudez/Documents/workspace/pocketgb/core/src/cpu.c:19), [docs/03-core-spec.md:63](/Users/joelbermudez/Documents/workspace/pocketgb/docs/03-core-spec.md:63) | El conflicto de bus durante OAM DMA agrupa ROM/SRAM y WRAM como un único bus también en CGB. En hardware CGB son buses separados. | `bus_blocked` solo distingue VRAM frente a “todo lo demás”: con DMA originado en WRAM bloquea erróneamente la ejecución desde ROM; con DMA desde ROM/SRAM bloquea erróneamente WRAM. [Pan Docs confirma que cartucho y WRAM usan buses separados en CGB](https://gbdev.io/pandocs/OAM_DMA_Transfer). Puede romper software CGB que aproveche esa característica. Los tests actuales no prueban explícitamente estas dos combinaciones. | Clasificar los buses como VRAM, cartucho y WRAM cuando `g->cgb.on`; conservar el comportamiento DMG existente. Añadir tests para DMA WRAM→OAM ejecutando desde ROM y DMA ROM→OAM accediendo a WRAM, tanto en CGB nativo como en compatibilidad. Corregir también la descripción de `docs/03-core-spec.md`. |
| H2 | baja | [docs/hitos/D-README.md:204](/Users/joelbermudez/Documents/workspace/pocketgb/docs/hitos/D-README.md:204), [docs/auditorias/D1-evidencia.md:35](/Users/joelbermudez/Documents/workspace/pocketgb/docs/auditorias/D1-evidencia.md:35) | El criterio y la evidencia afirman que existen doce capturas D1, pero el manifiesto actual define once. | La lista de D1 contiene `launch` oscuro y cinco pantallas light/dark: 11 PNG. La evidencia todavía describe `launch` light/dark en la línea 42, aunque en la línea 73 reconoce que quedó solo dark. Hay 15 capturas totales porque cuatro son heredadas de gameplay, no de D1. | Cambiar “doce” por “once” y actualizar la revisión visual de la evidencia para indicar únicamente `launch` dark. |

## Criterios del hito

| Criterio | Verificado por el auditor (sí/no) | Resultado |
|---|---|---|
| M8: `make -C core test HITO=M8` | sí | Reejecutado con el binario existente: 1167 comprobaciones unitarias, 157/157 casos requeridos PASS. `cgb-acid2` y `dmg-acid2-cgb` fueron idénticos a sus referencias. |
| M8: ASan/UBSan | sí | Suite ASan existente: 157/157 requeridos PASS, sin errores de AddressSanitizer ni UBSan. LeakSanitizer no está soportado por este sandbox; no es un defecto del proyecto. |
| M8: ausencia de regresiones DMG | sí | Todos los casos requeridos M1–M5 pasan. Los fallos observados corresponden a `known-fail` documentados. |
| M8: seguridad de ROM y estado v3 | sí | No encontré índices sin acotar ni lecturas fuera de rango. VRAM/WRAM, paletas y HDMA mantienen sus índices dentro de los arrays; `gb_state_load` valida CRC, modelo, tamaños, tags y rangos antes de aplicar. |
| M8: estado global mutable | sí | Inspección de fuentes: solo existen tablas `static const`; el estado mutable reside en `struct gb`. `nm` no pudo crear su caché temporal por el sandbox, por lo que no se cuenta como fallo del proyecto. |
| M8: Amarillo en color en iPhone | no | Solo consta la validación manual de Joel en la evidencia; no puede repetirse desde este sandbox. |
| M8: Rojo con paleta de compatibilidad | no | Sigue diferido explícitamente a D5/D6. El soporte del núcleo sí tiene tests unitarios. |
| D1: compila con Swift 6/iOS 26, Debug y Release | sí | Evidencia CI del commit `3ed4f76`: tests y builds Debug/Release exitosos, sin warnings. El código posterior hasta `main` solo cambia documentación/merge. |
| D1: shell de tres tabs, minimización y Settings con `Form` | sí | Verificado estáticamente y respaldado por `ShellTests`. |
| D1: assets light/dark de SPEC | sí | Existen los 12 color sets y sus tests pasan. |
| D1: sin vidrio simulado, GBA, L/R, cheats ni artwork remoto | sí | Sin coincidencias productivas de `.blur`, `UIBlurEffect`, `Material`, red o elementos fuera de alcance. |
| D1: capturas exigidas sin clipping | no | La evidencia visual del CI es plausible, pero el criterio numérico dice 12 y actualmente solo hay 11 capturas D1; ver H2. |
| D1: ID desconocido hace fallar el catálogo | sí | El router muestra `debug-unknown-screen` y `ScreenshotTests` lo rechaza. |
| D1: router y argumentos DEBUG excluidos de Release | sí | `DebugArguments`, `DebugScreenRouter`, launch preview y HUD están protegidos por `#if DEBUG`; el build Release del CI pasó. |
| D1: aislamiento Swift 6 de callbacks | sí | No encontré una regresión: el render block de audio se crea en un método `nonisolated`; las interrupciones saltan explícitamente a `MainActor`; los demás callbacks pasan por colas o APIs del actor principal. |
| D1: ruta de partidas intacta | sí | El diff no modifica `Saves/`, `Emulator/` ni `Audio/`. La SRAM continúa pasando por `SaveStore` y `AtomicFile` con escritura atómica y backups. |

## Notas

- Rango auditado: `d6905d7..main`, con `main` en `ca6bec5`.
- Revisé `git log --all --stat`, todos los nombres versionados y todos los blobs del historial buscando la firma de cabecera Game Boy. No hay ROMs, boot ROMs, `.sav` ni estados.
- No encontré código GPL/AGPL incorporado. Las tablas de SameBoy están identificadas como Expat/MIT y conservan el aviso completo.
- No hay APIs de red, ATS ni paquetes runtime en la app iOS.
- No encontré APIs iOS inventadas; además, las firmas usadas fueron aceptadas por Xcode 26.6 en CI.
- El sandbox impidió crear un directorio temporal para recompilar desde cero. Por ello reejecuté los binarios normal y ASan ya presentes y contrasté la compilación limpia con la evidencia CI. Esto no constituye un hallazgo del proyecto.
- No se modificó ningún archivo ni se creó ningún commit.
