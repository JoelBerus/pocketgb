# M6 · Biblioteca en iCloud Drive + saves robustos

🍎 Solo Mac. Spec: [04](../04-ios-spec.md) §Biblioteca, §Saves. Este hito cierra A4 y A5 de [01](../01-auditoria.md).

**Archivos:** `ios/PocketGB/Library/*`, `Saves/{SaveStore,AtomicFile}.swift`, `Settings/` (sección Partidas con los backups).

**Criterios de aceptación** (Joel en el iPhone + tests unitarios Swift en un target `PocketGBTests`)
- [ ] Tests de `AtomicFile`: escribir → matar a mitad (simulado lanzando error tras el tmp) → el archivo original sigue intacto.
- [ ] Tests de rotación: 7 guardados distintos → existen `.1`–`.5`; guardados idénticos no rotan.
- [ ] Elegir la carpeta → cerrar la app → reabrir: la biblioteca aparece sin volver a elegir la carpeta.
- [ ] Guardar en Pokémon → forzar el cierre de inmediato (< 2 s) → reabrir → la partida está.
- [ ] Reinstalar desde Xcode → la partida está. El `.sav` aparece junto al ROM en iCloud (verificable desde el Mac en Finder).
- [ ] Un `.sav` de tamaño incorrecto en la carpeta → error visible y el archivo no se modifica.
