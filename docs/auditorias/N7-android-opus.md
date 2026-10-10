# N7 Android · Auditoría Opus (resumen del coordinador)

Auditor: subagente Opus sin historial del desarrollo. Rama `n7-android-viajan` (hasta `cacb1d8`). Veredicto: **APROBAR CON CAMBIOS** (H1–H3 altas). Este archivo recoge fielmente el resumen que transmitió el coordinador; la respuesta está en [N7-android-respuesta](N7-android-respuesta.md) y el comportamiento común que fija las correcciones es ND20 ([N-README](../hitos/N-README.md) §2).

| # | Gravedad | Hallazgo |
|---|---|---|
| H1 | Alta | Divergencia falsa: tras instalar un cambio externo y abrir/cerrar sin guardar, la local es la recibida (solo en `received`, no en `lastOwn`); si el otro equipo vuelve a escribir → `DIVERGENCE` y el espejo se reescribe con la local, pisando en Drive el avance del otro. iOS tiene el mismo defecto. |
| H2 | Alta | `OWN_OLDER` reescribe el espejo sin guardar copia (una versión restaurada a propósito se pierde del dispositivo); iOS sí la aparta. |
| H3 | Alta | La divergencia al abrir no pregunta (§3.4): Android sigue con la local y reescribe el espejo. |
| H4 | Media | Un duplicado del mismo ROM con un `.sav` más viejo se clasifica `EXTERNAL_CHANGE` y gana sin mirar la fecha. |
| H5 | Media | `STALE`: Android no instala ni pregunta; iOS pregunta; los conjuntos de huellas conocidas son distintos. |
| H6 | Baja | La bandeja marca como visto lo que no se importó. |
| H7 | Baja | Una local ilegible o demasiado grande se trata como ausente → `INSTALL` sin backup. |
| H8 | Baja | Claves de META repetidas: gana la última. |
| H9 | Baja | `config` sin validar tipos; longitudes en code points (iOS en grafemas). |
| H10 | Baja | El detector de copias acepta `X 1.sav` y no excluye `X 2.sav` cuando existe `X 2.gb`. |
| H11 | Baja | `peek` en el hilo principal. |
| H12 | Baja | El aviso `ExternalChange` tapa `MirrorReadOnly`. |

Sin hallazgos en: reglas duras (ROMs, licencias, entrada no confiable, sin red), puente JNI, 790 tests JVM, APK sin `INTERNET`.
