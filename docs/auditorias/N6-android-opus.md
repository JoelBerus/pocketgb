# N6 Android · auditoría Opus (resumen fiel, transmitido por el coordinador)

**Veredicto: APROBAR CON CAMBIOS.**

| Id | Gravedad | Hallazgo |
|---|---|---|
| N6A-H1 | Media | `MomentStore.kt:166-174` `pushBeforeLoad` ordena el anillo por `createdMs = now()`. Si el reloj retrocede con 3 entradas posteriores, la nueva queda en `evicted` y se borra: se pierde la posición previa (la RAM queda en `.1`). |
| N6A-H2 | Baja | `indexForWriting`: al reconstruir un índice dañado se pierden `origin`/`originSha` → posible duplicado en la migración; el anillo reconstruido puede tener más de 3. |
| N6A-H3 | Baja | El kill-test JVM solo cubre crear + `installSram`, no `loadMoment` (anillo + escritura de la partida). |
| N6A-H4 | Baja | Restos sin uso: 2 cadenas, `StatesSheet`, la API antigua. |

**Criterios comprobados:** atomicidad, índice dañado, migración, exclusión por huella, JNI, ND12, ND13, retirada de `StatesConfirmTest` justificada; JVM nuevas 0 fallos; sin `INTERNET`; sin binarios. **Ninguna ruta pierde la RAM de la partida.**
