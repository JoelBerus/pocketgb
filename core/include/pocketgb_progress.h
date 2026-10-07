/*
 * pocketgb_progress.h — lector de progreso de los Pokémon oficiales de Game Boy (N6-C).
 *
 * Función pura y de solo lectura: dada la cabecera del ROM y los bytes de la
 * partida (.sav) devuelve el progreso del jugador (nombre, medallas, Pokédex,
 * tiempo de juego, dinero). Sin I/O, sin malloc, sin estado global; la salida
 * solo depende de las entradas. Los bytes del .sav son entrada NO confiable:
 * todo acceso pasa por un bounds-check y solo se devuelven datos si el
 * checksum interno de la partida cuadra con la disposición oficial.
 *
 * Soporta las versiones internacionales (EN/ES/FR/DE/IT) de Rojo, Azul y
 * Amarillo (1.ª generación) y de Oro, Plata y Cristal (2.ª generación). El
 * japonés y el coreano quedan fuera; los hacks solo dan datos si conservan la
 * disposición oficial y su checksum cuadra.
 *
 * Spec y fuentes de cada offset: docs/03-core-spec.md §Lector de progreso Pokémon.
 */
#ifndef POCKETGB_PROGRESS_H
#define POCKETGB_PROGRESS_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum pgb_prog_game {
    PGB_PROG_NONE = 0,   /* no es un Pokémon oficial soportado */
    PGB_PROG_GEN1,       /* Rojo, Azul o Amarillo */
    PGB_PROG_GEN2_GS,    /* Oro o Plata */
    PGB_PROG_GEN2_C      /* Cristal */
} pgb_prog_game;

/* Cabecera mínima del ROM que hay que pasar (0x100–0x14F: título en 0x134, destino en 0x14A). */
#define PGB_PROG_HEADER_MIN 0x150u
/* RAM del cartucho de todos los juegos soportados (32 KiB). */
#define PGB_PROG_SAVE_BYTES 0x8000u
/* Cabe el peor caso: 10 glifos de 3 bytes en UTF-8 + NUL (con 24 no cabrían ♂/♀ en un nombre entero). */
#define PGB_PROG_NAME_MAX 32

typedef struct pgb_progress {
    pgb_prog_game game;
    char player_name[PGB_PROG_NAME_MAX]; /* UTF-8, terminado en NUL; '?' para glifos desconocidos */
    uint16_t badges_mask;       /* 1.ª gen: bits 0-7 (Roca…Tierra). 2.ª gen: Johto en bits 0-7, Kanto en 8-15 */
    uint8_t badges_count;       /* bits a 1 de badges_mask */
    uint16_t pokedex_owned;     /* capturados (151 en la 1.ª gen, 251 en la 2.ª; solo cuentan esos bits) */
    uint16_t pokedex_seen;      /* vistos */
    uint16_t play_hours;        /* 1.ª gen: 0–255. 2.ª gen: 0–999 */
    uint8_t play_minutes;       /* 0–59 */
    uint8_t play_seconds;       /* 0–59 */
    uint32_t money;             /* en Pokédólares (1.ª gen: BCD decodificado, máx. 999999) */
} pgb_progress;

/* Identifica el juego por la cabecera (título en 0x134 y código de destino 0x14A = 1,
 * no japonés). No mira la partida. Devuelve PGB_PROG_NONE si la cabecera no es de un
 * juego soportado, es NULL o tiene menos de PGB_PROG_HEADER_MIN bytes. */
pgb_prog_game pgb_progress_identify(const uint8_t *rom_header, size_t header_len);

/* Lee el progreso de la partida.
 *   rom_header: al menos PGB_PROG_HEADER_MIN bytes del ROM (el principio basta).
 *   sram: la partida. Se admiten exactamente PGB_PROG_SAVE_BYTES (32 KiB) o 32 KiB más un
 *         bloque RTC de 16, 44 o 48 bytes (el bloque se ignora); otro tamaño → false.
 * Devuelve true y rellena *out solo si el juego está soportado y la partida es coherente:
 * checksum correcto, nombre terminado, dinero BCD válido (1.ª gen), minutos y segundos < 60 y
 * bytes de validación de la 2.ª gen. En cualquier otro caso devuelve false y deja *out a cero
 * (game = PGB_PROG_NONE). Solo se lee la copia principal de la partida, no la de respaldo. */
bool pgb_progress_read(const uint8_t *rom_header, size_t header_len,
                       const uint8_t *sram, size_t sram_len, pgb_progress *out);

#ifdef __cplusplus
}
#endif

#endif /* POCKETGB_PROGRESS_H */
