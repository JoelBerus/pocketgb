# Respuesta a las auditorías del plan N (Opus P1–P20, DeepSeek DS1–DS7)

Fecha: 2026-10-07. Plan resultante: [N-README v2](../hitos/N-README.md). Decisiones de Joel tomadas en la sesión (ND1–ND13).

| Hallazgo | Respuesta | Dónde |
|---|---|---|
| P1 (Android sin continuación exacta) | **Aceptado.** Joel ratifica que Android retome el AUTO como iOS (cambia J8). Nuevo lote A9 en la ola 1. | ND6, A9 |
| P2 (vida de los momentos ante cambios del núcleo) | **Aceptado, las dos medidas:** cada momento guarda la RAM del cartucho y la configuración, y toda subida de versión de estado debe cargar la anterior. | ND13, §3.3, N6 |
| P3 (cargar un momento cambia la partida) | **Aceptado:** confirmación y guía explícitas, anillo «Antes de cargar» de 3 fuera de la rotación de backups, AUTO unificado (cargar no lo pisa) y test de recuperación en un toque. Joel rechazó las instantáneas periódicas, pero el anillo de seguridad se mantiene por la regla 6. | §3.3, N6, ND7 |
| P4 (exclusión por huella) | **Aceptado** como criterio de N6 y N7, con equivalente en iOS. | §3.3, N6, N7 |
| P5 (viabilidad de la carpeta común) | **Resuelto con Joel:** el selector de iOS no muestra Google Drive; Android sí usa Drive. Sin carpeta común, el viaje es por paquete `.pgbm` («Enviar a otro dispositivo»), con bandeja `PocketGB/Intercambio/` en Android. `GMRoms/` se reorganiza igual que Drive en N9. | ND2, §3.4, N7, N9 |
| P6 (lotes que se pisan) | **Aceptado:** serie por plataforma N3 → N4 → N5/N8 Kotlin → N6 → N7. Linaje (N7a) antes que el paquete (N7b). Cadenas Android en `strings_<lote>.xml`. Catálogos solo con líneas añadidas al final. | §4, §5 |
| P7 (diagnóstico de Android) | **Aceptado.** Joel usa el modo «Siempre» (ND9): el desvanecido queda descartado. Se añade el diagnóstico real (sectores, zona muerta, región circular, contraste, háptica) y un criterio objetivo con traza sintética (≥ 99 % UP con ±8 pt/dp). | N2 |
| P8 (tabla de linaje) | **Aceptado:** la tabla está en §3.4, construida sobre el historial de escrituras propias que ya existe, con casos de latencia, conflicto y borrado. | §3.4, N7 |
| P9 (fusión de metadatos por dispositivo) | **Ya no aplica:** sin carpeta común no hay metadatos sincronizados automáticamente. Viajan dentro del `.pgbm` y se fusionan al importar. | ND12, §7 |
| P10 (`.pocketgb/` oculta, `_Revisar/` escaneada) | **Aceptado:** la carpeta visible es `PocketGB/` y los nombres reservados quedan en ND11 (prefijo `_` = apartada). | ND11, §3.2 |
| P11 (`.pgbm` en el hook) | **Aceptado:** se añade `*.pgbm` al hook en N7. Los vectores dorados usan carga sintética generada en el test. | N7 |
| P12 (fuentes de los offsets de Pokémon) | **Aceptado en parte:** fuentes documentales y código propio con cita; juegos oficiales por título y checksum global; Gen 1 y Gen 2 internacionales. No se pospone porque Joel lo eligió (ND5); va en la ola 1 por ser C puro. | §3.3, N6 |
| P13 (preferencias de iOS frágiles) | **Aceptado** en N1a. | §3.1 |
| P14 (quickKey, «Enviar» duplicado) | **Aceptado:** `quickKey` eliminado y caché (ruta, tamaño, fecha) → huella. «Enviar» es la exportación del paquete. D6 no se pospone (Joel lo eligió). | §3.1, §3.4 |
| P15 (reorganizar Drive y fechas) | **Aceptado:** N9 se hace con N1b/N7 instalados, inventario de sha antes y después, y movimientos que conservan id y fecha. | N9 |
| P16 (N1/N3 demasiado grandes; renombrar tarde) | **Aceptado:** N1a/N1b y N3a/N3b; renombrar en A9 (ola 1). | A9, N1, N3 |
| P17 (guía tardía, orden de recorte) | **Aceptado:** cada hito entrega su sección de guía; orden de recorte corregido. | §0, §4, §6 |
| P18 (decisiones provisionales afectadas) | **Aceptado:** J8, K9, K10, R14 y G7-3 listadas para ratificar; ND12 fija que momentos e inicio son por dispositivo. | §2 |
| P19 (detalles técnicos) | **Aceptado:** `#available` con alternativa y accesorio solo en Biblioteca en horizontal; `BitmapFactory` con `inSampleSize`; intents genéricos validados por cabecera; migración de RESCUE; encaje de portadas definido; decisiones renombradas a ND. | N3b, N5, N6, N7 |
| P20 (pruebas en dispositivo pendientes y ms/frame GBA) | **Aceptado:** las pruebas de partidas de PRUEBAS-JOEL son requisito de N7; ms/frame de GBA al terminar N8 nativo. | N7, N8, §6 |
| DS1 (orden de recorte contradictorio) | **Aceptado:** el orden sigue las prioridades de Joel. | §6 |
| DS2 (fechas en metadatos) | Ya no aplica (P9). Las partidas nunca deciden por fecha salvo sin historial, y entonces con backup. | §3.4 |
| DS3 (cita de la causa en iOS) | **Aceptado:** citado en N2. | N2 |
| DS4 (redacción de N9/N8 nativo) | **Aceptado.** | N8 |
| DS5 (`quickKey`) | **Aceptado** (= P14). | §3.1 |
| DS6 (los estados ya existen) | **Aceptado:** aclarado en N6 (lo que falta es la pantalla). | N6 |
| DS7 (exactitud de N1) | Sin cambios. | — |
