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
