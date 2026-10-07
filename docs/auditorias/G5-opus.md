# Auditoría G5 (APU GBA) · Opus de respaldo
Commit auditado: `b62eea1`. Ejecutado en una copia propia.

## Veredicto: APROBAR CON CAMBIOS

## Hallazgos
| ID | Severidad | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|---|---|---|---|---|---|
| H1 | media | suite.txt, homebrew/audio.c | Las pruebas de salida solo cubren el canal 2 y DirectSound A al 100 %; sin cubrir canal 3 (bancos, 64 muestras, 75 %), ruido, DS B con timer 1, paneo; el runner solo mide el canal izquierdo. | Lectura. | Más variantes y medir los dos lados. |
| H2 | baja | apu.c | `sample_rate` sin acotar: por encima del reloj la fase da la vuelta y la contabilidad se rompe (sin accesos fuera de rango). | Arnés: rate=20 000 000. | Acotar en `gba_load_rom`. |
| H3 | baja (no verificado) | apu.c | El banco que suena se fija al disparar; GBATEK sugiere efecto inmediato del bit 6. | Lectura. | Decidir con GBATEK/oráculo. |
| H4 | baja | hle.c RegisterRamReset | El bucle escribe ceros en las FIFO (0xA0–0xA6). | Lectura. | Parar en 0xA0 y vaciar las FIFO. |
| H5 | baja | io.c / apu.c | `pending` se suma antes de los timers: la muestra de la FIFO cambia unos ciclos tarde. | Lectura. | Sumar después. |
| H6 | baja | G-README §G5 | Menciona un save state que no se implementa en G5. | Lectura. | Moverlo a G6. |

## Criterios del hito
| Criterio | Verificado | Resultado |
|---|---|---|
| Tests unitarios de FIFO y APU | sí | PASS |
| PSG 439,8 Hz y DS 1024 Hz | sí | 439,8 / 1025,0 Hz |
| Proporción DS/PSG igual a mGBA | sí | 4,02 frente a 4,04 |
| test / asan G5 | sí | 77/77 y 77/77 |
| Reglas 1–4 | sí | Sin binarios; adaptación propia de core/src/apu.c; índices acotados; estados hostiles limpios con ASan+UBSan; sin globals mutables; WAV deterministas. |
