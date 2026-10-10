# N6 iOS · respuesta a la auditoría Opus

| Hallazgo | Respuesta |
|---|---|
| H1 | Corregido. `pushBeforeLoad` se divide en `appendBeforeLoad` (añade sin expulsar) y `trimRing(keeping:)`. `MomentActions.load` e `installSRAM` solo recortan **después** de confirmar la carga o el guardado, y nunca expulsan la entrada recuperada ni la recién escrita. Si fallan, el anillo queda un momento con 4 (el siguiente recorte lo arregla). Test `recoveringTheOldestNeverEvictsItWhenTheOperationFails`: anillo lleno, recuperar la más antigua con el núcleo rechazando el estado, con la carpeta de partidas en solo lectura (pausa) y al instalar sin sesión → la entrada y su `.sav` siguen. **Mutación**: volviendo a `pushBeforeLoad` en `load`, el test falla en 4 expectativas. |
| H2 | Nuevo punto de fallo `between` entre la escritura del `.state` y la del `.sav`; el kill-test de cargar recorre `files`, `between` e `index`. Test `halfWrittenMomentIsSetAsideNotDeleted`: `.sav.tmp` a medias + `m-<id>.state` sin `.sav` ni índice → el temporal se borra y el `.state` se aparta. Sigue siendo inyección de fallos, no muerte real del proceso (documentado). |
| H3 | Corregido. `recoverOrphans` ya no borra archivos con estado, partida o miniatura sin entrada: los mueve a `orphans/` (nombre único si ya existe). Solo se borran los `.tmp`. |
| H4 | Corregido. `finishOpening` lo limpia si falla, `closeGame` lo limpia y `start` lo captura al entrar y lo descarta al salir (con éxito o error). |
| H5 | Corregido por la vía simple: `installMomentSave` se rechaza mientras `opening` es verdadero (aún no se conoce la huella del juego que se abre). La sesión sigue tomando la huella al crearse; cargar desde el detalle ya va por la sesión. |
| H6 | Documentado, sin cambio: crear/cargar momentos ya ocurre con la sesión aparcada en el hilo principal (como los estados de D5) y las escrituras son de unos KiB; instalar desde el detalle es una acción explícita con confirmación. Se puede mover a una cola si se mide un tirón. |

Además: merge de `siguiente-nivel` (trae N6 Android) sin conflictos.

## Verificación
```
iPhone 17 Pro: ✔ Test run with 316 tests in 28 suites passed after 13.157 seconds.
iPhone SE (3.ª gen): ✔ Test run with 316 tests in 28 suites passed after 13.621 seconds.
Release generic/platform=iOS: ** BUILD SUCCEEDED **
```
