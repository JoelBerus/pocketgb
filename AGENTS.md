# AGENTS.md: reglas para cualquier agente en este repo

Idioma: español en docs, commits y comentarios de alto nivel. Identificadores de código en inglés.

## Roles
| Rol | Quién | Puede |
|---|---|---|
| Desarrollador | Claude (Opus) | Editar código, docs, tests; crear ramas `mN-<nombre>`; commits. |
| Auditor | Codex (`codex exec --sandbox read-only`) | **Solo leer y reportar.** Nunca edita, nunca hace commits. |
| Auditor de respaldo | Subagente Opus sin historial del desarrollo | Igual que Codex, cuando Codex no está disponible. |
| Dueño | Joel | Firma en Xcode, instala en el iPhone, vuelca sus cartuchos, aprueba merges. |

Flujo completo: [docs/auditorias/README.md](docs/auditorias/README.md). Ningún hito se cierra sin su informe de auditoría.

## Reglas duras (bloqueantes en auditoría)
1. **Nunca** agregar ROMs comerciales, boot ROMs de Nintendo, partidas (`.sav`) ni estados al repo. El hook `.githooks/pre-commit` lo refuerza; no lo desactives. Ver [docs/08-roms-legal.md](docs/08-roms-legal.md).
2. **Licencias:** solo se puede copiar código de proyectos MIT/BSD/zlib, citando origen en el archivo. Gambatte (GPLv2), Delta (AGPLv3), el directorio `iOS/` de SameBoy y cualquier GPL/AGPL: **leer sí, copiar no**, ni "adaptado".
3. **El ROM es entrada no confiable.** Todo acceso a memoria del cartucho pasa por funciones con bounds-check. Tamaños de ROM/RAM se validan contra la cabecera *y* contra el tamaño real del archivo.
4. **Núcleo (`core/`)**: C11 puro, sin I/O, sin `malloc` dentro de `gb_run_frame`, sin variables globales ni estáticas mutables (debe poder haber 2 instancias para el cable link), determinista (misma entrada → mismo framebuffer).
5. **App iOS**: sin red. No se añaden claves ATS, ni `URLSession`, ni SDKs, ni paquetes SPM de terceros en runtime.
6. **Partidas:** cualquier cambio en la ruta de guardado debe mantener escritura atómica + backups (ver [docs/04-ios-spec.md](docs/04-ios-spec.md) §Saves). Perder una partida es el peor bug posible.
7. No marcar un criterio de aceptación como cumplido sin haber ejecutado el comando que lo verifica y pegado la salida relevante en la PR/commit.

## Cómo compilar y probar
```bash
git config core.hooksPath .githooks        # una vez
tools/fetch-test-roms.sh                   # una vez, necesita red (ROMs de prueba libres)
make -C core test                          # suites headless
make -C core asan                          # tests con AddressSanitizer + UBSan
make -C core fuzz FUZZ_SECONDS=600         # libFuzzer sobre el parser/MBC
make -C core oracle                        # opcional: compila SameBoy para comparación
xcodebuild -project ios/PocketGB.xcodeproj -scheme PocketGB -destination 'generic/platform=iOS' build
```

## Mapa de documentos
- [00-vision](docs/00-vision.md) · [01-auditoria](docs/01-auditoria.md) · [02-arquitectura](docs/02-arquitectura.md)
- [03-core-spec](docs/03-core-spec.md) · [04-ios-spec](docs/04-ios-spec.md) · [05-android-spec](docs/05-android-spec.md)
- [06-testing](docs/06-testing.md) · [07-instalacion-iphone](docs/07-instalacion-iphone.md) · [08-roms-legal](docs/08-roms-legal.md) · [09-referencias](docs/09-referencias.md)
- **Estado actual y siguiente paso: [docs/ESTADO.md](docs/ESTADO.md)**. Léelo primero y actualízalo al cerrar cada hito.
- Hitos: [docs/hitos/](docs/hitos/). Trabajar **un hito a la vez**, en orden.
