# Auditoría Opus de las correcciones de Codex (M8 H1 y M9 H1–H3), 2026-09-29

M8 (m8-dma-buses): **APROBAR**, sin hallazgos; 157/157 requeridos, ASan limpio, check-globals OK.
M9 (commit d2fe3d3): **APROBAR CON CAMBIOS**: sin defectos en el código; falta ejecutar la campaña fuzz-link de 600 s y pegar la salida (regla 7). 157/157, ASan limpio, check-globals OK. `state_load_epoch` no se serializa ni es global; tests T+45/T+500/T+912 cubren la vía por época.
