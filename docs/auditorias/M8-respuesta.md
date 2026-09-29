# M8 · Respuesta a la auditoría Opus ([M8-opus.md](M8-opus.md))

Todos los hallazgos están corregidos en el commit `9f1b11c`, con tests de regresión en `core/tests/unit_cgb.c` (`audit_regressions`).

| ID | Estado | Qué se hizo |
|---|---|---|
| H1 (media) | corregido (`9f1b11c`) | El máximo de `stall` en la carga pasa a `16 + 128×16`, el valor alcanzable. Además, `gb_state_save` valida su propia salida con la misma pasada que `gb_state_load` y devuelve `GB_ERR_STATE_CORRUPT` antes que un estado ilegible. Los tests que fabrican estados inválidos usan el gancho `dbg.unchecked_save` (solo con `GB_TEST_HOOKS`) y comprueban también la autocomprobación. Hay un test de ida y vuelta con `stall = 2064`. |
| H2 (baja) | corregido (`9f1b11c`) | `gb_tick` descarta la petición de bloque de HBlank con la CPU en HALT o STOP; la transferencia sigue en los HBlank posteriores al despertar. Test: HALT con IE=0 y HDMA de HBlank pendiente → FF55 no cambia en 3 líneas. |
| H3 (baja) | corregido (`9f1b11c`) | `hdma_block` devuelve `false` al desbordar el destino. El HDMA general sale del bucle, el de HBlank se desactiva y FF55 lee `0xFF`. Test: destino `0x1FE0` con 4 bloques pedidos → se copian 2 y el principio de la VRAM queda intacto. |
| H4 (baja) | corregido (`9f1b11c`) | Aviso Expat de SameBoy completo y literal en `cgb.c`. |
| H5 (baja) | corregido (`9f1b11c`) | Al cargar un estado en compatibilidad se reaplica `opts.compat_palette` (`cgb_load_compat_palettes`). Documentado en 03-core-spec §Arranque. |

Nota sobre el diff: el auditor vio que su `main` local estaba en M0. El `main` remoto está en `d6905d7` (merge de M5), que es la base de esta rama; la PR mostrará solo M8.
