# 08 · ROMs: origen, almacenamiento y lo que nunca va al repo

## Regla
PocketGB solo se usa con **volcados de cartuchos que Joel posee**. Este repo no contiene, no enlaza y no ayuda a conseguir ROMs comerciales, ni boot ROMs de Nintendo.

## Por qué (técnico y legal)
- Pokémon Rojo y Amarillo tienen copyright de Nintendo, Game Freak y Creatures. Publicarlos o compartirlos (repo público, carpeta compartida, enlace de Drive) es distribución. GitHub los retira por DMCA y puede suspender la cuenta.
- Los sitios de "ROMs gratis" son un vector real de malware y de archivos modificados. Esto choca con el objetivo de seguridad del proyecto.
- El boot ROM tampoco se necesita: el núcleo arranca con el estado post-boot documentado ([03](03-core-spec.md)).

## Cómo volcar tus cartuchos
Con un lector USB de cartuchos, por ejemplo **GBxCart RW** (insideGadgets) o **GB Operator** (Epilogue):
1. Lee el ROM → `.gb` (Rojo/Azul, sin color) o `.gbc`. Amarillo sale como `.gb`, pero tiene el flag de compatibilidad CGB, y PocketGB lo detecta.
2. Lee también la **RAM de guardado** → `.sav`. Así traes tu partida real del cartucho (pila CR2025 incluida, si sigue viva).
3. Comprueba en PocketGB que los checksums coinciden (A15). Si no coinciden, limpia los contactos y repite el volcado.

## Dónde viven
- `iCloud Drive/PocketGB/` (o la carpeta que elijas en la app), **privada, sin compartir**. En Android, una carpeta local, de tarjeta SD o de Drive elegida con el selector del sistema (SAF); la app escribe `<rom>.sav` junto a la ROM, nunca la ROM.
- Si quieres un respaldo, usa un disco local cifrado. **Nunca** GitHub, ni público ni privado, ni un enlace compartido de Drive.

## Protecciones del repo
- `.gitignore`: `*.gb *.gbc *.sgb *.sav *.rtc *.state *.zip *.7z`, más la carpeta de ROMs de prueba.
- `.githooks/pre-commit`: además de la extensión, detecta por contenido el logo de cartucho (`CE ED 66 66` en `0x104`) y bloquea el commit aunque se renombre el archivo. Se activa con `git config core.hooksPath .githooks`.
- La auditoría de cada hito incluye `git log --all --stat` buscando binarios sospechosos.

## ROMs de prueba (sí permitidas localmente)
Blargg, Mooneye, acid2 y el resto del paquete c-sp son homebrew de libre distribución. Aun así, **no** se versionan: se descargan con hash verificado ([06](06-testing.md)).

## Game Boy Advance
- Mismas reglas: solo volcados de cartuchos propios. El `.gitignore` y el hook bloquean `*.gba`, `*.agb`, `*.srl` y cualquier archivo con el logo de la cabecera GBA en `0x04`, aunque se renombre.
- **BIOS:** la BIOS de Nintendo está **prohibida** en el repo, en la app y en los tests (regla dura 1). Nunca entra al repo (el hook bloquea archivos de 16 KiB llamados `*bios*` o `*.bin`). El núcleo funciona sin ella (HLE propia). Si Joel quiere más compatibilidad, puede volcar la de su propia GBA y dejarla como `gba_bios.bin` en su carpeta privada de iCloud, junto a los ROMs.
- Pruebas libres del núcleo GBA: jsmolka/gba-tests y SingleStepTests/ARM7TDMI (MIT), descargadas por `tools/fetch-gba-test-roms.sh`, nunca versionadas.
- **Licencias (GBA):** del código de otros proyectos solo se copia lo MIT/BSD/zlib citando el origen; en la práctica, solo SkyEmu (MIT) y el algoritmo de acarreo de multiplicación (zlib). mGBA (MPL-2.0, oráculo), NanoBoyAdvance, Hades y FuzzARM: leer sí, copiar no ([09](09-referencias.md)).
- `gba_bios.bin` propio de Joel es opcional, vive solo en su carpeta privada y la app solo lo valida por SHA-256 (no lo copia ni lo sube a ningún sitio).
