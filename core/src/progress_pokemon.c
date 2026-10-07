/*
 * progress_pokemon.c — lector de progreso de Pokémon Rojo/Azul/Amarillo y Oro/Plata/Cristal
 * (internacionales). API y reglas: include/pocketgb_progress.h y docs/03-core-spec.md.
 *
 * FUENTES (solo se toman HECHOS: posiciones, tamaños y fórmulas; el código es propio.
 * PKHeX es GPLv3 y pret no declara licencia: no se copia nada de ellos):
 *   - pret/pokered, pokeyellow, pokegold y pokecrystal: ram/wram.asm y ram/sram.asm
 *     (disposición de datos; los desplazamientos se calcularon evaluando esos archivos) y
 *     engine/menus/save.asm (CalcCheckSum, VerifyChecksum, CheckPrimarySaveFile),
 *     engine/play_time.asm y home/game_time.asm (contadores de tiempo),
 *     constants/charmap.asm (tabla de caracteres).
 *   - Data Crystal «Pokémon Red and Blue / Gold and Silver / Crystal: RAM map» (direcciones
 *     internacionales de nombre, dinero, medallas, Pokédex y tiempo) y PKHeX
 *     (Saves/Substructures/Gen12/SAV1Offsets.cs y SAV2Offsets.cs: posiciones de la copia
 *     principal de la partida, solo como contraste de los números).
 * Detalle y tabla completa: docs/03-core-spec.md y docs/auditorias/N6-C-evidencia.md.
 *
 * Reglas del núcleo (AGENTS.md): C11, sin I/O, sin malloc, sin variables globales ni
 * estáticas mutables. El .sav es entrada no confiable: todo acceso pasa por `at()`.
 */
#include <string.h>

#include "pocketgb_progress.h"

/* ---- Posiciones en el .sav (banco 1 de la SRAM = archivo + 0x2000) ---- */
enum {
    NAME_BYTES = 11,            /* NAME_LENGTH de las versiones internacionales: 10 glifos + 0x50 */
    TERMINATOR = 0x50,
    RTC_SUFFIX_A = 16,
    RTC_SUFFIX_B = 44,          /* BGB/VBA antiguo */
    RTC_SUFFIX_C = 48,          /* VBA/BGB actual; es el que escribe gb_sram_save */

    /* 1.ª generación (Rojo/Azul/Amarillo): sGameData = [0x2598, 0x3523), checksum en 0x3523. */
    G1_GAME_DATA = 0x2598,      /* = sPlayerName */
    G1_CHECKSUM = 0x3523,       /* = sMainDataCheckSum = sGameDataEnd */
    G1_DEX_OWNED = 0x25A3,      /* wPokedexOwned: 19 bytes, 151 bits */
    G1_DEX_SEEN = 0x25B6,
    G1_MONEY = 0x25F3,          /* wPlayerMoney: 3 bytes BCD, el más significativo primero */
    G1_BADGES = 0x2602,         /* wObtainedBadges: 1 byte */
    G1_PLAY_TIME = 0x2CED,      /* horas, «máximo alcanzado», minutos, segundos, fotogramas (1 byte cada uno) */
    G1_DEX_BITS = 151,

    /* 2.ª generación: sCheckValue1 en 0x2008 y sGameData desde 0x2009 (wPlayerID 2 B, wPlayerName 11 B). */
    G2_CHECK_VALUE_1 = 0x2008,
    G2_GAME_DATA = 0x2009,
    G2_NAME = 0x200B,
    G2_DEX_BITS = 251,
    G2_DEX_BYTES = 32,
    G2_SAVE_CHECK_1 = 99,       /* SAVE_CHECK_VALUE_1 */
    G2_SAVE_CHECK_2 = 127,      /* SAVE_CHECK_VALUE_2 */

    /* Oro/Plata: sGameDataEnd = sChecksum = 0x2D69 (el checksum va pegado al final de los datos). */
    GS_GAME_DATA_END = 0x2D69,
    GS_CHECKSUM = 0x2D69,
    GS_CHECK_VALUE_2 = 0x2D6B,
    GS_PLAY_TIME = 0x2053,      /* horas (2 B, big-endian), minutos, segundos, fotogramas */
    GS_MONEY = 0x23DB,          /* 3 B big-endian */
    GS_BADGES = 0x23E4,         /* Johto; Kanto en el byte siguiente */
    GS_DEX_OWNED = 0x2A4C,
    GS_DEX_SEEN = 0x2A6C,

    /* Cristal: sGameDataEnd = 0x2B83, y 0x18A bytes después el checksum (0x2D0D). */
    C_GAME_DATA_END = 0x2B83,
    C_CHECKSUM = 0x2D0D,
    C_CHECK_VALUE_2 = 0x2D0F,
    C_PLAY_TIME = 0x2052,
    C_MONEY = 0x23DC,
    C_BADGES = 0x23E5,
    C_DEX_OWNED = 0x2A27,
    C_DEX_SEEN = 0x2A47
};

/* ---- Acceso con bounds-check ---- */

/* Devuelve sav+off si [off, off+n) cabe en len; si no, NULL (sin aritmética que desborde). */
static const uint8_t *at(const uint8_t *sav, size_t len, size_t off, size_t n)
{
    if (off > len || n > len - off)
        return NULL;
    return sav + off;
}

/* ---- Cabecera ---- */

static bool title_is(const uint8_t *h, const char *t, size_t n, bool zero_after)
{
    if (memcmp(h + 0x134, t, n) != 0)
        return false;
    return !zero_after || h[0x134 + n] == 0;
}

pgb_prog_game pgb_progress_identify(const uint8_t *h, size_t n)
{
    if (!h || n < PGB_PROG_HEADER_MIN)
        return PGB_PROG_NONE;
    /* 0x14A = 1: edición no japonesa. Las ediciones japonesas de la 1.ª generación comparten
     * título con las internacionales pero guardan la partida en otra disposición. */
    if (h[0x14A] != 0x01)
        return PGB_PROG_NONE;
    /* Rojo, Azul y Amarillo: el título va terminado en 0 (en el resto del campo hay padding). */
    if (title_is(h, "POKEMON RED", 11, true) || title_is(h, "POKEMON BLUE", 12, true) ||
        title_is(h, "POKEMON YELLOW", 14, true))
        return PGB_PROG_GEN1;
    /* Oro y Plata: 11 caracteres; 0x13F–0x142 llevan el código del juego (AAUE…), no padding. */
    if (title_is(h, "POKEMON_GLD", 11, false) || title_is(h, "POKEMON_SLV", 11, false))
        return PGB_PROG_GEN2_GS;
    if (title_is(h, "PM_CRYSTAL", 10, true))
        return PGB_PROG_GEN2_C;
    return PGB_PROG_NONE;
}

/* ---- Texto (tabla de caracteres internacional de las generaciones 1 y 2) ---- */

/* Punto de código Unicode del glifo `c`, o -1 si no está en la tabla (se escribirá '?').
 * Solo se incluyen los caracteres que admite el teclado del nombre más los acentos que
 * documenta pret; el resto de la tabla (Katakana, control, marcos) se trata como desconocido. */
static int glyph_codepoint(uint8_t c, bool gen2)
{
    if (c >= 0x80 && c <= 0x99)
        return 'A' + (c - 0x80);
    if (c >= 0xA0 && c <= 0xB9)
        return 'a' + (c - 0xA0);
    if (c >= 0xF6)                      /* 0xF6–0xFF */
        return '0' + (c - 0xF6);
    switch (c) {
    case 0x7F: return ' ';
    case 0x9A: return '(';
    case 0x9B: return ')';
    case 0x9C: return ':';
    case 0x9D: return ';';
    case 0x9E: return '[';
    case 0x9F: return ']';
    case 0xE0: return '\'';
    case 0xE3: return '-';
    case 0xE6: return '?';
    case 0xE7: return '!';
    case 0xE8:                          /* punto y «punto decimal» (0xF2) */
    case 0xF2: return '.';
    case 0xEF: return 0x2642;           /* ♂ */
    case 0xF0: return 0x00A5;           /* ¥ (signo del Pokédólar) */
    case 0xF1: return 0x00D7;           /* × */
    case 0xF3: return '/';
    case 0xF4: return ',';
    case 0xF5: return 0x2640;           /* ♀ */
    default: break;
    }
    if (!gen2)
        return c == 0xBA ? 0x00E9 : -1;     /* é */
    switch (c) {
    case 0xC0: return 0x00C4;           /* Ä */
    case 0xC1: return 0x00D6;           /* Ö */
    case 0xC2: return 0x00DC;           /* Ü */
    case 0xC3: return 0x00E4;           /* ä */
    case 0xC4: return 0x00F6;           /* ö */
    case 0xC5: return 0x00FC;           /* ü */
    case 0xE9: return '&';
    case 0xEA: return 0x00E9;           /* é */
    default: return -1;
    }
}

/* Añade el punto de código `cp` en UTF-8 (≤ 0xFFFF). Devuelve los bytes escritos, 0 si no cabe. */
static size_t put_utf8(char *dst, size_t room, int cp)
{
    if (cp < 0x80) {
        if (room < 1)
            return 0;
        dst[0] = (char)cp;
        return 1;
    }
    if (cp < 0x800) {
        if (room < 2)
            return 0;
        dst[0] = (char)(0xC0 | (cp >> 6));
        dst[1] = (char)(0x80 | (cp & 0x3F));
        return 2;
    }
    if (room < 3)
        return 0;
    dst[0] = (char)(0xE0 | (cp >> 12));
    dst[1] = (char)(0x80 | ((cp >> 6) & 0x3F));
    dst[2] = (char)(0x80 | (cp & 0x3F));
    return 3;
}

/* Decodifica un nombre de NAME_BYTES bytes. false si no hay terminador 0x50 dentro (un nombre
 * real siempre lo tiene: lo escribe el propio juego, y el checksum no lo garantiza). */
static bool decode_name(const uint8_t *raw, bool gen2, char *out)
{
    size_t n = 0;
    for (size_t i = 0; i < NAME_BYTES; i++) {
        if (raw[i] == TERMINATOR) {
            out[n] = '\0';
            return true;
        }
        int cp = glyph_codepoint(raw[i], gen2);
        size_t w = put_utf8(out + n, PGB_PROG_NAME_MAX - 1 - n, cp < 0 ? '?' : cp);
        if (w == 0)
            return false;           /* no ocurre: 10 glifos × 3 bytes + NUL < PGB_PROG_NAME_MAX */
        n += w;
    }
    return false;
}

/* ---- Utilidades de campos ---- */

static uint16_t count_bits(const uint8_t *p, unsigned nbits)
{
    uint16_t n = 0;
    for (unsigned i = 0; i < nbits; i++)
        n = (uint16_t)(n + ((p[i >> 3] >> (i & 7)) & 1u));
    return n;
}

static uint8_t popcount16(uint16_t v)
{
    uint8_t n = 0;
    for (; v; v = (uint16_t)(v & (v - 1u)))
        n++;
    return n;
}

/* Dinero de la 1.ª generación: 3 bytes BCD (6 dígitos). false si algún nibble pasa de 9. */
static bool decode_bcd3(const uint8_t *p, uint32_t *out)
{
    uint32_t v = 0;
    for (int i = 0; i < 3; i++) {
        unsigned hi = (unsigned)p[i] >> 4, lo = (unsigned)p[i] & 15u;
        if (hi > 9 || lo > 9)
            return false;
        v = v * 100u + hi * 10u + lo;
    }
    *out = v;
    return true;
}

/* ---- 1.ª generación ---- */

/* CalcCheckSum de pokered: 255 − suma de 8 bits de [G1_GAME_DATA, G1_CHECKSUM). */
static bool g1_checksum_ok(const uint8_t *sav, size_t len)
{
    const uint8_t *p = at(sav, len, G1_GAME_DATA, G1_CHECKSUM - G1_GAME_DATA);
    const uint8_t *c = at(sav, len, G1_CHECKSUM, 1);
    if (!p || !c)
        return false;
    uint8_t sum = 0;
    for (size_t i = 0; i < G1_CHECKSUM - G1_GAME_DATA; i++)
        sum = (uint8_t)(sum + p[i]);
    return (uint8_t)~sum == *c;
}

static bool read_gen1(const uint8_t *sav, size_t len, pgb_progress *out)
{
    if (!g1_checksum_ok(sav, len))
        return false;
    const uint8_t *name = at(sav, len, G1_GAME_DATA, NAME_BYTES);
    const uint8_t *owned = at(sav, len, G1_DEX_OWNED, 19);
    const uint8_t *seen = at(sav, len, G1_DEX_SEEN, 19);
    const uint8_t *money = at(sav, len, G1_MONEY, 3);
    const uint8_t *badges = at(sav, len, G1_BADGES, 1);
    const uint8_t *time = at(sav, len, G1_PLAY_TIME, 5);
    if (!name || !owned || !seen || !money || !badges || !time)
        return false;
    if (time[2] >= 60 || time[3] >= 60)      /* minutos y segundos: TrackPlayTime nunca pasa de 59 */
        return false;
    if (!decode_name(name, false, out->player_name) || !decode_bcd3(money, &out->money))
        return false;
    out->badges_mask = badges[0];
    out->pokedex_owned = count_bits(owned, G1_DEX_BITS);
    out->pokedex_seen = count_bits(seen, G1_DEX_BITS);
    out->play_hours = time[0];
    out->play_minutes = time[2];
    out->play_seconds = time[3];
    return true;
}

/* ---- 2.ª generación ---- */

/* Checksum de pokegold/pokecrystal: suma de 16 bits de [G2_GAME_DATA, end), guardada little-endian. */
static bool g2_checksum_ok(const uint8_t *sav, size_t len, size_t end, size_t pos)
{
    const uint8_t *p = at(sav, len, G2_GAME_DATA, end - G2_GAME_DATA);
    const uint8_t *c = at(sav, len, pos, 2);
    if (!p || !c)
        return false;
    uint16_t sum = 0;
    for (size_t i = 0; i < end - G2_GAME_DATA; i++)
        sum = (uint16_t)(sum + p[i]);
    return sum == (uint16_t)(c[0] | (c[1] << 8));
}

static bool read_gen2(const uint8_t *sav, size_t len, bool crystal, pgb_progress *out)
{
    const size_t end = crystal ? C_GAME_DATA_END : GS_GAME_DATA_END;
    const size_t sum_pos = crystal ? C_CHECKSUM : GS_CHECKSUM;
    const size_t cv2 = crystal ? C_CHECK_VALUE_2 : GS_CHECK_VALUE_2;
    const size_t t_off = crystal ? C_PLAY_TIME : GS_PLAY_TIME;
    const size_t m_off = crystal ? C_MONEY : GS_MONEY;
    const size_t b_off = crystal ? C_BADGES : GS_BADGES;
    const size_t o_off = crystal ? C_DEX_OWNED : GS_DEX_OWNED;
    const size_t s_off = crystal ? C_DEX_SEEN : GS_DEX_SEEN;

    /* CheckPrimarySaveFile: los dos bytes de validación y VerifyChecksum. */
    const uint8_t *v1 = at(sav, len, G2_CHECK_VALUE_1, 1);
    const uint8_t *v2 = at(sav, len, cv2, 1);
    if (!v1 || !v2 || *v1 != G2_SAVE_CHECK_1 || *v2 != G2_SAVE_CHECK_2)
        return false;
    if (!g2_checksum_ok(sav, len, end, sum_pos))
        return false;

    const uint8_t *name = at(sav, len, G2_NAME, NAME_BYTES);
    const uint8_t *money = at(sav, len, m_off, 3);
    const uint8_t *badges = at(sav, len, b_off, 2);
    const uint8_t *owned = at(sav, len, o_off, G2_DEX_BYTES);
    const uint8_t *seen = at(sav, len, s_off, G2_DEX_BYTES);
    const uint8_t *time = at(sav, len, t_off, 5);
    if (!name || !money || !badges || !owned || !seen || !time)
        return false;
    if (time[2] >= 60 || time[3] >= 60)      /* GameTimer: minutos y segundos < 60 (tope 999:59:59) */
        return false;
    if (!decode_name(name, true, out->player_name))
        return false;
    out->money = ((uint32_t)money[0] << 16) | ((uint32_t)money[1] << 8) | money[2];
    out->badges_mask = (uint16_t)(badges[0] | (badges[1] << 8));   /* Johto, Kanto */
    out->pokedex_owned = count_bits(owned, G2_DEX_BITS);
    out->pokedex_seen = count_bits(seen, G2_DEX_BITS);
    out->play_hours = (uint16_t)((time[0] << 8) | time[1]);
    out->play_minutes = time[2];
    out->play_seconds = time[3];
    return true;
}

/* ---- Entrada pública ---- */

/* Tamaños de .sav que se aceptan: la RAM sola o con el bloque RTC al final. */
static bool save_size_ok(size_t n)
{
    return n == PGB_PROG_SAVE_BYTES || n == PGB_PROG_SAVE_BYTES + RTC_SUFFIX_A ||
           n == PGB_PROG_SAVE_BYTES + RTC_SUFFIX_B || n == PGB_PROG_SAVE_BYTES + RTC_SUFFIX_C;
}

bool pgb_progress_read(const uint8_t *rom_header, size_t header_len,
                       const uint8_t *sram, size_t sram_len, pgb_progress *out)
{
    if (!out)
        return false;
    memset(out, 0, sizeof *out);
    if (!sram || !save_size_ok(sram_len))
        return false;
    pgb_prog_game game = pgb_progress_identify(rom_header, header_len);
    bool ok = false;
    switch (game) {
    case PGB_PROG_GEN1:
        ok = read_gen1(sram, PGB_PROG_SAVE_BYTES, out);
        break;
    case PGB_PROG_GEN2_GS:
        ok = read_gen2(sram, PGB_PROG_SAVE_BYTES, false, out);
        break;
    case PGB_PROG_GEN2_C:
        ok = read_gen2(sram, PGB_PROG_SAVE_BYTES, true, out);
        break;
    case PGB_PROG_NONE:
    default:
        break;
    }
    if (!ok) {
        memset(out, 0, sizeof *out);
        return false;
    }
    out->game = game;
    out->badges_count = popcount16(out->badges_mask);
    return true;
}
