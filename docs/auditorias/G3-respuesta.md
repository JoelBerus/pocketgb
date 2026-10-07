# Respuesta a la auditoría Opus de G3

| ID | Corrección |
|---|---|
| G3-A1 | Afines y bitmaps restan `k·PB`/`k·PD` (k = línea mod mosaico vertical) a la referencia. Escena 12 nueva (modo 1, afín rotado con mosaico 3×4): idéntica a mGBA. Escena 13 (modo 3 con mosaico): idéntica sin rotación; con rotación mGBA usa otro muestreo que no coincide ni con su propio afín, así que se documenta como no verificado. |
| G3-A2 | El bit 0 solo se borra en 2D. La escena 12 incluye objetos de 256 colores con tesela impar en 1D: idéntica a mGBA. |
| G3-A3 | El avance de las referencias usa aritmética sin signo; la spec anota el `sext28` al restaurar estados en G6. |
| G3-A4 | Documentado en la spec (se compara desde el frame 60). |

```
$ make -C gba test HITO=G3   → 68/68 sin fallos requeridos
$ make -C gba asan HITO=G3   → 68/68 sin fallos requeridos
```
