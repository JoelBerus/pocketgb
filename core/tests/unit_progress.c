/*
 * unit_progress.c — lector de progreso Pokémon (pgb_progress_read, N6-C).
 *
 * Todas las partidas son SINTÉTICAS: se construyen byte a byte en memoria, con los
 * desplazamientos de docs/03-core-spec.md §Lector de progreso Pokémon (repetidos aquí a
 * propósito para que el test sea independiente de la implementación). Nunca se leen ni se
 * guardan .sav reales (AGENTS.md, regla 1). Las comprobaciones de límites usan copias de
 * tamaño EXACTO en el montón para que ASan detecte cualquier lectura fuera de rango.
 */
#include <stdlib.h>
#include <string.h>

#include "pocketgb_progress.h"
#include "unit.h"

enum {
    SAV = PGB_PROG_SAVE_BYTES,
    SAV_BUF = SAV + 64,          /* con sitio para el bloque RTC */

    /* 1.ª generación */
    T1_START = 0x2598, T1_CKSUM = 0x3523, T1_OWNED = 0x25A3, T1_SEEN = 0x25B6, T1_MONEY = 0x25F3,
    T1_BADGES = 0x2602, T1_TIME = 0x2CED,
    /* 2.ª generación */
    T2_CV1 = 0x2008, T2_START = 0x2009, T2_NAME = 0x200B,
    TG_END = 0x2D69, TG_CV2 = 0x2D6B, TG_TIME = 0x2053, TG_MONEY = 0x23DB, TG_BADGES = 0x23E4,
    TG_OWNED = 0x2A4C, TG_SEEN = 0x2A6C,
    TC_END = 0x2B83, TC_CKSUM = 0x2D0D, TC_CV2 = 0x2D0F, TC_TIME = 0x2052, TC_MONEY = 0x23DC,
    TC_BADGES = 0x23E5, TC_OWNED = 0x2A27, TC_SEEN = 0x2A47
};

enum { G_RED, G_BLUE, G_YELLOW, G_GOLD, G_SILVER, G_CRYSTAL, G_COUNT };

struct want {
    const char *name;            /* ASCII: letras, dígitos, espacio, '-', '?' */
    unsigned owned, seen;        /* los primeros n bits */
    unsigned badges;             /* 1.ª gen: 8 bits; 2.ª: Johto | Kanto << 8 */
    unsigned hours, minutes, seconds;
    unsigned money;
};

/* ---- Cabecera ---- */

static void make_header(uint8_t h[PGB_PROG_HEADER_MIN], int game)
{
    static const char *const title[G_COUNT] = { "POKEMON RED", "POKEMON BLUE", "POKEMON YELLOW",
                                               "POKEMON_GLD", "POKEMON_SLV", "PM_CRYSTAL" };
    memset(h, 0, PGB_PROG_HEADER_MIN);
    memcpy(h + 0x134, title[game], strlen(title[game]));
    if (game >= G_GOLD)
        memcpy(h + 0x13F, game == G_CRYSTAL ? "BYTE" : "AAUE", 4);   /* código del juego */
    h[0x143] = game == G_CRYSTAL ? 0xC0 : (game >= G_GOLD || game == G_YELLOW ? 0x80 : 0x00);
    h[0x14A] = 0x01;             /* no japonés */
}

/* ---- Texto ---- */

static uint8_t enc(char c)
{
    if (c >= 'A' && c <= 'Z')
        return (uint8_t)(0x80 + (c - 'A'));
    if (c >= 'a' && c <= 'z')
        return (uint8_t)(0xA0 + (c - 'a'));
    if (c >= '0' && c <= '9')
        return (uint8_t)(0xF6 + (c - '0'));
    if (c == ' ')
        return 0x7F;
    if (c == '-')
        return 0xE3;
    return 0xE6;                 /* '?' */
}

static void put_name(uint8_t *dst, const char *s)
{
    size_t n = 0;
    memset(dst, 0, 11);
    for (; s[n] && n < 10; n++)
        dst[n] = enc(s[n]);
    dst[n] = 0x50;
}

/* ---- Constructores de partidas ---- */

static void set_bits(uint8_t *p, unsigned n)
{
    for (unsigned i = 0; i < n; i++)
        p[i >> 3] = (uint8_t)(p[i >> 3] | (1u << (i & 7)));
}

static void fix_g1(uint8_t *sav)
{
    uint8_t s = 0;
    for (size_t i = T1_START; i < T1_CKSUM; i++)
        s = (uint8_t)(s + sav[i]);
    sav[T1_CKSUM] = (uint8_t)~s;
}

static void build_g1(uint8_t *sav, const struct want *w)
{
    memset(sav, 0, SAV_BUF);
    put_name(sav + T1_START, w->name);
    set_bits(sav + T1_OWNED, w->owned);
    set_bits(sav + T1_SEEN, w->seen);
    sav[T1_MONEY] = (uint8_t)((w->money / 100000 % 10) << 4 | (w->money / 10000 % 10));
    sav[T1_MONEY + 1] = (uint8_t)((w->money / 1000 % 10) << 4 | (w->money / 100 % 10));
    sav[T1_MONEY + 2] = (uint8_t)((w->money / 10 % 10) << 4 | (w->money % 10));
    sav[T1_BADGES] = (uint8_t)w->badges;
    sav[T1_TIME] = (uint8_t)w->hours;
    sav[T1_TIME + 2] = (uint8_t)w->minutes;
    sav[T1_TIME + 3] = (uint8_t)w->seconds;
    sav[T1_TIME + 4] = 30;       /* fotogramas */
    fix_g1(sav);
}

static void fix_g2(uint8_t *sav, bool crystal)
{
    size_t end = crystal ? TC_END : TG_END, pos = crystal ? TC_CKSUM : TG_END;
    unsigned sum = 0;
    for (size_t i = T2_START; i < end; i++)
        sum = (sum + sav[i]) & 0xFFFFu;
    sav[pos] = (uint8_t)sum;
    sav[pos + 1] = (uint8_t)(sum >> 8);
}

static void build_g2(uint8_t *sav, bool crystal, const struct want *w)
{
    size_t time = crystal ? TC_TIME : TG_TIME, money = crystal ? TC_MONEY : TG_MONEY;
    size_t badges = crystal ? TC_BADGES : TG_BADGES;
    memset(sav, 0, SAV_BUF);
    sav[T2_CV1] = 99;
    sav[crystal ? TC_CV2 : TG_CV2] = 127;
    put_name(sav + T2_NAME, w->name);
    set_bits(sav + (crystal ? TC_OWNED : TG_OWNED), w->owned);
    set_bits(sav + (crystal ? TC_SEEN : TG_SEEN), w->seen);
    sav[money] = (uint8_t)(w->money >> 16);
    sav[money + 1] = (uint8_t)(w->money >> 8);
    sav[money + 2] = (uint8_t)w->money;
    sav[badges] = (uint8_t)w->badges;
    sav[badges + 1] = (uint8_t)(w->badges >> 8);
    sav[time] = (uint8_t)(w->hours >> 8);
    sav[time + 1] = (uint8_t)w->hours;
    sav[time + 2] = (uint8_t)w->minutes;
    sav[time + 3] = (uint8_t)w->seconds;
    sav[time + 4] = 12;
    fix_g2(sav, crystal);
}

static void build(uint8_t *sav, int game, const struct want *w)
{
    if (game <= G_YELLOW)
        build_g1(sav, w);
    else
        build_g2(sav, game == G_CRYSTAL, w);
}

static void fix(uint8_t *sav, int game)
{
    if (game <= G_YELLOW)
        fix_g1(sav);
    else
        fix_g2(sav, game == G_CRYSTAL);
}

/* Copia de tamaño exacto en el montón: ASan marca como fuera de rango el byte n. */
static uint8_t *exact_copy(const uint8_t *src, size_t n)
{
    uint8_t *p = malloc(n ? n : 1);
    if (p && n)
        memcpy(p, src, n);
    return p;
}

static bool valid_utf8(const char *s)
{
    const uint8_t *p = (const uint8_t *)s;
    while (*p) {
        size_t extra = *p < 0x80 ? 0 : (*p & 0xE0) == 0xC0 ? 1 : (*p & 0xF0) == 0xE0 ? 2 : 99;
        if (extra == 99)
            return false;
        p++;
        for (size_t i = 0; i < extra; i++, p++)
            if ((*p & 0xC0) != 0x80)
                return false;
    }
    return true;
}

static bool is_zeroed(const pgb_progress *p)
{
    pgb_progress z;
    memset(&z, 0, sizeof z);
    return memcmp(p, &z, sizeof z) == 0;
}

/* Amarillo europeo: «POKEMON YELAPS» + letra de idioma (0x13F–0x142 = código «APS?»), 0x143 = 0x80. */
static void make_header_yellow_eu(uint8_t h[PGB_PROG_HEADER_MIN], char lang)
{
    make_header(h, G_YELLOW);
    memcpy(h + 0x134, "POKEMON YELAPS", 14);
    h[0x142] = (uint8_t)lang;
}

static bool read_ok(int game, const uint8_t *sav, size_t len, pgb_progress *out)
{
    uint8_t h[PGB_PROG_HEADER_MIN];
    make_header(h, game);
    return pgb_progress_read(h, sizeof h, sav, len, out);
}

/* ---- Pruebas ---- */

static void test_identify(struct ut *t)
{
    static const pgb_prog_game want[G_COUNT] = { PGB_PROG_GEN1, PGB_PROG_GEN1, PGB_PROG_GEN1,
                                                 PGB_PROG_GEN2_GS, PGB_PROG_GEN2_GS, PGB_PROG_GEN2_C };
    uint8_t h[PGB_PROG_HEADER_MIN];
    for (int g = 0; g < G_COUNT; g++) {
        make_header(h, g);
        CHECK(t, pgb_progress_identify(h, sizeof h) == want[g]);
        CHECK(t, pgb_progress_identify(h, sizeof h + 100) == want[g]);   /* admite más bytes */
        CHECK(t, pgb_progress_identify(h, sizeof h - 1) == PGB_PROG_NONE);
        h[0x14A] = 0x00;                                                  /* edición japonesa */
        CHECK(t, pgb_progress_identify(h, sizeof h) == PGB_PROG_NONE);
    }
    CHECK(t, pgb_progress_identify(NULL, 0x150) == PGB_PROG_NONE);

    make_header(h, G_RED);
    h[0x134 + 11] = 'X';                                                  /* «POKEMON REDX» */
    CHECK(t, pgb_progress_identify(h, sizeof h) == PGB_PROG_NONE);
    make_header(h, G_CRYSTAL);
    h[0x134 + 10] = 'X';                                                  /* «PM_CRYSTALX» */
    CHECK(t, pgb_progress_identify(h, sizeof h) == PGB_PROG_NONE);
    make_header(h, G_RED);
    memcpy(h + 0x134, "POKEMON GREEN", 14);
    CHECK(t, pgb_progress_identify(h, sizeof h) == PGB_PROG_NONE);
    make_header(h, G_RED);
    memcpy(h + 0x134, "TETRIS", 7);
    CHECK(t, pgb_progress_identify(h, sizeof h) == PGB_PROG_NONE);
    make_header(h, G_RED);
    memcpy(h + 0x134, "pokemon red", 12);                                  /* distingue mayúsculas */
    CHECK(t, pgb_progress_identify(h, sizeof h) == PGB_PROG_NONE);

    /* H1: Amarillo de las ediciones europeas (ES/FR/DE/IT): «POKEMON YELAPS» + D, F, I o S en 0x142. */
    static const char eu_langs[] = "DFIS";
    for (size_t i = 0; i < sizeof eu_langs - 1; i++) {
        make_header_yellow_eu(h, eu_langs[i]);
        CHECK(t, pgb_progress_identify(h, sizeof h) == PGB_PROG_GEN1);
        CHECK(t, pgb_progress_identify(h, sizeof h - 1) == PGB_PROG_NONE);
        h[0x14A] = 0x00;                                                  /* destino japonés */
        CHECK(t, pgb_progress_identify(h, sizeof h) == PGB_PROG_NONE);
    }
    /* Otras letras (inglés europeo no lleva sufijo; J = Japón; K = Corea), el título sin letra y variantes. */
    static const uint8_t not_eu[] = { 'E', 'J', 'K', 'U', 'X', 'd', ' ', 0x00, 0xFF };
    for (size_t i = 0; i < sizeof not_eu; i++) {
        make_header_yellow_eu(h, (char)not_eu[i]);
        CHECK(t, pgb_progress_identify(h, sizeof h) == PGB_PROG_NONE);
    }
    make_header_yellow_eu(h, 'D');
    h[0x134 + 13] = 'T';                                                  /* «POKEMON YELAPT» */
    CHECK(t, pgb_progress_identify(h, sizeof h) == PGB_PROG_NONE);
    make_header(h, G_YELLOW);
    h[0x142] = 'D';                                                       /* «POKEMON YELLOW» + D: sin terminador */
    CHECK(t, pgb_progress_identify(h, sizeof h) == PGB_PROG_NONE);

    /* Oro/Plata/Cristal: el último carácter del código del juego es el idioma (0x142). Se aceptan
     * los internacionales y se rechaza el coreano (K: «AAUK», «AAXK»). */
    static const char gs_langs[] = "EDFISU";
    for (int g = G_GOLD; g <= G_CRYSTAL; g++) {
        for (size_t i = 0; i < sizeof gs_langs - 1; i++) {
            make_header(h, g);
            h[0x142] = (uint8_t)gs_langs[i];
            CHECK(t, pgb_progress_identify(h, sizeof h) == (g == G_CRYSTAL ? PGB_PROG_GEN2_C : PGB_PROG_GEN2_GS));
        }
        make_header(h, g);
        h[0x142] = 'K';
        CHECK(t, pgb_progress_identify(h, sizeof h) == PGB_PROG_NONE);
    }
}

static void check_fields(struct ut *t, const pgb_progress *p, pgb_prog_game game, const char *name,
                         const struct want *w, unsigned badge_mask, unsigned badge_count)
{
    CHECK(t, p->game == game);
    CHECK(t, strcmp(p->player_name, name) == 0);
    CHECK(t, p->badges_mask == badge_mask);
    CHECK(t, p->badges_count == badge_count);
    CHECK(t, p->pokedex_owned == w->owned);
    CHECK(t, p->pokedex_seen == w->seen);
    CHECK(t, p->play_hours == w->hours);
    CHECK(t, p->play_minutes == w->minutes);
    CHECK(t, p->play_seconds == w->seconds);
    CHECK(t, p->money == w->money);
}

static void test_gen1(struct ut *t)
{
    static const struct want w = { "ASH", 151, 100, 0xA5, 100, 59, 7, 123456 };
    uint8_t sav[SAV_BUF];
    pgb_progress p;
    for (int g = G_RED; g <= G_YELLOW; g++) {
        build_g1(sav, &w);
        memset(&p, 0xAA, sizeof p);
        CHECK(t, read_ok(g, sav, SAV, &p));
        check_fields(t, &p, PGB_PROG_GEN1, "ASH", &w, 0xA5, 4);
    }

    /* H1: Amarillo europeo (cabecera «POKEMON YELAPS?»): misma disposición, se lee igual. */
    build_g1(sav, &w);
    for (const char *l = "DFIS"; *l; l++) {
        uint8_t eh[PGB_PROG_HEADER_MIN];
        make_header_yellow_eu(eh, *l);
        memset(&p, 0xAA, sizeof p);
        CHECK(t, pgb_progress_read(eh, sizeof eh, sav, SAV, &p));
        check_fields(t, &p, PGB_PROG_GEN1, "ASH", &w, 0xA5, 4);
    }

    /* Una sola medalla por bit (Roca=bit 0 … Tierra=bit 7) y 8 medallas. */
    for (unsigned b = 0; b < 8; b++) {
        struct want x = w;
        x.badges = 1u << b;
        build_g1(sav, &x);
        CHECK(t, read_ok(G_RED, sav, SAV, &p) && p.badges_mask == (1u << b) && p.badges_count == 1);
    }
    { struct want x = w; x.badges = 0xFF; build_g1(sav, &x);
      CHECK(t, read_ok(G_BLUE, sav, SAV, &p) && p.badges_count == 8); }

    /* Partida recién empezada y valores límite. */
    { struct want x = { "", 0, 0, 0, 0, 0, 0, 0 }; build_g1(sav, &x);
      CHECK(t, read_ok(G_RED, sav, SAV, &p) && p.player_name[0] == '\0' && p.money == 0 &&
               p.pokedex_owned == 0 && p.badges_count == 0); }
    { struct want x = { "MAX", 151, 151, 0xFF, 255, 59, 59, 999999 }; build_g1(sav, &x);
      sav[T1_TIME + 1] = 0xFF;   /* «tiempo máximo alcanzado» (TrackPlayTime) */
      fix_g1(sav);
      CHECK(t, read_ok(G_YELLOW, sav, SAV, &p));
      check_fields(t, &p, PGB_PROG_GEN1, "MAX", &x, 0xFF, 8); }

    /* El bit 151 (relleno del último byte de la Pokédex) no cuenta. */
    build_g1(sav, &w);
    sav[T1_OWNED + 18] = (uint8_t)(sav[T1_OWNED + 18] | 0x80);
    sav[T1_SEEN + 18] = (uint8_t)(sav[T1_SEEN + 18] | 0x80);
    fix_g1(sav);
    CHECK(t, read_ok(G_RED, sav, SAV, &p) && p.pokedex_owned == 151 && p.pokedex_seen == 100);

    /* Rango exacto del checksum: [0x2598, 0x3523). Fuera de él no importa. */
    build_g1(sav, &w);
    sav[T1_START - 1] ^= 0xFF;
    sav[T1_CKSUM + 1] ^= 0xFF;
    sav[SAV - 1] ^= 0xFF;
    CHECK(t, read_ok(G_RED, sav, SAV, &p));
    static const size_t inside[] = { T1_START, T1_OWNED, 0x3000, T1_CKSUM - 1, T1_TIME };
    for (size_t i = 0; i < sizeof inside / sizeof inside[0]; i++) {
        build_g1(sav, &w);
        sav[inside[i]] ^= 0x01;
        CHECK(t, !read_ok(G_RED, sav, SAV, &p));
        CHECK(t, is_zeroed(&p));
    }
    build_g1(sav, &w);
    sav[T1_CKSUM] ^= 0x01;
    CHECK(t, !read_ok(G_RED, sav, SAV, &p) && is_zeroed(&p));

    /* Incoherencias con checksum correcto: BCD, minutos, segundos, nombre sin terminar. */
    build_g1(sav, &w); sav[T1_MONEY + 1] = 0x1A; fix_g1(sav);
    CHECK(t, !read_ok(G_RED, sav, SAV, &p) && is_zeroed(&p));
    build_g1(sav, &w); sav[T1_MONEY] = 0xA0; fix_g1(sav);
    CHECK(t, !read_ok(G_RED, sav, SAV, &p));
    build_g1(sav, &w); sav[T1_TIME + 2] = 60; fix_g1(sav);
    CHECK(t, !read_ok(G_RED, sav, SAV, &p));
    build_g1(sav, &w); sav[T1_TIME + 3] = 60; fix_g1(sav);
    CHECK(t, !read_ok(G_RED, sav, SAV, &p));
    build_g1(sav, &w); memset(sav + T1_START, 0x80, 11); fix_g1(sav);
    CHECK(t, !read_ok(G_RED, sav, SAV, &p));

    /* Una partida de la 2.ª generación no pasa por la 1.ª (ni al revés). */
    static const struct want w2 = { "GOLD", 10, 20, 0x0101, 5, 6, 7, 3000 };
    build_g2(sav, false, &w2);
    CHECK(t, !read_ok(G_RED, sav, SAV, &p));
    build_g1(sav, &w);
    CHECK(t, !read_ok(G_GOLD, sav, SAV, &p) && !read_ok(G_CRYSTAL, sav, SAV, &p));
}

static void test_gen2(struct ut *t)
{
    static const struct want w = { "KRIS", 251, 200, 0x0FFF, 123, 45, 6, 999999 };
    uint8_t sav[SAV_BUF];
    pgb_progress p;
    static const struct { int game; bool crystal; pgb_prog_game id; } cases[] = {
        { G_GOLD, false, PGB_PROG_GEN2_GS }, { G_SILVER, false, PGB_PROG_GEN2_GS },
        { G_CRYSTAL, true, PGB_PROG_GEN2_C }
    };
    for (size_t i = 0; i < sizeof cases / sizeof cases[0]; i++) {
        build_g2(sav, cases[i].crystal, &w);
        memset(&p, 0xAA, sizeof p);
        CHECK(t, read_ok(cases[i].game, sav, SAV, &p));
        check_fields(t, &p, cases[i].id, "KRIS", &w, 0x0FFF, 12);

        /* Medallas: Johto en los bits 0-7, Kanto en 8-15. */
        struct want x = w;
        x.badges = 0xFF00 | 0x01;
        build_g2(sav, cases[i].crystal, &x);
        CHECK(t, read_ok(cases[i].game, sav, SAV, &p) && p.badges_mask == 0xFF01 && p.badges_count == 9);

        /* Horas big-endian de 16 bits, tope del juego 999:59:59 y partida nueva. */
        x = w;
        x.hours = 0x0102;
        build_g2(sav, cases[i].crystal, &x);
        CHECK(t, read_ok(cases[i].game, sav, SAV, &p) && p.play_hours == 0x0102);
        x.hours = 999; x.minutes = 59; x.seconds = 59;
        build_g2(sav, cases[i].crystal, &x);
        CHECK(t, read_ok(cases[i].game, sav, SAV, &p) && p.play_hours == 999 && p.play_minutes == 59 &&
                 p.play_seconds == 59);
        /* H5: horas > 999 y dinero > 999999 no los escribe el juego (tope de 999:59:59 y MAX_MONEY). */
        static const unsigned bad_hours[] = { 1000, 1023, 0x0400, 0x7FFF, 0xFFFF };
        for (size_t k = 0; k < sizeof bad_hours / sizeof bad_hours[0]; k++) {
            x = w;
            x.hours = bad_hours[k];
            build_g2(sav, cases[i].crystal, &x);
            CHECK(t, !read_ok(cases[i].game, sav, SAV, &p) && is_zeroed(&p));
        }
        static const unsigned bad_money[] = { 1000000, 1000001, 0x0F4240, 0x800000, 0xFFFFFF };
        for (size_t k = 0; k < sizeof bad_money / sizeof bad_money[0]; k++) {
            x = w;
            x.money = bad_money[k];
            build_g2(sav, cases[i].crystal, &x);
            CHECK(t, !read_ok(cases[i].game, sav, SAV, &p) && is_zeroed(&p));
        }
        x = w; x.hours = 0; x.money = 999999;                              /* los máximos sí valen */
        build_g2(sav, cases[i].crystal, &x);
        CHECK(t, read_ok(cases[i].game, sav, SAV, &p) && p.money == 999999);
        { struct want n = { "", 0, 0, 0, 0, 0, 0, 0 };
          build_g2(sav, cases[i].crystal, &n);
          CHECK(t, read_ok(cases[i].game, sav, SAV, &p) && p.money == 0 && p.pokedex_owned == 0); }

        /* Dinero de 3 bytes big-endian (no BCD): 0x0F423F = 999999; el bit 251..255 de la Pokédex no cuenta. */
        build_g2(sav, cases[i].crystal, &w);
        CHECK(t, sav[cases[i].crystal ? TC_MONEY : TG_MONEY] == 0x0F &&
                 sav[(cases[i].crystal ? TC_MONEY : TG_MONEY) + 1] == 0x42 &&
                 sav[(cases[i].crystal ? TC_MONEY : TG_MONEY) + 2] == 0x3F);
        size_t o = cases[i].crystal ? TC_OWNED : TG_OWNED;
        sav[o + 31] = (uint8_t)(sav[o + 31] | 0xF8);                       /* bits 251..255 */
        size_t sn = o + (cases[i].crystal ? TC_SEEN - TC_OWNED : TG_SEEN - TG_OWNED);
        sav[sn + 31] = (uint8_t)(sav[sn + 31] | 0xF8);
        fix(sav, cases[i].game);
        CHECK(t, read_ok(cases[i].game, sav, SAV, &p) && p.pokedex_owned == 251 && p.pokedex_seen == 200);

        /* Nombre: acentos de la tabla de la 2.ª generación. */
        build_g2(sav, cases[i].crystal, &w);
        static const uint8_t accents[] = { 0xC0, 0xC4, 0xEA, 0xE9, 0x01, 0x50 };
        memcpy(sav + T2_NAME, accents, sizeof accents);
        fix(sav, cases[i].game);
        CHECK(t, read_ok(cases[i].game, sav, SAV, &p) && strcmp(p.player_name, "\xC3\x84\xC3\xB6\xC3\xA9&?") == 0);

        /* Bytes de validación y rango del checksum. */
        build_g2(sav, cases[i].crystal, &w);
        sav[T2_CV1] = 98;
        CHECK(t, !read_ok(cases[i].game, sav, SAV, &p) && is_zeroed(&p));
        build_g2(sav, cases[i].crystal, &w);
        sav[cases[i].crystal ? TC_CV2 : TG_CV2] = 126;
        CHECK(t, !read_ok(cases[i].game, sav, SAV, &p));
        size_t end = cases[i].crystal ? TC_END : TG_END, ck = cases[i].crystal ? TC_CKSUM : TG_END;
        size_t flip[] = { T2_START, T2_NAME, end - 1, ck, ck + 1 };
        for (size_t k = 0; k < sizeof flip / sizeof flip[0]; k++) {
            build_g2(sav, cases[i].crystal, &w);
            sav[flip[k]] ^= 0x10;
            CHECK(t, !read_ok(cases[i].game, sav, SAV, &p));
        }
        /* Fuera del rango no importa: tras el final (Cristal: relleno de 0x18A bytes), tras el
         * checksum y el último byte de la RAM. */
        build_g2(sav, cases[i].crystal, &w);
        sav[cases[i].crystal ? TC_END : TG_END + 3] ^= 0xFF;
        sav[cases[i].crystal ? TC_CKSUM - 1 : TG_END + 4] ^= 0xFF;
        sav[SAV - 1] ^= 0xFF;
        CHECK(t, read_ok(cases[i].game, sav, SAV, &p));

        /* Incoherencias con checksum correcto. */
        build_g2(sav, cases[i].crystal, &w);
        memset(sav + T2_NAME, 0x80, 11);                                   /* sin terminador */
        fix(sav, cases[i].game);
        CHECK(t, !read_ok(cases[i].game, sav, SAV, &p));
        build_g2(sav, cases[i].crystal, &w);
        sav[(cases[i].crystal ? TC_TIME : TG_TIME) + 2] = 61;
        fix(sav, cases[i].game);
        CHECK(t, !read_ok(cases[i].game, sav, SAV, &p));
        build_g2(sav, cases[i].crystal, &w);
        sav[(cases[i].crystal ? TC_TIME : TG_TIME) + 3] = 60;
        fix(sav, cases[i].game);
        CHECK(t, !read_ok(cases[i].game, sav, SAV, &p));
    }

    /* Oro/Plata y Cristal tienen disposiciones distintas: no se confunden. */
    build_g2(sav, false, &w);
    CHECK(t, !read_ok(G_CRYSTAL, sav, SAV, &p) && is_zeroed(&p));
    build_g2(sav, true, &w);
    CHECK(t, !read_ok(G_GOLD, sav, SAV, &p) && !read_ok(G_SILVER, sav, SAV, &p));
}

static void test_names(struct ut *t)
{
    static const struct want w = { "X", 1, 1, 0, 1, 1, 1, 1 };
    uint8_t sav[SAV_BUF];
    pgb_progress p;

    /* 1.ª generación: 0xBA (é en inglés, à en DE/FR/ES/IT: ambiguo → «?»), ♂ (0xEF), ♀ (0xF5), signos,
     * dígitos y un glifo desconocido (0x01). */
    build_g1(sav, &w);
    static const uint8_t raw1[] = { 0x80, 0xBA, 0xEF, 0xF5, 0x01, 0xF6, 0xFF, 0x7F, 0xE3, 0xE6, 0x50 };
    memcpy(sav + T1_START, raw1, sizeof raw1);
    fix_g1(sav);
    CHECK(t, read_ok(G_RED, sav, SAV, &p) &&
             strcmp(p.player_name, "A?\xE2\x99\x82\xE2\x99\x80?09 -?") == 0);
    /* 0xE9 es katakana en la 1.ª generación (desconocido) y 0xBA no es é en la 2.ª. */
    build_g1(sav, &w);
    sav[T1_START] = 0xE9;
    sav[T1_START + 1] = 0x50;
    fix_g1(sav);
    CHECK(t, read_ok(G_BLUE, sav, SAV, &p) && strcmp(p.player_name, "?") == 0);
    build_g2(sav, true, &w);
    sav[T2_NAME] = 0xBA;
    sav[T2_NAME + 1] = 0x50;
    fix_g2(sav, true);
    CHECK(t, read_ok(G_CRYSTAL, sav, SAV, &p) && strcmp(p.player_name, "?") == 0);

    /* H4: ÄÖÜäöü (0xC0–0xC5) se teclean en las ediciones alemana e italiana y valen igual en DE/FR/ES/IT;
     * en inglés no tienen glifo. Se leen en la 1.ª y en la 2.ª generación. */
    static const uint8_t umlauts[] = { 0xC0, 0xC1, 0xC2, 0xC3, 0xC4, 0xC5, 0x50 };
    build_g1(sav, &w);
    memcpy(sav + T1_START, umlauts, sizeof umlauts);
    fix_g1(sav);
    CHECK(t, read_ok(G_RED, sav, SAV, &p) &&
             strcmp(p.player_name, "\xC3\x84\xC3\x96\xC3\x9C\xC3\xA4\xC3\xB6\xC3\xBC") == 0);
    build_g2(sav, true, &w);
    memcpy(sav + T2_NAME, umlauts, sizeof umlauts);
    fix_g2(sav, true);
    CHECK(t, read_ok(G_CRYSTAL, sav, SAV, &p) &&
             strcmp(p.player_name, "\xC3\x84\xC3\x96\xC3\x9C\xC3\xA4\xC3\xB6\xC3\xBC") == 0);

    /* H3: <PK> (0xE1) y <MN> (0xE2) están en el teclado del nombre de las dos generaciones. */
    static const uint8_t pkmn[] = { 0xE1, 0xE2, 0x8F, 0xE1, 0xE2, 0x50 };      /* PK MN P PK MN */
    build_g1(sav, &w);
    memcpy(sav + T1_START, pkmn, sizeof pkmn);
    fix_g1(sav);
    CHECK(t, read_ok(G_BLUE, sav, SAV, &p) && strcmp(p.player_name, "PKMNPPKMN") == 0);
    build_g2(sav, false, &w);
    memcpy(sav + T2_NAME, pkmn, sizeof pkmn);
    fix_g2(sav, false);
    CHECK(t, read_ok(G_SILVER, sav, SAV, &p) && strcmp(p.player_name, "PKMNPPKMN") == 0);
    /* Diez <PK>: 20 bytes, caben. */
    build_g1(sav, &w);
    memset(sav + T1_START, 0xE1, 10);
    sav[T1_START + 10] = 0x50;
    fix_g1(sav);
    CHECK(t, read_ok(G_RED, sav, SAV, &p) && strlen(p.player_name) == 20);

    /* Nombre de 10 glifos de 3 bytes: cabe con su NUL en PGB_PROG_NAME_MAX. */
    build_g1(sav, &w);
    memset(sav + T1_START, 0xEF, 10);
    sav[T1_START + 10] = 0x50;
    fix_g1(sav);
    CHECK(t, read_ok(G_RED, sav, SAV, &p) && strlen(p.player_name) == 30 && valid_utf8(p.player_name));

    /* Ningún byte inicial produce UTF-8 inválido ni desborda, en las dos generaciones. */
    for (unsigned b = 0; b < 256; b++) {
        if (b == 0x50)
            continue;
        build_g1(sav, &w);
        sav[T1_START] = (uint8_t)b;
        sav[T1_START + 1] = 0x50;
        fix_g1(sav);
        CHECK(t, read_ok(G_RED, sav, SAV, &p) && valid_utf8(p.player_name) && strlen(p.player_name) <= 3 &&
                 strlen(p.player_name) >= 1);
        build_g2(sav, false, &w);
        sav[T2_NAME] = (uint8_t)b;
        sav[T2_NAME + 1] = 0x50;
        fix_g2(sav, false);
        CHECK(t, read_ok(G_GOLD, sav, SAV, &p) && valid_utf8(p.player_name) && strlen(p.player_name) <= 3 &&
                 strlen(p.player_name) >= 1);
    }
}

static void test_sizes_and_bounds(struct ut *t)
{
    static const struct want w = { "RED", 12, 34, 0x13, 1, 2, 3, 4567 };
    uint8_t sav[SAV_BUF];
    uint8_t h[PGB_PROG_HEADER_MIN];
    pgb_progress p;

    for (int g = 0; g < G_COUNT; g++) {
        build(sav, g, &w);
        make_header(h, g);

        /* Longitudes admitidas: 32 KiB y 32 KiB + RTC de 16, 44 o 48 bytes (se ignora el bloque). */
        static const size_t good[] = { SAV, SAV + 16, SAV + 44, SAV + 48 };
        for (size_t i = 0; i < sizeof good / sizeof good[0]; i++) {
            memset(sav + SAV, 0xFF, SAV_BUF - SAV);
            uint8_t *copy = exact_copy(sav, good[i]);
            CHECK(t, copy != NULL);
            if (copy) {
                CHECK(t, pgb_progress_read(h, sizeof h, copy, good[i], &p) && strcmp(p.player_name, "RED") == 0);
                free(copy);
            }
        }

        /* Cualquier otra longitud (incluidas las cortas, que no deben leer fuera) → false. */
        static const size_t bad[] = { 0, 1, 0x2597, 0x2598, 0x2D68, 0x2D69, 0x3522, 0x3523, 0x7FFF,
                                      SAV + 1, SAV + 15, SAV + 17, SAV + 43, SAV + 45, SAV + 47,
                                      SAV + 49, 2 * SAV };
        for (size_t i = 0; i < sizeof bad / sizeof bad[0]; i++) {
            uint8_t *big = calloc(1, bad[i] > SAV_BUF ? bad[i] : SAV_BUF);
            CHECK(t, big != NULL);
            if (!big)
                continue;
            memcpy(big, sav, SAV);
            uint8_t *copy = exact_copy(big, bad[i]);
            CHECK(t, copy != NULL);
            if (copy) {
                memset(&p, 0xAA, sizeof p);
                CHECK(t, !pgb_progress_read(h, sizeof h, copy, bad[i], &p));
                CHECK(t, is_zeroed(&p));
                free(copy);
            }
            free(big);
        }

        /* Cabecera de tamaño exacto: 0x14F no basta, 0x150 sí. */
        uint8_t *hh = exact_copy(h, sizeof h);
        CHECK(t, hh != NULL);
        if (hh) {
            CHECK(t, pgb_progress_read(hh, sizeof h, sav, SAV, &p));
            for (size_t n = 0; n < sizeof h; n++) {
                uint8_t *shorter = exact_copy(h, n);
                CHECK(t, shorter != NULL);
                if (shorter) {
                    CHECK(t, !pgb_progress_read(shorter, n, sav, SAV, &p) && is_zeroed(&p));
                    CHECK(t, pgb_progress_identify(shorter, n) == PGB_PROG_NONE);
                    free(shorter);
                }
            }
            free(hh);
        }

        /* Punteros nulos. */
        CHECK(t, !pgb_progress_read(NULL, 0, sav, SAV, &p));
        CHECK(t, !pgb_progress_read(h, sizeof h, NULL, SAV, &p) && is_zeroed(&p));
        CHECK(t, !pgb_progress_read(h, sizeof h, sav, SAV, NULL));
    }
}

static void test_unsupported(struct ut *t)
{
    static const struct want w = { "RED", 12, 34, 0x13, 1, 2, 3, 4567 };
    uint8_t sav[SAV_BUF];
    uint8_t h[PGB_PROG_HEADER_MIN];
    pgb_progress p;

    /* Ediciones japonesas (destino 0) con una partida válida de la disposición internacional. */
    for (int g = 0; g < G_COUNT; g++) {
        build(sav, g, &w);
        make_header(h, g);
        h[0x14A] = 0;
        CHECK(t, !pgb_progress_read(h, sizeof h, sav, SAV, &p) && is_zeroed(&p));
        h[0x14A] = 2;
        CHECK(t, !pgb_progress_read(h, sizeof h, sav, SAV, &p));
    }

    /* Corea: «AAUK»/«AAXK» (Oro/Plata coreano, destino 1 y título internacional) con una partida
     * que cuadra con la disposición internacional: se rechaza por el idioma, no solo por el checksum. */
    for (int g = G_GOLD; g <= G_CRYSTAL; g++) {
        build(sav, g, &w);
        make_header(h, g);
        CHECK(t, pgb_progress_read(h, sizeof h, sav, SAV, &p));
        h[0x142] = 'K';
        CHECK(t, !pgb_progress_read(h, sizeof h, sav, SAV, &p) && is_zeroed(&p));
    }

    /* Otros títulos (Pokémon Verde, Pinball, un hack con el título de Cristal y otra disposición). */
    build_g1(sav, &w);
    make_header(h, G_RED);
    memset(h + 0x134, 0, 16);
    memcpy(h + 0x134, "POKEMON GREEN", 13);
    CHECK(t, !pgb_progress_read(h, sizeof h, sav, SAV, &p));
    memset(h + 0x134, 0, 16);
    memcpy(h + 0x134, "POKEPINBALL", 11);
    CHECK(t, !pgb_progress_read(h, sizeof h, sav, SAV, &p));

    /* Hack con título de Cristal y datos que no siguen la disposición oficial: checksum no cuadra. */
    uint32_t x = 12345;
    for (size_t i = 0; i < SAV; i++) {
        x = x * 1664525u + 1013904223u;
        sav[i] = (uint8_t)(x >> 24);
    }
    for (int g = 0; g < G_COUNT; g++) {
        make_header(h, g);
        CHECK(t, !pgb_progress_read(h, sizeof h, sav, SAV, &p) && is_zeroed(&p));
    }

    /* Partidas vacías: borradas a 0x00 o a 0xFF (cartucho sin usar). */
    for (int fill = 0; fill < 2; fill++) {
        memset(sav, fill ? 0xFF : 0x00, SAV_BUF);
        for (int g = 0; g < G_COUNT; g++) {
            make_header(h, g);
            CHECK(t, !pgb_progress_read(h, sizeof h, sav, SAV, &p) && is_zeroed(&p));
        }
    }
}

static void test_determinism(struct ut *t)
{
    static const struct want w = { "JOEL", 77, 90, 0x3C, 12, 34, 56, 78901 };
    uint8_t sav[SAV_BUF];
    pgb_progress a, b;
    for (int g = 0; g < G_COUNT; g++) {
        build(sav, g, &w);
        memset(&a, 0x11, sizeof a);
        memset(&b, 0xEE, sizeof b);
        CHECK(t, read_ok(g, sav, SAV, &a) && read_ok(g, sav, SAV, &b));
        CHECK(t, memcmp(&a, &b, sizeof a) == 0);          /* incluidos el relleno y la cola del nombre */
        CHECK(t, strlen(a.player_name) == 4 && a.player_name[4] == '\0' && a.player_name[31] == '\0');
    }
}

void unit_progress(struct ut *t)
{
    test_identify(t);
    test_gen1(t);
    test_gen2(t);
    test_names(t);
    test_sizes_and_bounds(t);
    test_unsupported(t);
    test_determinism(t);
}

/* Semillas de fuzz_progress (gbtest --fuzz-seeds): [juego][indicadores][partida de 32 KiB].
 * `which` 0..UT_PROGRESS_SEEDS-1 = Rojo, Azul, Amarillo, Oro, Plata, Cristal y Amarillo alemán con
 * valores no triviales. Indicadores 0xC7 = checksum + validación + rangos + nombre + longitud 0x8000 (ver
 * fuzz/fuzz_progress.c). Devuelve la longitud escrita, o 0 si no cabe. */
size_t ut_progress_seed(unsigned which, uint8_t *out, size_t cap)
{
    static const struct want w = { "SEED", 120, 140, 0x2D, 50, 21, 9, 23456 };
    uint8_t sav[SAV_BUF];
    if (which >= UT_PROGRESS_SEEDS || cap < 2 + SAV)
        return 0;
    build(sav, which == 6 ? G_YELLOW : (int)which, &w);          /* 6 = Amarillo europeo (alemán) */
    out[0] = (uint8_t)which;
    out[1] = 0xC7;
    memcpy(out + 2, sav, SAV);
    return 2 + SAV;
}
