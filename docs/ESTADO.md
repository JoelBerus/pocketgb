# Estado del proyecto

> Fuente de verdad del estado para cualquier sesión (Mac o nube). Actualizar al cerrar cada hito.

**Actualizado:** 2026-09-28 · **Hito actual:** M0 completo → **siguiente: M1** (☁️ se puede hacer en la nube).

## Hecho
- M0: paquete de instrucciones (AGENTS.md, docs 00–09, hitos M0–M9, checklist de auditoría, contrato `core/include/pocketgb.h`, Makefile, descarga verificada de las ROMs de prueba v7.0, hook anti-ROMs).

## Siguiente paso exacto
1. `git checkout -b m1-cpu`
2. `tools/fetch-test-roms.sh`
3. Implementar [M1](hitos/M1-cpu.md): `core/src/*`, `core/tests/runner.c`, `core/tests/run_suite.py`, `core/tests/suite.txt`.
4. Verificar con `make -C core test HITO=M1 && make -C core asan HITO=M1 && make -C core check-globals`.
5. Auditoría (Codex en el Mac; subagente Opus en la nube) → `docs/auditorias/M1-*.md` → PR a `main`.

## Decisiones tomadas (no reabrir sin Joel)
| Fecha | Decisión |
|---|---|
| 2026-09-28 | Núcleo propio en C11. SameBoy solo como oráculo dev-only. |
| 2026-09-28 | Claude (Opus) desarrolla; Codex solo audita en solo lectura; si Codex no está, audita un subagente Opus. |
| 2026-09-28 | iOS nativo (SwiftUI + Metal), Android nativo futuro (Kotlin + NDK). Sin frameworks híbridos. |
| 2026-09-28 | Firma con Apple ID gratuito (reinstalar cada 7 días). |
| 2026-09-28 | Biblioteca en una carpeta privada de iCloud Drive vía document picker + bookmark (sin capability iCloud). ROMs solo de cartuchos propios; nunca en GitHub. |
| 2026-09-28 | Controles en horizontal superpuestos y translúcidos (0.30 en reposo / 0.60 pulsado, configurable). |

## Pendiente de Joel (manual)
- [ ] Crear el proyecto Xcode cuando lleguemos a M4 ([04](04-ios-spec.md) §Proyecto Xcode).
- [ ] Activar el Modo Desarrollador en el iPhone ([07](07-instalacion-iphone.md)).
- [ ] Volcar los cartuchos de Rojo y Amarillo ([08](08-roms-legal.md)).
