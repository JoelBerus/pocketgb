/*
 * pocketgb_pgbm.h — contenedor `.pgbm` (N7-C): el archivo único con el que las apps de iOS y
 * Android exportan un momento o envían una partida a otro dispositivo sin red.
 *
 * Un `.pgbm` agrupa secciones opacas para el C: META (JSON UTF-8 de las apps), SAVE (la partida,
 * `.sav` crudo), STAT (un save state del núcleo, opcional), THMB (miniatura PNG, opcional) y
 * ROMF (huella truncada del ROM, 16 bytes). Esta API solo empaqueta, valida y desempaqueta:
 * no interpreta el JSON, no abre el `.sav` ni el estado, no toca el disco.
 *
 * Reglas del núcleo (AGENTS.md): C11, sin I/O, sin malloc, sin estado global, determinista
 * (el mismo `pgbm_view` produce siempre los mismos bytes). El archivo es ENTRADA NO
 * CONFIABLE: `pgbm_parse` acota todas las longitudes antes de usarlas, verifica el CRC-32 de
 * todo el contenido y no copia nada (los spans apuntan dentro del buffer del llamador).
 *
 * Formato byte a byte, topes y vectores dorados: docs/12-formato-pgbm.md.
 */
#ifndef POCKETGB_PGBM_H
#define POCKETGB_PGBM_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Versión del formato que escribe y entiende esta biblioteca. Una versión distinta se rechaza. */
#define PGBM_VERSION 1u

/* Topes (bytes). Se aplican al leer y al escribir. Son literales sencillos (no expresiones) para que
 * Swift los importe como constantes. */
#define PGBM_MAX_TOTAL    4194304u   /* 4 MiB: archivo completo, cabecera y CRC incluidos */
#define PGBM_MAX_META     65536u     /* 64 KiB: JSON de las apps */
#define PGBM_MAX_SAV      131136u    /* 128 KiB + 64 B: .sav hasta 128 KiB + RTC (GB 48 B, GBA 16 B) */
#define PGBM_MAX_STATE    1048576u   /* 1 MiB: estado del núcleo (el de GBA ocupa ≈ 666 KiB) */
#define PGBM_MAX_THUMB    262144u    /* 256 KiB: miniatura PNG */
#define PGBM_ROMF_BYTES   16u        /* longitud exacta de la huella del ROM */
#define PGBM_MAX_SECTIONS 64u        /* secciones del archivo, las desconocidas incluidas */

/* Un trozo de bytes. «Presente» = len > 0 (SAV es la excepción: ver pgbm_view.sav). */
typedef struct pgbm_span {
    const uint8_t *data;
    uint32_t len;
} pgbm_span;

/*
 * Contenido de un paquete.
 *   meta  JSON UTF-8 sin NUL, ≤ PGBM_MAX_META. len 0 = ausente.
 *   sav   la partida, ≤ PGBM_MAX_SAV. Es obligatoria en el archivo: len 0 es una partida vacía
 *         (juego sin batería), no «sin sección». El importador debe comprobar que el tamaño
 *         encaja con el cartucho antes de tocar nada.
 *   state estado del núcleo, ≤ PGBM_MAX_STATE. len 0 = ausente.
 *   thumb PNG (solo se comprueba la firma), ≤ PGBM_MAX_THUMB. len 0 = ausente.
 *   rom_fp huella del ROM (16 bytes); siempre presente.
 *   version  al leer: la versión del archivo (siempre PGBM_VERSION si hay éxito). Al escribir:
 *            0 o PGBM_VERSION; otro valor → PGBM_ERR_VERSION.
 * Al leer, los spans ausentes quedan {NULL, 0}.
 */
typedef struct pgbm_view {
    pgbm_span meta, sav, state, thumb;
    uint8_t rom_fp[PGBM_ROMF_BYTES];
    uint16_t version;
} pgbm_view;

typedef enum pgbm_result {
    PGBM_OK = 0,
    PGBM_ERR_MAGIC,      /* no empieza por «PGBM» */
    PGBM_ERR_VERSION,    /* versión de formato desconocida (0 o mayor que PGBM_VERSION) */
    PGBM_ERR_TRUNCATED,  /* el buffer es más corto que la longitud que declara la cabecera */
    PGBM_ERR_BOUNDS,     /* estructura imposible: sección que se sale, bytes sobrantes, longitud
                            no permitida (ROMF ≠ 16, sección opcional vacía), tipo no ASCII */
    PGBM_ERR_CRC,        /* el CRC-32 no coincide: el archivo se corrompió */
    PGBM_ERR_DUPLICATE,  /* una sección conocida aparece dos veces */
    PGBM_ERR_MISSING,    /* falta SAVE o ROMF */
    PGBM_ERR_UTF8,       /* META no es UTF-8 válido o contiene NUL */
    PGBM_ERR_PNG,        /* THMB no empieza por la firma PNG */
    PGBM_ERR_TOO_LARGE,  /* sección, archivo o número de secciones por encima de los topes */
    PGBM_ERR_ARG,        /* argumento NULL (o span con datos NULL y longitud > 0) */
    PGBM_ERR_NOSPACE     /* el buffer de salida es menor que pgbm_encoded_size() */
} pgbm_result;

/*
 * Lee y valida un paquete. No copia: los spans de *out apuntan dentro de `buf`, que debe
 * seguir vivo y sin cambios mientras se usen. Orden de las comprobaciones: mágico → versión →
 * longitud → CRC-32 → estructura de las secciones → obligatorias → META y THMB. Los tipos de
 * sección desconocidos se ignoran (cuentan para el CRC). Si falla, *out queda a cero.
 */
pgbm_result pgbm_parse(const uint8_t *buf, size_t len, pgbm_view *out);

/*
 * Bytes que ocupa el paquete de `in` en el formato canónico, o 0 si no cabe en los topes (o
 * `in` es NULL, o un span con datos NULL y longitud > 0). Solo mide: el contenido de META y
 * THMB lo valida pgbm_encode.
 */
size_t pgbm_encoded_size(const pgbm_view *in);

/*
 * Escribe el paquete canónico (orden de secciones ROMF, META, THMB, SAVE, STAT) en
 * out[0..cap) y deja en *written (puede ser NULL) los bytes escritos. Valida lo mismo que
 * pgbm_parse, así que todo lo que se escribe vuelve a leerse. Si falla no toca `out` y
 * *written = 0. `out` no debe solaparse con los spans de `in`.
 */
pgbm_result pgbm_encode(const pgbm_view *in, uint8_t *out, size_t cap, size_t *written);

/* Nombre estable («PGBM_ERR_CRC»…) para registros y pruebas; «PGBM_ERR_UNKNOWN» fuera de rango. */
const char *pgbm_result_name(pgbm_result r);

#ifdef __cplusplus
}
#endif

#endif /* POCKETGB_PGBM_H */
