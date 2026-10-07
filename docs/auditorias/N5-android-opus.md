# N5 · Portadas (Android) — auditoría Opus

Auditor: subagente Opus sin historial del desarrollo ([PROMPT.md](PROMPT.md)), sobre `e181a3e`. Resumen transmitido por el coordinador.

**Veredicto: APROBAR CON CAMBIOS** (3 hallazgos bajos).

| # | Gravedad | Hallazgo |
|---|---|---|
| N5A-1 | Baja | Lint `UseKtx` en `N5Catalog.kt:120` (`Bitmap.createBitmap`); la evidencia dice que ningún aviso está en archivos de N5. |
| N5A-2 | Baja | La caché `covers/folder/` (clave SHA-256 de URI + sello) deja huérfanas las copias antiguas cuando la imagen cambia; solo «Borrar portadas» las limpia. Purgar tras el escaneo o anotar deuda. |
| N5A-3 | Baja | `StorageUsage.kt:21` suma todo `covers/`, incluido `settings.json`: tras «Borrar portadas» nunca vuelve a 0 B. Medir solo `imported` y `folder`. |

Nota: carrera menor, la imagen de la carpeta puede leerse dos veces a la vez la primera vez que se ve.

**Criterios verificados:** JVM 724/0; lint 0 errores, 26 avisos; decodificador ante imágenes hostiles correcto; regla 6 cumple (no toca la ruta de guardado); no toca imágenes del usuario; sin INTERNET; sin binarios en el repo; el escaneo no lee imágenes; K9, K10 y ND15 cumplen.

**Decisiones propias (1–9 de la evidencia):** ratificables; la 6 (la imagen de la carpeta no cuenta para el carril «Continuar jugando») la debe ratificar Joel.
