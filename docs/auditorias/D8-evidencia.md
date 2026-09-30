# D8 · Evidencia (regresión, documentación y auditoría)

D8 no añade funcionalidad. Rama `d2-a1-wip` (D2–D7 + decisiones de Joel), frente a `main` desde `c384a9d`.

## Criterios CI (D-README §10) y cómo se verificaron
| Criterio | Verificación |
|---|---|
| Catálogo completo verde | Run del runner propio sobre `7f0fd0f`: `resultado: success`, 84 capturas (`ci-shots/d2-a1-wip/SUMMARY.md`) |
| `SUMMARY.md` sin errores ni warnings propios | "Errores y warnings del proyecto: (ninguno)"; Release `BUILD SUCCEEDED` |
| Núcleo sin regresiones | `make -C core test` en Linux (2026-09-30): "OK: todos los casos requeridos en PASS" (157/157 requeridos); también en el CI |
| AtomicFile, biblioteca, controles, save states y accesibilidad verdes | `✔ Test run with 93 tests in 11 suites passed` + UI (`ScreenshotTests`, `ShellTests`, `ShellFolderPickerTests`, `ShellLibraryTests`, `ShellAccessibilityTests`) |
| Revisión visual registrada | `docs/diseno/VERIFICACION.md` § "Registro de revisión visual final" |
| Sin ROMs, `.sav`, `.state` ni capturas comerciales en el repo | `git log --all --stat` sin `.sav`/`.state`/`.gb`/`.gbc` fuera de `core/tests` (fixtures libres descargados, no versionados); las capturas de demostración son arte generado |
| Sin red, GBA, cheats ni vidrio simulado | `rg 'URLSession\|NWConnection\|NSAppTransportSecurity\|\.blur\(\|UIBlurEffect\|Material\.\|\.gba\|cheat' ios/PocketGB`: sin coincidencias |
| M6/M7 sustituidos por D2–D6 | `docs/hitos/README.md` |
| Auditoría independiente | Codex D2–D5 (`15c3b49`): RECHAZAR → H1–H3 corregidos ([D2-D5-respuesta.md](D2-D5-respuesta.md)). **Pendiente:** auditoría Codex final de D6–D8 y de las correcciones, en el Mac |
| `ESTADO.md` refleja el resultado real | Actualizado con este cierre |

## Evidencia por hito
[D2](D2-evidencia.md) · [D3](D3-evidencia.md) · [D4](D4-evidencia.md) · [D5](D5-evidencia.md) · [D6](D6-evidencia.md) · [D7](D7-evidencia.md)

## Decisiones de Joel durante D3–D6
- Auditoría Codex al final y pruebas del iPhone al terminar los cambios visuales.
- El botón de menú pausa y abre las opciones (sin HUD desplegable).
- Cruceta Game Boy o flechas separadas; tamaño por control en el editor.
- "Continuar" exacto (estado automático al abrir): en otra rama de Joel.

## Validación de Joel
- 2026-09-30: funciones de D2–D6 probadas en el iPhone, correctas.
- Pendiente (D-README §10): accesibilidad real (VoiceOver, AX1–AX5, Reduce Transparency/Motion del sistema) y aprobación del merge.
