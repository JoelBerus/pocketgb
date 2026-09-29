# Hitos

Se trabajan **en orden** y de a uno. Cada hito se hace en una rama `mN-<nombre>`, sigue el flujo de [auditorias/README.md](../auditorias/README.md) y se cierra actualizando [ESTADO.md](../ESTADO.md).

| Hito | Dónde se puede hacer | Estado |
|---|---|---|
| [M0 · Instrucciones](M0-instrucciones.md) | cualquiera | ✅ cerrado 2026-09-28 |
| [M1 · CPU + MMU + timer](M1-cpu.md) | ☁️ nube o Mac | ✅ cerrado 2026-09-29 (auditoría Opus: APROBAR CON CAMBIOS) |
| [M2 · PPU DMG](M2-ppu-dmg.md) | ☁️ nube o Mac | ✅ cerrado 2026-09-29 (auditoría Opus: APROBAR CON CAMBIOS) |
| [M3 · MBC + SRAM + fuzzing](M3-mbc-fuzz.md) | ☁️ nube o Mac | ✅ cerrado 2026-09-29 (auditoría Opus: RECHAZAR → APROBAR CON CAMBIOS) |
| [M4 · App iOS mínima](M4-ios-minima.md) | 🍎 solo Mac (Xcode) | ✅ cerrado 2026-09-29 (Codex, 4 vueltas: RECHAZAR ×4 → H1–H7 corregidos; probado en el iPhone de Joel) |
| [M5 · APU + audio](M5-apu-audio.md) | ☁️ núcleo · 🍎 integración iOS | 🟡 núcleo cerrado; iOS implementado 2026-09-29 (10 min sin underruns en simulador), faltan criterios 3–4 en el iPhone |
| [M6 · Biblioteca + saves](M6-biblioteca-saves.md) | — | ➡️ sustituido por D2 + D3 ([D-README](D-README.md)) |
| [M7 · Controles + extras](M7-controles.md) | — | ➡️ sustituido por D4 + D5 + D6 ([D-README](D-README.md)) |
| [M8 · CGB](M8-cgb.md) | ☁️ núcleo · 🍎 prueba en iPhone | |
| [M9 · Cable virtual](M9-link-virtual.md) | ☁️ núcleo · 🍎 UI | |

**Hitos de diseño D1–D8** (app con la propuesta Liquid Glass de Joel): ☁️ nube + CI de macOS con capturas · 🍎 validación final en el iPhone. Plan: [D-README](D-README.md) · Spec: [../diseno/SPEC.md](../diseno/SPEC.md).

☁️ = Claude en la nube (Linux: clang, make, python3). 🍎 = requiere macOS + Xcode + iPhone.
Orden recomendado si se trabaja en la nube: M1 → M2 → M3 → (núcleo de M5) → (núcleo de M8), mientras M4/M6/M7 esperan a una sesión en el Mac.
