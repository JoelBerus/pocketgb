# D1 · Respuesta a la auditoría Opus ([D1-opus.md](D1-opus.md))

Todo corregido en `119f1e7` y verificado con el CI de macOS (ver [D1-evidencia.md](D1-evidencia.md) §6).

| ID | Estado | Qué se hizo |
|---|---|---|
| H1 (media) | corregido | "Abrir un archivo" en el aviso de carpeta ya no presenta la sheet desde el botón de la alerta. Ahora marca `pickAfterNotice`, y el `didSet` de `folderNoticeShown` abre el selector cuando la alerta se ha cerrado. Hay dos UI tests nuevos (`OpenFileTests`): uno por el menú `…` y otro por el aviso. Los dos esperan el selector de documentos. |
| H2 (media) | corregido | `AccentPrimary` en oscuro pasa de `#5A96FF` a `#3F7FEF`: el texto blanco queda a unos 3,9:1 en vez de 2,9:1. Anotado en SPEC §7.1. D7 revisará el contraste en conjunto. |
| H3 (baja) | corregido | `ScreenshotTests` exige que el nombre de la captura coincida con el valor de `-screen` cuando la línea lo lleva. Las líneas `game-*` son las heredadas de M4/M5 y D4/D5 las sustituyen por IDs de SPEC. |
| H4 (baja) | corregido en parte | `LaunchPreviewView`, `UnknownScreenView` y sus ramas en `RootView` quedan dentro de `#if DEBUG`. Los dos campos de `AppState` (`debugShowsLaunch`, `debugUnknownScreen`) siguen en Release: poner `#if` dentro de una clase `@Observable` es frágil con la macro, y en Release los campos no hacen nada. |
| H5 (baja) | corregido | Con un juego abierto, `preferredColorScheme` es `.dark` para toda la ventana, alertas incluidas. Nueva captura `game-acid portrait light`. |
| H6 (baja) | corregido | `launch` solo en dark, como SPEC §9. D-README y `screens.txt` quedan alineados. |
