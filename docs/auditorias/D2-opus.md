# D2 · Auditoría (subagente Opus de respaldo, sin historial del desarrollo)

Dos rondas, con `docs/auditorias/PROMPT.md`. Codex no está disponible en la nube. Resumen fiel de los informes del auditor.

## Ronda 1 · commit `614792d` · Veredicto: RECHAZAR

Bloqueante único (H1), el resto del hito bien hecho: AtomicFile cumple docs/04 y la inyección de fallos no llega a producción; SaveResolution es pura y está probada; restaurar respalda antes la actual y no puede coincidir con una sesión viva; sin carreras en la cola de guardado; CI verde, sin warnings, y las 14 capturas son correctas.

| ID | Severidad | Problema | Corrección sugerida |
|---|---|---|---|
| H1 | **bloqueante (regla 6)** | Un `.sav` junto al ROM que solo está en iCloud (placeholder `.<rom>.sav.icloud` o sin datos) se tomaba por ausente. Con partida local, se sobrescribía sin leerlo ni respaldarlo. Sin partida local (p. ej. tras reinstalar), el juego empezaba de cero y el primer guardado pisaba la única copia. | Detectar el placeholder y `.notDownloaded`, y no tocar el espejo. Mejor: descargarlo y leerlo antes de abrir, fuera del hilo principal. Añadir un test con `.juego.sav.icloud`. |
| H2 | media | `mirror.read()` hacía una lectura coordinada en el hilo principal: bloquea la UI si iCloud descarga. | Leerlo con el ROM en `Task.detached`. |
| H3 | media | Local con tamaño incorrecto y espejo válido: se instalaba el espejo sin avisar y la local acababa saliendo de la rotación. | Apartar la local fuera de la rotación y avisar. |
| H4 | media | Un espejo que se lee pero no se puede escribir se añadía como `.1` en cada apertura: el historial de backups acababa sustituido por copias idénticas. | No añadir un backup cuyo contenido ya esté en `.1`–`.5`. |
| H5 | media | `Juego.gb` y `Juego.gbc` compartían `Juego.sav`. | Detectar la colisión y desactivar el espejo. |
| H6 | baja | Una carpeta no disponible al arrancar no se recuperaba al volver a primer plano. | `restore()` en foreground y botón "Reintentar". |
| H7 | baja | Un aviso de progreso tardío podía dejar colgada la fila "Buscando juegos…". | Guardar la generación terminada. |
| H8 | baja | El título "Partida con tamaño inesperado" salía para cualquier aviso; la captura usaba un texto distinto al de la app. | Avisos tipados. |
| H9 | baja | Los criterios de `.sav` incorrecto y de conflicto solo se probaban sobre la función pura. | Tests de integración con E/S real. |
| H10 | baja | `failAfterStep` se compilaba en Release (sin riesgo real). | `#if DEBUG`. |
| H11 | baja | `.downloaded` se mostraba como "solo en iCloud". | Tratarlo como jugable. |
| H12 | baja | Con dos dispositivos a la vez, gana el último en escribir el espejo. | Aceptable: la local manda; documentar. |
| H13 | baja | `readROM`: TOCTOU entre el tamaño y la lectura completa. | `read(upToCount: max + 1)`. |

## Ronda 2 · commit `2f01e83` · Veredicto: APROBAR CON CAMBIOS

H1 resuelto:
- Con el espejo en iCloud sin descargar y sin partida local, el juego no se abre.
- Con partida local, el espejo queda fuera toda la sesión y se avisa.

Se recorrieron todas las ramas de `SaveOpening.prepare`: ninguna sobrescribe sin backup ni escribe un `.sav` de tamaño incorrecto. H2–H11 y H13, resueltos; H12, aceptado.

| ID | Severidad | Problema | Corrección sugerida |
|---|---|---|---|
| N1 | media | Con `-demoFolderState stale`, `enterForeground()` llamaba a `restore()` y, sin bookmark en el simulador, la fase pasaba a "sin carpeta": captura y UI test rotos. | No pasar de `.unavailable` a `.noFolder` sin bookmark; en DEBUG con demo, no reescanear. |
| N2 | baja | Con placeholder, `snapshot()` pide la descarga pero lee en el acto: sale `.unavailable` casi siempre al primer intento. | Esperar un poco a la descarga. |
| N3 | baja | Nombre de cuarentena con resolución de 1 s. | Sufijo único. |
| N4 | baja (informativo) | Liberar `audioScratch` antes de relanzar es correcto, pero frágil. | Comentario. |
| N5 | baja | La colisión de espejos distinguía mayúsculas. | Comparar en minúsculas. |
