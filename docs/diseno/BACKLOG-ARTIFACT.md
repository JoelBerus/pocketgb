# Diferencias pendientes frente al artifact

Revisión posterior a D8 sobre el diseño de referencia. Este archivo prioriza diferencias; D8.1 no las implementa.

## Backlog recomendado

- **P1 — Cambiar artwork local:** elegir una imagen desde Fotos o Archivos, almacenada solo en el dispositivo y sin red.
- **P2 — Ver todos en recientes:** destino dedicado cuando el historial supere el carril visible.
- **P2 — Tiempo total jugado:** contador local por huella, con formato accesible.
- **P2 — Estados desde el detalle:** consulta y carga offline sin entrar primero al juego.
- **P2 — Reiniciar juego:** confirmación explícita desde pausa; nunca debe tocar SRAM ni backups.

## Ya integrado

Mando físico, avance rápido, audio, almacenamiento, ajustes por juego, paletas y accesibilidad (VoiceOver, AX5, Reduce Motion y Reduce Transparency).

## Descartado por las reglas del proyecto

- GBA y cheats: fuera del alcance GB/GBC y del núcleo aprobado.
- Artwork por red y audio en background: la app no usa red y el audio se limita a la sesión activa.
- Borrar ROMs: PocketGB solo lee la carpeta del usuario.
- Importación que copie archivos: la biblioteca conserva el acceso directo a la carpeta elegida.
