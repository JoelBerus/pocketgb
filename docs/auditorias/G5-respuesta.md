# Respuesta a la auditoría Opus de G5

| ID | Corrección |
|---|---|
| H1 | Variantes nuevas de `audio.c`: canal 3 con 64 muestras (banco 0 alto, banco 1 bajo: solo con dos bancos hay onda, 128 Hz) al 75 % solo a la izquierda; ruido solo a la derecha; DirectSound B al 50 % con timer 1 a 16 384 Hz solo a la derecha (512 Hz). El modo audio mide el lado pedido (`audio:HZ:L|R`) y con `!` exige silencio en el otro. Las cinco variantes, contra mGBA: mismas frecuencias y mismo paneo (tabla abajo). |
| H2 | `sample_rate` se acota a 8000–192000 (0 = sin audio) en `gba_load_rom`; documentado en `pocketgba.h`. |
| H3 | El bit 6 de `SOUND3CNT_L` elige el banco que suena con efecto inmediato (GBATEK); la prueba de onda depende de los dos bancos y coincide con mGBA. |
| H4 | `RegisterRamReset` (bit 6) borra 0x60–0x9F y vacía las FIFO con `SOUNDCNT_H` bits 11 y 15. |
| H5 | Los ciclos pendientes de la APU se suman después de los timers. |
| H6 | G-README: el estado de la APU se serializa en G6. |

```
$ make -C gba test HITO=G5   → 81/81 sin fallos requeridos
$ make -C gba asan HITO=G5   → 81/81 sin fallos requeridos
variante  mGBA (pico L/R, Hz)                PocketGB (pico L/R, Hz)
psg       6187 / 6187, 439.9                 2638 / 2638, 439.8
ds        24875 / 24875, 1023.5              10653 / 10653, 1025.0
wave      5044 / 0, 128.0                    2338 / 0, 128.0
noise     0 / 7014 (aleatorio)               0 / 2801 (aleatorio)
dsb       0 / 12187, 512.0                   0 / 5508, 512.5
```
La escala absoluta es menor a propósito (× 20 para no saturar con los seis canales); las proporciones son próximas a mGBA.
