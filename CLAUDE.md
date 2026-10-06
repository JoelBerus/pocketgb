# CLAUDE.md

Responde en español. Las reglas del proyecto viven en AGENTS.md (compartidas con Codex):

@AGENTS.md

## Al empezar cualquier sesión (local o en la nube)
1. Lee `docs/ESTADO.md`: te dice en qué hito estamos y cuál es el siguiente paso exacto.
2. Si estás en la nube (Linux, `CLAUDE_CODE_REMOTE=true`): avanzas los hitos ☁️ del núcleo y los hitos de diseño D1… de la app ([docs/hitos/D-README.md](docs/hitos/D-README.md)). No hay Xcode, iPhone, Codex ni la bóveda de Obsidian de Joel: la UI se verifica con el CI de macOS y sus capturas ([docs/diseno/VERIFICACION.md](docs/diseno/VERIFICACION.md)). La auditoría usa el fallback: un subagente Opus con `docs/auditorias/PROMPT.md`.
3. Si `core/tests/roms/` no existe: `tools/fetch-test-roms.sh`. Para el núcleo GBA (hitos G): si `gba/tests/roms/` no existe, `tools/fetch-gba-test-roms.sh` (~1 GB).

## Al cerrar un hito
- Actualiza `docs/ESTADO.md` (estado, siguiente paso, decisiones) y la tabla de `docs/hitos/README.md`.
- Commits en español terminados con la línea de coautoría correspondiente. Rama `mN-<nombre>`, PR a `main`.
- En local (Mac de Joel), además: nota de Obsidian `Proyectos/pocketgb/Contexto.md` y diario del día.
