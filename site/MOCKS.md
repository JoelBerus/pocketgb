# Datos provisionales (mocks) de la landing

La landing usa datos reales del repositorio salvo lo que figura aquí. Cada mock está marcado en `index.html` con un comentario `<!-- MOCK ... -->`. Al sustituirlo, cambia también su traducción en `assets/i18n/en.js` y borra la fila.

| Qué | Dónde | Valor provisional | Qué hace falta para quitarlo |
|---|---|---|---|
| App Store | Sección «Descargas», primer botón | «Próximamente», sin enlace | Cuenta de Apple Developer de pago y la ficha publicada. Sustituir el `<span>` por un `<a>` a la ficha. |
| Google Play | Sección «Descargas», segundo botón | «Próximamente», sin enlace | Cuenta de Play Console y la ficha publicada (hoy Android v1 deja fuera la tienda: `docs/05-android-spec.md`). |
| APK en GitHub | Sección «Descargas», tercer botón | «Próximamente», sin enlace | Un GitHub Release con el APK Release **firmado** (`docs/07-instalacion-android.md`, sección de firma). |
| Precio | «Gratis.» en Descargas y la pregunta «¿Cuánto cuesta?» | Gratis | Decisión de Joel. |
| Dominio | `canonical`, `hreflang` y `og:url` en `<head>` | `https://joelberus.github.io/pocketgb/` (la URL real de GitHub Pages) | Solo si se compra un dominio propio: cambiar esas URL y añadir `CNAME` en `site/`. |

## Pendiente que no es mock, pero condiciona el texto
- **Licencia:** el repositorio no tiene `LICENSE`. La página dice «La licencia todavía no está decidida» y no usa «código abierto». Al elegirla, actualizar la pregunta «¿Es de código abierto?».
- **Cable link:** figura como «en pruebas» hasta la prueba real en el iPhone (`docs/ESTADO.md`).
- **Versiones:** iOS 0.4 (`MARKETING_VERSION`) y Android 1.0.0 (`versionName`). Actualizar al publicar.
