# Auditoría N6 iOS (subagente Opus): APROBAR CON CAMBIOS

Resumen fiel del informe entregado por el coordinador.

- **H1 (alta, regla 6)** `MomentActions.load`/`installSRAM` + `MomentStore.pushBeforeLoad`: recuperar la entrada más antigua con el anillo lleno la expulsa y borra sus archivos antes de saber si la operación tiene éxito; si falla `session.loadState` o `saves.save`, se pierde.
- **H2 (media)**: los kill-tests lanzan la excepción tras fsync/rename completos; no cubren un `.tmp` a medias ni la muerte entre el rename del `.state` y el del `.sav`.
- **H3 (media)**: `recoverOrphans` borra `m-*`/`b-*` no indexados; con un índice válido pero desfasado (restaurado de una copia) borraría momentos reales.
- **H4 (baja)**: `momentAfterOpen` no se limpia si la apertura falla.
- **H5 (baja)**: la sesión toma la huella después de construir `EmulatorSession`; durante la apertura la huella no tiene dueño.
- **H6 (baja)**: E/S con fsync en el MainActor.

Resto correcto; 314 tests OK.
