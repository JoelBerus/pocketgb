# Auditoría N4 Android · categorías, etiquetas, inicio y centro de ajustes (Opus, subagente sin historial)

Fecha: 2026-10-07. Rama `n4-android-categorias` en `4a26993` (diff `siguiente-nivel...n4-android-categorias`). Rol: solo lectura; copia `/private/tmp/claude-501/audit-n4a`. Informe transcrito en forma condensada (contenido sin cambios).

## Veredicto: APROBAR CON CAMBIOS
Nada bloqueante. Datos del usuario protegidos: ninguna escritura usa una huella sin confirmar ni la de otro juego; `preferences.json` lee v2/v3; una v5 no se sobrescribe ni se aparta; escritura atómica de N1. Evidencia reproducida desde limpio: JVM 664/0, instrumentadas 446/0 (52 clases), kill-test 50/50, lint 0 errores/20 avisos, APKs con el mismo tamaño. Antes de fusionar: H1 (conteo de Favoritos) y H2 (centro redundante).

## Hallazgos
| ID | Severidad | Archivo:línea | Problema | Corrección sugerida |
|---|---|---|---|---|
| H1 | media | `ui/library/LibraryContent.kt:661`, `library/LibraryOrganization.kt:231` | Con > 10 favoritos la fila dice «10 juegos» (TalkBack igual): `favoritesCount` cuenta la lista ya recortada. | `favoritesTotal` en `HomeSections` + test con 11. |
| H2 | media (diseño) | `ui/details/GameCenter.kt:115-163` | El centro repite lo mismo tres veces («Favoritas · movido en la app», insignia, «Su carpeta: Puzles»); «Volver a su carpeta» suelta con mucho aire; etiquetas con aspecto de chip pulsable que no lo son. | Solo la categoría con la insignia y «Su carpeta: …»; «Volver a su carpeta» como acción secundaria junto a «Cambiar»; etiquetas con estilo de texto. |
| H3 | media (cobertura) | `AdaptiveLibraryUiTest.kt:116`, `LibraryUiTest.kt:103,135-146` | Las pruebas de N3 ocultan las estanterías: el horizontal solo se prueba con el carril delante; `HomeUiTest` no tiene horizontal. | Prueba horizontal con el inicio completo: desplazar hasta «Todos los juegos», ver la flotante y abrir un panel sin tapar el título. |
| H4 | baja (a11y) | `ui/components/GameCard.kt:148` | En tarjetas estrechas TalkBack probablemente anuncia «Movido en la app» dos veces (la insignia no usa `announce=false`). | `contentDescription = if (compact && announce) text else null`. |
| H5 | baja (a11y) | `ui/library/CategoryScreen.kt:268-275` | TalkBack lee los separadores «›» de las migas. | `clearAndSetSemantics {}` en `Separator()`. |
| H6 | baja (a11y) | `GameCenter.kt:138,151` | La etiqueta accesible de «Cambiar» no contiene el texto visible (WCAG 2.5.3). | «Cambiar categoría». |
| H7 | baja (prueba) | `HomeUiTest.kt` (`atTwoHundredPercent…`) | «Ver todo no se corta» compara texto semántico (siempre entero). | `TextLayoutResult.hasVisualOverflow` o ancho medido. |
| H8 | baja (doc) | `docs/11-biblioteca-carpetas.md:60`, `docs/guia/categorias-android.md:50` | Afirman sin matices que mover/renombrar conserva la categoría virtual; solo si el escaneo reconoce el movimiento. | «en cuanto PocketGB reconoce el archivo (normalmente al volver a escanear; si no, al abrir el juego o su detalle)». |
| H9 | baja (doc) | `docs/LEEME-PocketGB.txt:27` | Dice que se leen `.gba` y Android aún no los lista. | «(.gba: iPhone; Android cuando llegue)». |
| H10 | baja | `library/LibraryPreferences.kt:274-276` | Con copias en carpetas distintas, «elegir su propia carpeta = volver» actúa sobre todas; la copia que está en su carpeta real lleva la insignia (`isMovedInApp` solo mira `virtualFolderPath != null`). | `isMovedInApp` compara `virtualFolderPath != folderPath`; documentarlo. |
| H11 | baja | `library/LibraryOrganization.kt:177,187` | N4A-9 no se cumple si se movió algo con la categoría fijada (se guarda con las fijadas delante); tras mover, una carpeta nueva aparece detrás de «Sin categoría». | Guardar solo el orden relativo y mantener «.» al final; test. |
| H12 | baja | `library/LibraryCategory.kt:66-70` | `fromKey` es código muerto; una carpeta `*` choca con `ALL_KEY`. | Quitarlo o usarlo. |
| H13 | baja (UX) | `CategoryPickerDialog`, `N4Catalog` | «pokémon» nueva crea una virtual distinta de «Pokémon»; el ejemplo «Favoritas» compite con Favoritos. | Proponer la existente si coincide sin mayúsculas ni acentos; ejemplo «Para jugar». |
| H14 | baja (evidencia) | commit `df14840` | Dice «estanterías con fuente al 200 %» pero la captura solo muestra el carril. | Ajustar el `swipe` o el texto. |
| H15 | baja | `GameSettingsSheet.kt` (`onPick`/`onAdd`) | Si la huella deja de estar confirmada entre abrir y aplicar, el diálogo se cierra sin cambios ni aviso. | Aviso o reconfirmar y reintentar. |

## Criterios (resumen)
Reglas duras: sí. Árbol → secciones, subcategorías y migas: sí (`LibraryOrganizationTest` 31, `HomeUiTest` 9, `CategoryNavigationTest`). Categoría virtual aplicada y revertida: sí. `preferences.json` v4: sí (`LibraryFormatV4Test` 7). Ajustes de inicio por dispositivo: sí (ver H11). Filtro y búsqueda por etiqueta/categoría: sí. Interacción con N3: parcial (H3). Accesibilidad: parcial (H4–H7). Capturas: sí (H2, H14). Documentación: sí (H8, H9). JVM 664, instrumentadas 446, kill-test 50/50: sí. Mutaciones: no repetidas.

## Decisiones que deberían ser de Joel
N4A-1 (las categorías abren su pantalla en vez de filtrar en el sitio, como se aprobó en N3), N4A-2 (sin carpetas no hay estanterías), N4A-3 (límite de 10; estanterías cuentan archivos y Favoritos juegos), N4A-5 (categoría virtual compartida por copias, ver H10), N4A-8 (etiquetas no visibles en las tarjetas) y que la pausa siga sin centro de ajustes.
