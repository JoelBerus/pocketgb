# M0 · Respuesta a la auditoría de Codex (vuelta 1: RECHAZAR)

| ID | Decisión | Qué se hizo |
|---|---|---|
| M0-01 | corregido | M0 reabierto en `ESTADO.md`. Evidencia real en `M0-evidencia.md`. Se añade la regla: la evidencia la ejecuta el desarrollador en `MN-evidencia.md`, porque el sandbox de solo lectura del auditor no puede compilar (bloquea `/tmp`). |
| M0-02 | corregido | `pocketgb.h`: `gb_run_cycles`, `gb_serial_bit_cb` (maestro, por bit), `gb_serial_byte_cb` (notificación), `gb_serial_clock_external` (esclavo). Se elimina `gb_serial_receive`. 03 §Serial y M9 actualizados. |
| M0-03 | corregido | Huella = SHA-256 de **todo** el ROM (`uint8_t fingerprint[32]`). Save states con los 32 bytes y nombres de archivo con 128 bits. SHA-256 propio con vectores FIPS en los unit tests. |
| M0-04 | corregido | 03 §Arranque separa DMG / CGB nativo / CGB compatibilidad según Pan Docs (B por licencia y suma del título, HL `991A`/`007C`, F de DMG según el checksum). Tests `boot_regs-dmgABC` (M1) y `boot_regs-cgb` (M8). |
| M0-05 | corregido | 04 §Saves: rama de primer guardado (`rename(2)` + `fsync` del directorio) frente a reemplazo (`replaceItemAt`). Test de primer guardado añadido a M6. |
| M0-06 | corregido | `0x52–0x54` declarados fuera de alcance, con test de rechazo. |
| M0-07 | corregido | Título de 16/15/11 bytes según la cabecera; `0x143` nunca forma parte del título si es flag. |
| M0-08 | corregido | OAM DMA: en DMG solo HRAM; en CGB, conflicto solo en el bus del origen. |
| M0-09 | corregido | `GB_ERR_OUT_OF_MEMORY`; la instancia queda válida y sin ROM. Test con fallo de reserva inyectado. |
| M0-10 | corregido | `cloud-setup.sh` revalida `clang make ar nm python3 git curl unzip xxd` tras instalar y sale con código ≠ 0 si falta alguna. Las ROMs de prueba solo generan aviso. |

# Vuelta 2 (Codex: RECHAZAR)

| ID | Decisión | Qué se hizo |
|---|---|---|
| M0-05 (reabierto) | corregido | Nueva secuencia con invariante "siempre existe un `.sav` completo": el actual se **copia** a `.1` (nunca se mueve) y el nuevo se instala con un único `rename(2)`, que reemplaza atómicamente y también cubre el primer guardado. Tests de fallo inyectado tras cada paso (04 §Saves, M6). |
| M0-04 (reabierto) | **descartado**, con evidencia | La nota "F según `INC B`" de Pan Docs está en la columna **AGB (DMG mode)**. La columna CGB (DMG mode) dice `Z=1 N=0 H=0 C=0` = `0x80`. El propio test `misc/boot_regs-cgb.s` hace `assert_f $80` sobre un ROM DMG (`0x143=0x00`, B=0). Evidencia en M0-evidencia.md §6. Se aclara en 03 §Arranque. |
| M0-11 | corregido | M9 usa deadline absoluto (`T += 456`; cada instancia corre `T - t_i` y acumula lo devuelto), así la deriva queda acotada a ≤ 44 T-ciclos. Test con instrucciones de 4 y 24 T-ciclos. |
| M0-12 | corregido | Hook en bash con `read -r -d ''` sobre `git diff -z`. Probado con un nombre que contiene un salto de línea (M0-evidencia.md §5). |
