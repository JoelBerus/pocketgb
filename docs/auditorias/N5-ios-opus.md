# N5 · Portadas (iOS): auditoría Opus

Auditor: subagente Opus sin historial del desarrollo ([PROMPT.md](PROMPT.md)). Resumen transmitido por el coordinador.

**Veredicto: APROBAR CON CAMBIOS** (1 hallazgo medio y 3 bajos).

| # | Gravedad | Hallazgo |
|---|---|---|
| N5iA-1 | Media | `Covers.swift`, `CoverImageRules.sniff`/`CoverDecoder.decode`: HEIC se acepta en todos los decodificados (carpeta, caché, copias no confiables), no solo al importar. Un `Juego.png` que por dentro es HEIC llega al decodificador HEVC. La evidencia dice que la carpeta sigue limitada a png/jpg/jpeg/webp, pero eso solo se cumple por la extensión. |
| N5iA-2 | Baja | `loadFolderImage` usa una cola en serie: la primera lectura coordinada de una imagen de iCloud sin descargar bloquea la cola sin tiempo límite. |
| N5iA-3 | Baja | `GameArtworkStore.saveEncoded` hace `queue.sync` y escribe en el hilo principal PNG de hasta unos 4 MB. |
| N5iA-4 | Baja | `pruneFolderCache` se ejecuta tras cualquier escaneo que no llegue al tope: si la carpeta está inaccesible, el escaneo sale vacío y podría borrar toda la caché. |

**Sin bloqueantes.** Están bien: regla 6, red, binarios, escaneo, temporales de PhotosPicker, límites y «Borrar portadas». Tests: 292 OK; Release OK; ND17 OK; paridad OK (comprobada leyendo el código).
