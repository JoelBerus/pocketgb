# CLAUDE.md

Responde en español. Las reglas del proyecto viven en AGENTS.md (compartidas con Codex):

@AGENTS.md

## Al empezar cualquier sesión (local o en la nube)
1. Lee `docs/ESTADO.md`: te dice en qué hito estamos y cuál es el siguiente paso exacto.
2. Si estás en la nube (Linux, `CLAUDE_CODE_REMOTE=true`): solo puedes avanzar los hitos ☁️ (ver `docs/hitos/README.md`). No hay Xcode, iPhone, Codex ni la bóveda de Obsidian de Joel. La auditoría usa el fallback: un subagente Opus con `docs/auditorias/PROMPT.md`.
3. Si `core/tests/roms/` no existe: `tools/fetch-test-roms.sh`.

## Al cerrar un hito
- Actualiza `docs/ESTADO.md` (estado, siguiente paso, decisiones) y la tabla de `docs/hitos/README.md`.
- Commits en español terminados con la línea de coautoría correspondiente. Rama `mN-<nombre>`, PR a `main`.
- En local (Mac de Joel), además: nota de Obsidian `Proyectos/pocketgb/Contexto.md` y diario del día.
