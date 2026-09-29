# Auditorías: Claude desarrolla, Codex audita

## Flujo por hito
1. **Desarrollo (Claude/Opus).** Rama `mN-<nombre>`. Se implementa hasta que todos los criterios del hito pasan, **ejecutando** sus comandos.
2. **Auditoría (Codex, solo lectura).** Desde la raíz del repo, en el Mac:
   ```bash
   N=M1; codex exec --sandbox read-only "$(cat docs/auditorias/PROMPT.md)
   Hito: docs/hitos/$N-*.md. Revisa el diff: git diff main...HEAD" > docs/auditorias/$N-codex.md
   ```
   Codex **no edita**: el sandbox es de solo lectura y el prompt lo prohíbe.
3. **Fallback.** Si `codex` no está disponible (sin cuota, sin login, o en Claude en la nube, donde no hay Codex): Claude lanza un **subagente Opus nuevo**, sin el historial del desarrollo, con el mismo `PROMPT.md`, y guarda la salida en `docs/auditorias/MN-opus.md`. Cuando Codex vuelva a estar disponible, se puede repetir la auditoría con Codex y guardarla junto a la otra.
4. **Cierre.** Claude responde cada hallazgo en `docs/auditorias/MN-respuesta.md`: `corregido (commit)`, `descartado (razón)` o `diferido (hito)`. Los bloqueantes no se pueden descartar sin la aprobación de Joel. Después: merge a `main` y actualización de [ESTADO.md](../ESTADO.md).
