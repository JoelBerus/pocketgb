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
| [M9 · Cable virtual](M9-link-virtual.md) | ☁️ núcleo · 🍎 UI | ☁️ núcleo cerrado 2026-09-29 (auditorías Opus y Codex; fuzz-link 600 s); 🍎 UI implementada y auditada (Opus, respondida; [evidencia](../auditorias/M9-ios-evidencia.md), [respuesta](../auditorias/M9-ios-respuesta.md)), fusionada en `cierre-integracion` (`ad446d9`); 🍎 pendiente: prueba del intercambio Rojo ↔ Amarillo en el iPhone |

**Hitos de diseño D1–D8:** fusionados en `main` el 2026-09-30. **D8.1** es el lote correctivo posterior (continuación exacta, alias, identidad y ajustes visuales): ✅ implementado y auditado (Opus en dos vueltas, [respuesta](../auditorias/D8.1-respuesta.md)), fusionado en `cierre-integracion` (`4d085c9`); 🍎 pendiente: prueba I1 en el iPhone. App basada en la propuesta Liquid Glass de Joel: ☁️ nube + CI de macOS con capturas · 🍎 validación final en el iPhone. Plan: [D-README](D-README.md) · Spec: [../diseno/SPEC.md](../diseno/SPEC.md).

**Game Boy Advance (G0–G9)**: plan en [G-README](G-README.md), spec en [10-gba-spec](../10-gba-spec.md).

| Hito | Dónde | Estado |
|---|---|---|
| G0 · Instrucciones y andamiaje | ☁️ | ✅ cerrado 2026-10-05 (auditoría Opus: APROBAR CON CAMBIOS → corregido) |
| G1 · CPU ARM7TDMI | ☁️ | ✅ cerrado 2026-10-05 (SingleStepTests 100 %, arm/thumb/memory.gba PASS; auditoría Opus: APROBAR CON CAMBIOS → corregido) |
| G2 · Bus, DMA, timers, IRQ, HLE | ☁️ | ✅ cerrado 2026-10-05 (bios/nes/memory.gba PASS; Opus: RECHAZAR → APROBAR CON CAMBIOS → corregido) |
| G3 · PPU | ☁️ | ✅ cerrado 2026-10-05 (12/14 escenas idénticas a mGBA; Opus: APROBAR CON CAMBIOS → corregido) |
| G4 · Cartucho y saves | ☁️ | ✅ cerrado 2026-10-05 (SRAM, Flash, EEPROM, RTC; Opus: APROBAR CON CAMBIOS → corregido) |
| G5 · APU | ☁️ | ✅ cerrado 2026-10-05 (núcleo; Opus: APROBAR CON CAMBIOS → corregido); 🍎 escucha en G8 |
| G6 · Save states y determinismo | ☁️ | ✅ cerrado 2026-10-05 (estados validados, determinismo, fuzzers; auditoría de núcleo Opus: APROBAR CON CAMBIOS → corregido) |
| G7 · App: biblioteca y sesión | ☁️ + CI macOS | ✅ implementado y auditado 2026-10-05 (Codex: RECHAZAR → G7-4 corregido; G7-1 y G7-2 descartados con evidencia, G7-3 documentado); pendiente de Joel: aprobar el descarte de G7-1 y confirmar el RTC dentro del `.sav` (G7-3) |
| G8 · App: pantalla, controles y audio | ☁️ + CI macOS · 🍎 | ✅ implementado y auditado (Opus: APROBAR CON CAMBIOS → corregido, H11 en G9); 🍎 pendiente de Joel: 60 fps, Kirby ≥ 30 min con cierre forzado, audio y L/R |
| G9 · Cierre | ☁️ + 🍎 | ✅ ☁️ regresión, documentación y auditoría final Opus ([G9-evidencia](../auditorias/G9-evidencia.md), [G9-opus](../auditorias/G9-opus.md)); fusionado en `cierre-integracion` (`94b53fa`); faltan la prueba de Joel (Kirby) y la PR a `main` con su aprobación |

**Hitos Android A1–A8** (app nativa Kotlin/Compose, plan en [../diseno-android/SPEC.md](../diseno-android/SPEC.md) §7):

| Hito | Estado |
|---|---|
| A1 · Fundamentos | ✅ cerrado ([evidencia](../auditorias/A1-android-evidencia.md)) |
| A2 · Core y vídeo | ✅ cerrado ([evidencia](../auditorias/A2-android-evidencia.md)) |
| A3 · Audio, input y ciclo de vida | ✅ cerrado, probado por Joel ([evidencia](../auditorias/A3-android-evidencia.md)) |
| A4 · Biblioteca | ✅ cerrado, probado por Joel ([evidencia](../auditorias/A4-android-evidencia.md)) |
| A5 · Partidas y estados | ✅ implementado y auditado en 7 vueltas; prueba manual de Joel pendiente ([PRUEBAS-JOEL](../PRUEBAS-JOEL.md)) ([evidencia](../auditorias/A5-android-evidencia.md)) |
| A6 · Paridad visual | ✅ cerrado y auditado (Opus y DeepSeek, respuesta A6-R; kill-test 50/50) ([evidencia](../auditorias/A6-android-evidencia.md)) |
| A7 · Robustez | ✅ implementado y auditado (Opus + DeepSeek, respondido; [evidencia](../auditorias/A7-android-evidencia.md), [respuesta](../auditorias/A7-android-respuesta.md)); 🍎 pendiente: mando, TalkBack, rotación y memoria baja en el teléfono |
| A8 · Cierre | ✅ cerrado (docs, icono, 1.0.0, literales extraídos a `strings.xml`; auditoría conjunta [CIERRE-opus](../auditorias/CIERRE-opus.md); [evidencia](../auditorias/A8-android-evidencia.md)); 🍎 pendiente: pruebas reales de Joel ([PRUEBAS-JOEL](../PRUEBAS-JOEL.md)) |

☁️ = Claude en la nube (Linux: clang, make, python3). 🍎 = requiere macOS + Xcode + iPhone.
Orden recomendado si se trabaja en la nube: M1 → M2 → M3 → (núcleo de M5) → (núcleo de M8), mientras M4/M6/M7 esperan a una sesión en el Mac.

**Siguiente nivel (A9 y N1–N9, 2026-10-07)**: plan en [N-README](N-README.md) (auditado: [Opus](../auditorias/N0-plan-opus.md), [DeepSeek](../auditorias/N0-plan-deepseek.md), [respuesta](../auditorias/N0-plan-respuesta.md)). Rama de integración `siguiente-nivel`.

| Hito | Estado |
|---|---|
| A9 · Paridad Android (renombrar, continuación exacta) | ✅ fusionado en `siguiente-nivel` (Opus APROBAR CON CAMBIOS, respondida) |
| N1 · Identidad y carpetas | ✅ iOS y Android fusionados (Opus: 2 vueltas cada uno, respondidas) |
| N2 · Controles | ✅ iOS y Android fusionados (Opus APROBAR CON CAMBIOS, respondidas; reglas comunes) |
| N3 · Biblioteca y detalle adaptables | Android ✅ fusionado (Opus, respondida); iOS respondido, pendiente de verificación (entorno bloqueado) |
| N4 · Categorías, etiquetas e inicio | ⏳ Android en implementación |
| N5 · Portadas | pendiente |
| N6 · Momentos y progreso | parcial: lector Pokémon en C fusionado (Opus, respondida); app pendiente |
| N7 · Partidas que viajan | parcial: contenedor `.pgbm` en C con META v1 fusionado (Opus, respondida) |
| N8 · GBA en Android | parcial: capa nativa fusionada (Opus, respondida); Kotlin pendiente (tras N4 Android) |
| N9 · Carpetas de Joel, guía y cierre | pendiente |
