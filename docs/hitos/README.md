# Hitos

Se trabajan **en orden** y de a uno. Cada hito se hace en una rama `mN-<nombre>`, sigue el flujo de [auditorias/README.md](../auditorias/README.md) y se cierra actualizando [ESTADO.md](../ESTADO.md).

| Hito | Dónde se puede hacer | Estado |
|---|---|---|
| [M0 · Instrucciones](M0-instrucciones.md) | cualquiera | ✅ cerrado 2026-09-28 |
| [M1 · CPU + MMU + timer](M1-cpu.md) | ☁️ nube o Mac | ✅ cerrado 2026-09-29 (auditoría Opus: APROBAR CON CAMBIOS) |
| [M2 · PPU DMG](M2-ppu-dmg.md) | ☁️ nube o Mac | ✅ cerrado 2026-09-29 (auditoría Opus: APROBAR CON CAMBIOS) |
| [M3 · MBC + SRAM + fuzzing](M3-mbc-fuzz.md) | ☁️ nube o Mac | ✅ cerrado 2026-09-29 (auditoría Opus: RECHAZAR → APROBAR CON CAMBIOS) |
| [M4 · App iOS mínima](M4-ios-minima.md) | 🍎 solo Mac (Xcode) | ✅ cerrado 2026-09-29 (Codex, 4 vueltas: RECHAZAR ×4 → H1–H7 corregidos; probado en el iPhone de Joel) |
| [M5 · APU + audio](M5-apu-audio.md) | ☁️ núcleo · 🍎 integración iOS | ✅ cerrado 2026-09-29 (núcleo: auditoría Opus; iOS: Codex implementa, Opus audita, Joel lo oye en su iPhone) |
| [M6 · Biblioteca + saves](M6-biblioteca-saves.md) | — | ➡️ sustituido por D2 + D3 ([D-README](D-README.md)) |
| [M7 · Controles + extras](M7-controles.md) | — | ➡️ sustituido por D4 + D5 + D6 ([D-README](D-README.md)) |
| [M8 · CGB](M8-cgb.md) | ☁️ núcleo · 🍎 prueba en iPhone | ✅ cerrado 2026-09-29 (auditoría Opus: APROBAR CON CAMBIOS → H1–H5 corregidos; Amarillo en color en el iPhone; paleta de Rojo → D5/D6) |
| [M9 · Cable virtual](M9-link-virtual.md) | ☁️ núcleo · 🍎 UI | ☁️ núcleo cerrado 2026-09-29 (auditorías Opus y Codex; fuzz-link 600 s); 🍎 falta UI e intercambio en el iPhone |

**Hitos de diseño D1–D8** (D1 ✅ cerrado 2026-09-29; D2–D7 ☁️ implementados en `d2-a1-wip`, CI verde y probados por Joel en el iPhone (D2–D6); D8 ☁️ evidencia consolidada, falta la auditoría Codex final y el merge) (app con la propuesta Liquid Glass de Joel): ☁️ nube + CI de macOS con capturas · 🍎 validación final en el iPhone. Plan: [D-README](D-README.md) · Spec: [../diseno/SPEC.md](../diseno/SPEC.md).

**Hitos Android A1–A8** (app nativa Kotlin/Compose, plan en [../diseno-android/SPEC.md](../diseno-android/SPEC.md) §7):

| Hito | Estado |
|---|---|
| A1 · Fundamentos | ✅ cerrado ([evidencia](../auditorias/A1-android-evidencia.md)) |
| A2 · Core y vídeo | ✅ cerrado ([evidencia](../auditorias/A2-android-evidencia.md)) |
| A3 · Audio, input y ciclo de vida | ✅ cerrado, probado por Joel ([evidencia](../auditorias/A3-android-evidencia.md)) |
| A4 · Biblioteca | ✅ cerrado, probado por Joel ([evidencia](../auditorias/A4-android-evidencia.md)) |
| A5 · Partidas y estados | ✅ implementado y auditado en 7 vueltas; pendiente la prueba manual de Joel ([evidencia](../auditorias/A5-android-evidencia.md)) |
| A6 · Paridad visual | ✅ cerrado y auditado (Opus y DeepSeek, respuesta A6-R; kill-test 50/50) ([evidencia](../auditorias/A6-android-evidencia.md)) |
| A7 · Robustez | 🟡 implementado (L1 a L4), pendiente de auditoría y de pruebas reales ([evidencia](../auditorias/A7-android-evidencia.md)) |
| A8 · Cierre | ⏳ pendiente (regresión, Release, icono, prueba real y auditoría conjunta; cerrar A5V6-H1, A5V6-H3 y A5V7-H1 si siguen abiertos) |

☁️ = Claude en la nube (Linux: clang, make, python3). 🍎 = requiere macOS + Xcode + iPhone.
Orden recomendado si se trabaja en la nube: M1 → M2 → M3 → (núcleo de M5) → (núcleo de M8), mientras M4/M6/M7 esperan a una sesión en el Mac.
