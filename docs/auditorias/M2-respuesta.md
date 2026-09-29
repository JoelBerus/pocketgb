# M2 · Respuesta a la auditoría (docs/auditorias/M2-opus.md)

Veredicto recibido: **APROBAR CON CAMBIOS** (1 media, 6 bajas; ninguna bloqueante). Todo se corrigió en el commit de cierre de M2 (el que contiene este archivo).

| ID | Decisión | Qué se hizo |
|---|---|---|
| H1 | corregido | `oam_scan` guarda la altura usada (`ppu.obj_height`) y `render_line` descarta el objeto si `row >= height`. La máscara `& 0x1FFF` se mantiene como defensa. |
| H2 | corregido | Comentario arreglado. Nuevos casos en `unit_ppu.c`: prioridad BG (objeto bajo fondo ≠ 0 y visible sobre color 0), flip X y límite de 10 objetos por línea. |
| H3 | corregido | `ppu_vram_blocked`/`ppu_oam_blocked` pasan a `static inline` en `internal.h` y `bus_blocked` (cpu.c) las usa: el test verifica el código que ejecuta la CPU. |
| H4 | corregido | Líneas duplicadas quitadas de `suite.txt`, con un comentario que dice que esos casos se ejecutan desde el bloque M1. |
| H5 | corregido | `--dump` fallido → salida 2 (`ERROR`). |
| H6 | corregido | `png2rgba.py` exige IHDR y solo acepta profundidad 8, o 1/2/4 en gris/paleta; el resto se rechaza con mensaje. |
| H7 | corregido | Punto añadido en `docs/03-core-spec.md`. |

Nota del auditor sobre el orden R/B: queda anotada en `docs/ESTADO.md` para M8 (cgb-acid2 lo cubrirá con colores).

Verificación tras las correcciones:
```
make -C core test HITO=M2   → 70/80 PASS · requeridos: 70/70 PASS · OK
make -C core asan HITO=M2   → 70/80 PASS · requeridos: 70/70 PASS · OK
make -C core check-globals  → Sin estado global mutable: OK
gbtest --unit               → PASS: 154 comprobaciones, 0 fallos
bench: dmg-acid2 47.8x, cpu_instrs 44.7x tiempo real
```
