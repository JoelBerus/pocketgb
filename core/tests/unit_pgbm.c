/*
 * unit_pgbm.c — contenedor `.pgbm` (pgbm_parse / pgbm_encode / pgbm_encoded_size, N7-C).
 *
 * Todo es SINTÉTICO: las cargas son bytes pseudoaleatorios deterministas (xorshift32) o la
 * fórmula de relleno de docs/12-formato-pgbm.md; nunca se leen ni se guardan partidas ni
 * estados reales (AGENTS.md, regla 1). Las comprobaciones de límites copian el paquete a un
 * bloque del montón de tamaño EXACTO para que ASan detecte cualquier lectura fuera de rango.
 * Los paquetes defectuosos se construyen byte a byte con `raw_build` (con CRC correcto, para
 * llegar a cada comprobación de estructura sin que el CRC la tape).
 */
#include <stdlib.h>
#include <string.h>

#include "pocketgb_pgbm.h"
#include "unit.h"

enum { HDR = 12, CRCB = 4, SHDR = 8, ROMF_LEN = PGBM_ROMF_BYTES };

/* ---- Utilidades ---- */

static uint32_t rng_next(uint32_t *s)
{
    uint32_t x = *s;
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    return *s = x;
}

static void rng_fill(uint8_t *p, size_t n, uint32_t *s)
{
    for (size_t i = 0; i < n; i++)
        p[i] = (uint8_t)(rng_next(s) >> 11);
}

/* Relleno determinista de los vectores dorados (docs/12-formato-pgbm.md §Vectores dorados). */
static uint8_t pat(unsigned seed, size_t i)
{
    return (uint8_t)(seed + i * 37u + (i >> 8) * 11u);
}

static void pat_fill(uint8_t *p, size_t n, unsigned seed)
{
    for (size_t i = 0; i < n; i++)
        p[i] = pat(seed, i);
}

static void le16(uint8_t *p, uint32_t v)
{
    p[0] = (uint8_t)v;
    p[1] = (uint8_t)(v >> 8);
}

static void le32(uint8_t *p, uint32_t v)
{
    p[0] = (uint8_t)v;
    p[1] = (uint8_t)(v >> 8);
    p[2] = (uint8_t)(v >> 16);
    p[3] = (uint8_t)(v >> 24);
}

static uint8_t *dup_exact(const uint8_t *src, size_t n)
{
    uint8_t *p = malloc(n ? n : 1);
    if (p && n)
        memcpy(p, src, n);
    return p;
}

static void hex_of(const uint8_t d[32], char out[65])
{
    for (int i = 0; i < 32; i++)
        snprintf(out + 2 * i, 3, "%02x", d[i]);
}

static bool sha_is(const uint8_t *data, size_t n, const char *hex)
{
    uint8_t d[32];
    char h[65];
    sha256(data, n, d);
    hex_of(d, h);
    return strcmp(h, hex) == 0;
}

static bool span_is(pgbm_span s, const uint8_t *b, size_t n)
{
    return s.len == n && (n == 0 || (s.data && memcmp(s.data, b, n) == 0));
}

static bool view_zero(const pgbm_view *v)
{
    pgbm_view z;
    memset(&z, 0, sizeof z);
    return memcmp(v, &z, sizeof z) == 0;
}

/* Analiza una copia de tamaño exacto; el resultado de los spans NO se puede usar después. */
static pgbm_result parse_copy(const uint8_t *b, size_t n, pgbm_view *v)
{
    uint8_t *c = dup_exact(b, n);
    pgbm_result r = c ? pgbm_parse(c, n, v) : PGBM_ERR_ARG;
    free(c);
    return r;
}

static pgbm_result parse_code(const uint8_t *b, size_t n)
{
    pgbm_view v;
    return parse_copy(b, n, &v);
}

/* Aleatorio válido: texto UTF-8 de exactamente n bytes (sin NUL). */
static void rand_utf8(uint8_t *p, size_t n, uint32_t *s)
{
    size_t i = 0;
    while (i < n) {
        size_t room = n - i;
        uint32_t r = rng_next(s) % 8;
        if (r == 0 && room >= 4) {                  /* U+1F600..U+1F63F */
            p[i++] = 0xF0; p[i++] = 0x9F; p[i++] = 0x98; p[i++] = (uint8_t)(0x80 + rng_next(s) % 0x40);
        } else if (r <= 2 && room >= 3) {           /* U+2080..U+20BF */
            p[i++] = 0xE2; p[i++] = 0x82; p[i++] = (uint8_t)(0x80 + rng_next(s) % 0x40);
        } else if (r <= 4 && room >= 2) {           /* U+00C0..U+00FF */
            p[i++] = 0xC3; p[i++] = (uint8_t)(0x80 + rng_next(s) % 0x40);
        } else {
            p[i++] = (uint8_t)(0x20 + rng_next(s) % 0x5F);
        }
    }
}

/* ---- Constructor «crudo» de paquetes (para probar paquetes mal formados) ---- */

struct rsec {
    const char *tag;            /* 4 bytes exactos */
    const uint8_t *data;
    uint32_t len;               /* bytes que se copian */
    int64_t declared;           /* longitud que se anuncia; < 0 = la misma */
};

/* version/count/total: valores de la cabecera (count < 0 = nº real; total < 0 = longitud real).
 * bad_crc: escribe un CRC incorrecto. Devuelve la longitud del buffer. */
static size_t raw_build(uint8_t *out, uint32_t version, int32_t count, int64_t total,
                        const struct rsec *s, unsigned n, bool bad_crc)
{
    size_t pos = HDR;
    for (unsigned i = 0; i < n; i++) {
        memcpy(out + pos, s[i].tag, 4);
        le32(out + pos + 4, s[i].declared < 0 ? s[i].len : (uint32_t)s[i].declared);
        if (s[i].len)
            memcpy(out + pos + SHDR, s[i].data, s[i].len);
        pos += SHDR + s[i].len;
    }
    size_t len = pos + CRCB;
    memcpy(out, "PGBM", 4);
    le16(out + 4, version);
    le16(out + 6, count < 0 ? n : (uint32_t)count);
    le32(out + 8, total < 0 ? (uint32_t)len : (uint32_t)total);
    uint32_t crc = crc32_update(0, out, pos);
    le32(out + pos, bad_crc ? ~crc : crc);
    return len;
}

/* Un paquete mínimo válido a partir del cual se fabrican los defectuosos. */
static uint8_t g_rom[ROMF_LEN];
static uint8_t g_sav16[16];

static void base_init(void)
{
    pat_fill(g_rom, sizeof g_rom, 0x10);
    pat_fill(g_sav16, sizeof g_sav16, 0x21);
}

#define RS(tag, d, n) { tag, d, n, -1 }

/* ---- Vectores dorados ---- */

/* «Pokémon Rojo – prueba» con é (C3 A9) y el guion largo (E2 80 93). 61 bytes. */
static const char GOLDEN_META[] = "{\"formato\":1,\"juego\":\"Pok\xC3\xA9" "mon Rojo \xE2\x80\x93 prueba\",\"ms\":1234567}";
enum { GOLDEN_META_LEN = sizeof GOLDEN_META - 1, GOLDEN_SAV = 8192, GOLDEN_STATE = 1500, GOLDEN_THUMB = 32 };

static const char G1_SHA[] = "617c82a522e01eddddba5606df1f383356d9f4ef93c0768d9d7e893dcf146cd7";
static const char G2_SHA[] = "ea86e10010169eae94e57719e10230a15e1cbaf607fdeb650dc6eb0b352dcf75";
static const char G3_SHA[] = "eba7c40e531741db94fc000ec90ed36774707357e9b181d05c8f40780d32f6a2";
enum { G1_LEN = 9857, G2_LEN = 64, G3_LEN = 77 };
static const uint32_t G1_CRC = 0xe477060fu, G2_CRC = 0xc42da47du, G3_CRC = 0x587f15d4u;

static const uint8_t G2_BYTES[G2_LEN] = {
    0x50, 0x47, 0x42, 0x4d, 0x01, 0x00, 0x02, 0x00, 0x40, 0x00, 0x00, 0x00, 0x52, 0x4f, 0x4d, 0x46,
    0x10, 0x00, 0x00, 0x00, 0x10, 0x35, 0x5a, 0x7f, 0xa4, 0xc9, 0xee, 0x13, 0x38, 0x5d, 0x82, 0xa7,
    0xcc, 0xf1, 0x16, 0x3b, 0x53, 0x41, 0x56, 0x45, 0x10, 0x00, 0x00, 0x00, 0x21, 0x46, 0x6b, 0x90,
    0xb5, 0xda, 0xff, 0x24, 0x49, 0x6e, 0x93, 0xb8, 0xdd, 0x02, 0x27, 0x4c, 0x7d, 0xa4, 0x2d, 0xc4
};

/* which: 0 = G1 completo, 1 = G2 mínimo, 2 = G3 con una sección desconocida. Devuelve la longitud. */
static size_t build_golden(unsigned which, uint8_t *out, size_t cap)
{
    uint8_t rom[ROMF_LEN];
    pat_fill(rom, sizeof rom, 0x10);
    if (which == 2) {
        struct rsec s[3] = { RS("ROMF", rom, ROMF_LEN), RS("XTRA", (const uint8_t *)"hola!", 5), { "SAVE", NULL, 16, -1 } };
        uint8_t sav[16];
        pat_fill(sav, sizeof sav, 0x21);
        s[2].data = sav;
        if (cap < 128)
            return 0;
        return raw_build(out, 1, -1, -1, s, 3, false);
    }
    pgbm_view v;
    memset(&v, 0, sizeof v);
    memcpy(v.rom_fp, rom, sizeof rom);
    uint8_t *sav = malloc(GOLDEN_SAV), *state = malloc(GOLDEN_STATE);
    uint8_t thumb[GOLDEN_THUMB] = { 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A };
    size_t written = 0;
    pgbm_result r = PGBM_ERR_ARG;
    if (sav && state) {
        v.sav.data = sav;
        if (which == 0) {
            pat_fill(sav, GOLDEN_SAV, 0x21);
            pat_fill(state, GOLDEN_STATE, 0x42);
            pat_fill(thumb + 8, sizeof thumb - 8, 0x63);
            v.sav.len = GOLDEN_SAV;
            v.meta.data = (const uint8_t *)GOLDEN_META;
            v.meta.len = GOLDEN_META_LEN;
            v.state.data = state;
            v.state.len = GOLDEN_STATE;
            v.thumb.data = thumb;
            v.thumb.len = sizeof thumb;
        } else {
            pat_fill(sav, 16, 0x21);
            v.sav.len = 16;
        }
        r = pgbm_encode(&v, out, cap, &written);
    }
    free(sav);
    free(state);
    return r == PGBM_OK ? written : 0;
}

static void test_golden(struct ut *t)
{
    base_init();
    static const unsigned lens[3] = { G1_LEN, G2_LEN, G3_LEN };
    static const char *const shas[3] = { G1_SHA, G2_SHA, G3_SHA };
    static const uint32_t crcs[3] = { G1_CRC, G2_CRC, G3_CRC };
    uint8_t *buf = malloc(16384);
    CHECK(t, buf != NULL);
    if (!buf)
        return;
    for (unsigned w = 0; w < 3; w++) {
        size_t n = build_golden(w, buf, 16384);
        CHECK(t, n == lens[w]);
        CHECK(t, sha_is(buf, n, shas[w]));
        if (n >= CRCB) {
            uint32_t stored = (uint32_t)buf[n - 4] | ((uint32_t)buf[n - 3] << 8) | ((uint32_t)buf[n - 2] << 16) |
                              ((uint32_t)buf[n - 1] << 24);
            CHECK(t, stored == crcs[w]);
            CHECK(t, crc32_update(0, buf, n - 4) == crcs[w]);
        }
    }
    /* G2 byte a byte: contra la tabla del documento, que no sale del codificador. */
    size_t n2 = build_golden(1, buf, 16384);
    CHECK(t, n2 == G2_LEN && memcmp(buf, G2_BYTES, G2_LEN) == 0);

    /* G1 se lee con los contenidos esperados. */
    size_t n1 = build_golden(0, buf, 16384);
    uint8_t *c = dup_exact(buf, n1);
    pgbm_view v;
    CHECK(t, c != NULL);
    if (c) {
        CHECK(t, pgbm_parse(c, n1, &v) == PGBM_OK);
        CHECK(t, v.version == 1);
        uint8_t exp[GOLDEN_SAV];
        pat_fill(exp, GOLDEN_SAV, 0x10);
        CHECK(t, memcmp(v.rom_fp, exp, ROMF_LEN) == 0);
        CHECK(t, span_is(v.meta, (const uint8_t *)GOLDEN_META, GOLDEN_META_LEN));
        pat_fill(exp, GOLDEN_SAV, 0x21);
        CHECK(t, span_is(v.sav, exp, GOLDEN_SAV));
        pat_fill(exp, GOLDEN_STATE, 0x42);
        CHECK(t, span_is(v.state, exp, GOLDEN_STATE));
        CHECK(t, v.thumb.len == GOLDEN_THUMB && v.thumb.data[0] == 0x89 && v.thumb.data[8] == pat(0x63, 0));
        free(c);
    }
    /* G3: la sección desconocida se ignora y el resto se lee. */
    size_t n3 = build_golden(2, buf, 16384);
    c = dup_exact(buf, n3);
    CHECK(t, c != NULL);
    if (c) {
        CHECK(t, pgbm_parse(c, n3, &v) == PGBM_OK);
        CHECK(t, span_is(v.sav, g_sav16, 16) && v.meta.len == 0 && v.state.len == 0 && v.thumb.len == 0);
        free(c);
    }
    free(buf);
}

/* ---- Ida y vuelta con cargas aleatorias ---- */

static void roundtrip(struct ut *t, size_t meta_n, size_t sav_n, size_t state_n, size_t thumb_n, uint32_t seed)
{
    uint32_t rs = seed;
    uint8_t *meta = malloc(meta_n + 1), *sav = malloc(sav_n + 1), *state = malloc(state_n + 1), *thumb = malloc(thumb_n + 8);
    CHECK(t, meta && sav && state && thumb);
    if (!(meta && sav && state && thumb)) {
        free(meta); free(sav); free(state); free(thumb);
        return;
    }
    rand_utf8(meta, meta_n, &rs);
    rng_fill(sav, sav_n, &rs);
    rng_fill(state, state_n, &rs);
    if (thumb_n) {
        rng_fill(thumb, thumb_n, &rs);
        memcpy(thumb, "\x89PNG\r\n\x1a\n", thumb_n >= 8 ? 8 : 0);
    }
    pgbm_view in;
    memset(&in, 0, sizeof in);
    rng_fill(in.rom_fp, ROMF_LEN, &rs);
    in.meta = (pgbm_span){ meta_n ? meta : NULL, (uint32_t)meta_n };
    in.sav = (pgbm_span){ sav, (uint32_t)sav_n };
    in.state = (pgbm_span){ state_n ? state : NULL, (uint32_t)state_n };
    in.thumb = (pgbm_span){ thumb_n ? thumb : NULL, (uint32_t)thumb_n };

    size_t expect = HDR + CRCB + SHDR + ROMF_LEN + SHDR + sav_n + (meta_n ? SHDR + meta_n : 0) +
                    (state_n ? SHDR + state_n : 0) + (thumb_n ? SHDR + thumb_n : 0);
    size_t size = pgbm_encoded_size(&in);
    CHECK(t, size == expect);
    uint8_t *pkg = malloc(size ? size : 1);
    CHECK(t, pkg != NULL);
    if (pkg) {
        size_t written = 7;
        CHECK(t, pgbm_encode(&in, pkg, size, &written) == PGBM_OK);
        CHECK(t, written == size);
        pgbm_view out;
        CHECK(t, pgbm_parse(pkg, size, &out) == PGBM_OK);
        CHECK(t, out.version == PGBM_VERSION);
        CHECK(t, memcmp(out.rom_fp, in.rom_fp, ROMF_LEN) == 0);
        CHECK(t, span_is(out.meta, meta, meta_n) && span_is(out.sav, sav, sav_n));
        CHECK(t, span_is(out.state, state, state_n) && span_is(out.thumb, thumb, thumb_n));
        CHECK(t, (meta_n != 0) == (out.meta.data != NULL) && (state_n != 0) == (out.state.data != NULL));
        CHECK(t, (thumb_n != 0) == (out.thumb.data != NULL) && out.sav.data != NULL);
        /* Los spans caen dentro del buffer (parse no copia). */
        const uint8_t *lo = pkg, *hi = pkg + size;
        CHECK(t, out.sav.data >= lo && out.sav.data + out.sav.len <= hi);
        CHECK(t, !meta_n || (out.meta.data >= lo && out.meta.data + out.meta.len <= hi));
        /* Canónico: volver a codificar lo leído da los mismos bytes. */
        uint8_t *again = malloc(size);
        if (again) {
            size_t w2 = 0;
            CHECK(t, pgbm_encode(&out, again, size, &w2) == PGBM_OK && w2 == size && memcmp(again, pkg, size) == 0);
            free(again);
        }
        /* Con más sitio del necesario no se escribe de más. */
        uint8_t *big = malloc(size + 32);
        if (big) {
            memset(big, 0xA5, size + 32);
            size_t w3 = 0;
            CHECK(t, pgbm_encode(&in, big, size + 32, &w3) == PGBM_OK && w3 == size);
            CHECK(t, memcmp(big, pkg, size) == 0);
            bool tail = true;
            for (size_t i = size; i < size + 32; i++)
                tail = tail && big[i] == 0xA5;
            CHECK(t, tail);
            free(big);
        }
        free(pkg);
    }
    free(meta); free(sav); free(state); free(thumb);
}

static void test_roundtrip(struct ut *t)
{
    static const size_t cases[][4] = {   /* meta, sav, state, thumb */
        { 0, 0, 0, 0 },                  /* partida vacía, nada opcional */
        { 0, 512, 0, 0 },                /* MBC2 */
        { 1, 1, 1, 8 },                  /* tamaños mínimos de cada sección opcional */
        { 61, 8192, 0, 0 },
        { 200, 32768 + 48, 40000, 700 }, /* GB con RTC */
        { 300, 131072 + 16, 700000, 9000 },
        { 0, 32768, 123456, 0 },
        { 5000, 0, 0, 8 },
        { PGBM_MAX_META, PGBM_MAX_SAV, PGBM_MAX_STATE, PGBM_MAX_THUMB },   /* todos los topes a la vez */
        { PGBM_MAX_META, 0, 0, 0 },
        { 0, PGBM_MAX_SAV, 0, 0 },
        { 0, 0, PGBM_MAX_STATE, 0 },
        { 0, 0, 0, PGBM_MAX_THUMB },
    };
    for (size_t i = 0; i < sizeof cases / sizeof cases[0]; i++)
        roundtrip(t, cases[i][0], cases[i][1], cases[i][2], cases[i][3], 0x1234567u + (uint32_t)i * 7919u);
    /* Muchas formas pequeñas con semillas distintas. */
    uint32_t rs = 99;
    for (unsigned i = 0; i < 60; i++) {
        size_t m = rng_next(&rs) % 3 ? rng_next(&rs) % 300 : 0, s = rng_next(&rs) % 2000;
        size_t st = rng_next(&rs) % 3 ? rng_next(&rs) % 3000 : 0, th = rng_next(&rs) % 3 ? 8 + rng_next(&rs) % 500 : 0;
        roundtrip(t, m, s, st, th, 0xC0FFEEu + i);
    }
}

/* ---- Cabecera, versión, longitud, CRC ---- */

static void test_header_errors(struct ut *t)
{
    uint8_t *g = malloc(16384);
    CHECK(t, g != NULL);
    if (!g)
        return;
    size_t n = build_golden(0, g, 16384);
    pgbm_view v;

    /* Argumentos nulos y buffer vacío. */
    CHECK(t, pgbm_parse(NULL, 10, &v) == PGBM_ERR_ARG);
    CHECK(t, pgbm_parse(g, n, NULL) == PGBM_ERR_ARG);
    CHECK(t, parse_code(g, 0) == PGBM_ERR_TRUNCATED);

    /* Mágico. */
    static const char *const bad_magic[] = { "PGBN", "pgbm", "PGB\0", "\0GBM", "XGBM", "GBPM" };
    for (size_t i = 0; i < sizeof bad_magic / sizeof bad_magic[0]; i++) {
        uint8_t *c = dup_exact(g, n);
        if (!c)
            continue;
        memcpy(c, bad_magic[i], 4);
        CHECK(t, pgbm_parse(c, n, &v) == PGBM_ERR_MAGIC);
        CHECK(t, view_zero(&v));
        free(c);
    }
    CHECK(t, parse_code((const uint8_t *)"X", 1) == PGBM_ERR_MAGIC);       /* ni el primer byte coincide */
    CHECK(t, parse_code((const uint8_t *)"PG", 2) == PGBM_ERR_TRUNCATED);  /* prefijo del mágico */

    /* Versión: 0, futuras y la mayor posible. Se rechaza antes de mirar el resto. */
    static const unsigned versions[] = { 0, 2, 3, 0x0100, 0x7FFF, 0xFFFF };
    for (size_t i = 0; i < sizeof versions / sizeof versions[0]; i++) {
        uint8_t *c = dup_exact(g, n);
        if (!c)
            continue;
        le16(c + 4, versions[i]);
        CHECK(t, pgbm_parse(c, n, &v) == PGBM_ERR_VERSION);
        CHECK(t, view_zero(&v));
        free(c);
        /* Con una cabecera de otra versión que ni siquiera mide 12 bytes también se avisa de la versión. */
        uint8_t shortv[8] = { 'P', 'G', 'B', 'M', (uint8_t)versions[i], (uint8_t)(versions[i] >> 8), 0, 0 };
        CHECK(t, parse_code(shortv, 8) == PGBM_ERR_VERSION);
    }

    /* Truncado en cada byte: todo prefijo propio del paquete se rechaza como TRUNCATED. */
    int bad = 0;
    for (size_t k = 0; k < n; k++)
        if (parse_code(g, k) != PGBM_ERR_TRUNCATED)
            bad++;
    CHECK(t, bad == 0);
    /* Un byte de más: no es el archivo que anuncia la cabecera. */
    uint8_t *plus = malloc(n + 1);
    if (plus) {
        memcpy(plus, g, n);
        plus[n] = 0;
        CHECK(t, parse_code(plus, n + 1) == PGBM_ERR_BOUNDS);
        free(plus);
    }

    /* Longitud total de la cabecera. */
    static const struct { uint32_t total; pgbm_result want; } totals[] = {
        { 0, PGBM_ERR_BOUNDS }, { 15, PGBM_ERR_BOUNDS }, { 16, PGBM_ERR_BOUNDS },
        { 9856, PGBM_ERR_BOUNDS },                              /* menor que el archivo */
        { 9858, PGBM_ERR_TRUNCATED }, { 100000, PGBM_ERR_TRUNCATED },
        { PGBM_MAX_TOTAL, PGBM_ERR_TRUNCATED },
        { PGBM_MAX_TOTAL + 1, PGBM_ERR_TOO_LARGE }, { 0xFFFFFFFFu, PGBM_ERR_TOO_LARGE },
    };
    for (size_t i = 0; i < sizeof totals / sizeof totals[0]; i++) {
        uint8_t *c = dup_exact(g, n);
        if (!c)
            continue;
        le32(c + 8, totals[i].total);
        pgbm_result r = pgbm_parse(c, n, &v);
        CHECK(t, r == totals[i].want);
        if (r != totals[i].want)
            fprintf(stderr, "    total=%u -> %s\n", (unsigned)totals[i].total, pgbm_result_name(r));
        free(c);
    }

    /* Archivo tan corto como su propia longitud total (12..15 bytes): ni siquiera cabe el CRC. */
    for (uint32_t tot = 12; tot < 16; tot++) {
        uint8_t tiny[16];
        memset(tiny, 0, sizeof tiny);
        memcpy(tiny, "PGBM", 4);
        le16(tiny + 4, 1);
        le32(tiny + 8, tot);
        CHECK(t, parse_code(tiny, tot) == PGBM_ERR_BOUNDS);
    }
    /* El paquete más corto posible (16 bytes, sin secciones) lee bien hasta que falta ROMF/SAVE. */
    {
        uint8_t empty[16];
        size_t en = raw_build(empty, 1, -1, -1, NULL, 0, false);
        CHECK(t, en == 16 && parse_code(empty, en) == PGBM_ERR_MISSING);
    }

    /* Corromper cualquier byte (todos los bits) del paquete mínimo, o dos bits por byte del
     * completo, nunca da OK; los 12 primeros bytes dan el error de su campo. */
    uint8_t *sm = malloc(G2_LEN);
    if (sm) {
        int ok = 0;
        for (size_t i = 0; i < G2_LEN; i++)
            for (unsigned b = 0; b < 8; b++) {
                memcpy(sm, G2_BYTES, G2_LEN);
                sm[i] ^= (uint8_t)(1u << b);
                pgbm_result r = parse_code(sm, G2_LEN);
                if (r == PGBM_OK)
                    ok++;
                if (i < 4)
                    CHECK(t, r == PGBM_ERR_MAGIC);
                else if (i < 6)
                    CHECK(t, r == PGBM_ERR_VERSION);
                else if (i >= 12 || i < 8)
                    CHECK(t, r == PGBM_ERR_CRC);                 /* cuenta y contenido: los cubre el CRC */
                else if (i >= 8)
                    CHECK(t, r == PGBM_ERR_TRUNCATED || r == PGBM_ERR_BOUNDS || r == PGBM_ERR_TOO_LARGE);
            }
        CHECK(t, ok == 0);
        free(sm);
    }
    uint8_t *w = dup_exact(g, n);
    if (w) {
        int ok = 0, not_crc = 0;
        for (size_t i = 12; i < n; i++) {
            w[i] ^= 0x01;
            pgbm_result r = parse_code(w, n);
            ok += r == PGBM_OK;
            not_crc += r != PGBM_ERR_CRC;
            w[i] ^= 0x01;
            w[i] ^= 0x80;
            r = parse_code(w, n);
            ok += r == PGBM_OK;
            not_crc += r != PGBM_ERR_CRC;
            w[i] ^= 0x80;
        }
        CHECK(t, ok == 0 && not_crc == 0);
        CHECK(t, parse_code(w, n) == PGBM_OK);                   /* y restaurado vuelve a leerse */
        free(w);
    }
    free(g);
}

/* ---- Estructura de las secciones (con CRC correcto) ---- */

static void test_structure(struct ut *t)
{
    base_init();
    uint8_t *buf = malloc(PGBM_MAX_TOTAL + 64);
    uint8_t *big = calloc(PGBM_MAX_TOTAL + 64, 1);
    CHECK(t, buf && big);
    if (!(buf && big)) {
        free(buf);
        free(big);
        return;
    }
    pgbm_view v;
    struct rsec romf = RS("ROMF", g_rom, ROMF_LEN), sav = RS("SAVE", g_sav16, 16);
    static const uint8_t json[] = "{\"a\":1}";
    static const uint8_t png[] = { 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3 };
    static const uint8_t blob[] = { 1, 2, 3, 4 };

    /* Base y orden: cualquier orden de las secciones es válido. */
    {
        struct rsec a[2] = { romf, sav }, b[2] = { sav, romf };
        size_t n = raw_build(buf, 1, -1, -1, a, 2, false);
        CHECK(t, parse_copy(buf, n, &v) == PGBM_OK);
        n = raw_build(buf, 1, -1, -1, b, 2, false);
        CHECK(t, parse_copy(buf, n, &v) == PGBM_OK);
        struct rsec all[5] = { RS("STAT", blob, 4), RS("THMB", png, sizeof png), sav, RS("META", json, 7), romf };
        n = raw_build(buf, 1, -1, -1, all, 5, false);
        uint8_t *c = dup_exact(buf, n);
        CHECK(t, c && pgbm_parse(c, n, &v) == PGBM_OK);
        if (c) {
            CHECK(t, span_is(v.meta, json, 7) && span_is(v.thumb, png, sizeof png) && span_is(v.state, blob, 4));
            CHECK(t, span_is(v.sav, g_sav16, 16) && memcmp(v.rom_fp, g_rom, ROMF_LEN) == 0);
            free(c);
        }
    }

    /* Faltan secciones obligatorias. */
    {
        struct rsec only_romf[1] = { romf }, only_sav[1] = { sav }, meta_only[1] = { RS("META", json, 7) };
        size_t n = raw_build(buf, 1, -1, -1, only_romf, 1, false);
        CHECK(t, parse_code(buf, n) == PGBM_ERR_MISSING);
        n = raw_build(buf, 1, -1, -1, only_sav, 1, false);
        CHECK(t, parse_code(buf, n) == PGBM_ERR_MISSING);
        n = raw_build(buf, 1, -1, -1, NULL, 0, false);
        CHECK(t, parse_code(buf, n) == PGBM_ERR_MISSING);
        n = raw_build(buf, 1, -1, -1, meta_only, 1, false);
        CHECK(t, parse_code(buf, n) == PGBM_ERR_MISSING);
        /* La falta de ROMF o SAVE gana a un META inválido (orden documentado). */
        static const uint8_t badutf[] = { 0xFF };
        struct rsec x[2] = { sav, RS("META", badutf, 1) };
        n = raw_build(buf, 1, -1, -1, x, 2, false);
        CHECK(t, parse_code(buf, n) == PGBM_ERR_MISSING);
    }

    /* Duplicadas: cada tipo conocido. */
    {
        static const char *const tags[5] = { "ROMF", "META", "THMB", "SAVE", "STAT" };
        for (int k = 0; k < 5; k++) {
            struct rsec s[7] = { romf, sav, RS("META", json, 7), RS("THMB", png, sizeof png), RS("STAT", blob, 4), { 0 }, { 0 } };
            const uint8_t *d = k == 0 ? g_rom : k == 1 ? json : k == 2 ? png : k == 3 ? g_sav16 : blob;
            uint32_t dl = k == 0 ? ROMF_LEN : k == 1 ? 7 : k == 2 ? sizeof png : k == 3 ? 16 : 4;
            s[5] = (struct rsec){ tags[k], d, dl, -1 };
            size_t n = raw_build(buf, 1, -1, -1, s, 6, false);
            CHECK(t, parse_code(buf, n) == PGBM_ERR_DUPLICATE);
        }
    }

    /* ROMF de longitud distinta de 16; opcionales vacías; SAVE vacía sí vale. */
    {
        static const unsigned romf_lens[] = { 0, 1, 15, 17, 32 };
        for (size_t i = 0; i < sizeof romf_lens / sizeof romf_lens[0]; i++) {
            struct rsec s[2] = { RS("ROMF", big, romf_lens[i]), sav };
            size_t n = raw_build(buf, 1, -1, -1, s, 2, false);
            CHECK(t, parse_code(buf, n) == PGBM_ERR_BOUNDS);
        }
        static const char *const opt[3] = { "META", "STAT", "THMB" };
        for (int k = 0; k < 3; k++) {
            struct rsec s[3] = { romf, sav, { opt[k], big, 0, -1 } };
            size_t n = raw_build(buf, 1, -1, -1, s, 3, false);
            CHECK(t, parse_code(buf, n) == PGBM_ERR_BOUNDS);
        }
        struct rsec e[2] = { romf, { "SAVE", big, 0, -1 } };
        size_t n = raw_build(buf, 1, -1, -1, e, 2, false);
        uint8_t *c = dup_exact(buf, n);
        CHECK(t, c && pgbm_parse(c, n, &v) == PGBM_OK);
        CHECK(t, c && v.sav.len == 0 && v.sav.data != NULL && v.sav.data <= c + n);
        free(c);
    }

    /* Longitudes que se salen del contenedor. */
    {
        struct rsec last_plus1[2] = { romf, { "SAVE", g_sav16, 16, 17 } };
        size_t n = raw_build(buf, 1, -1, -1, last_plus1, 2, false);
        CHECK(t, parse_code(buf, n) == PGBM_ERR_BOUNDS);
        struct rsec huge[2] = { romf, { "SAVE", g_sav16, 16, (int64_t)0xFFFFFFFFu } };
        n = raw_build(buf, 1, -1, -1, huge, 2, false);
        CHECK(t, parse_code(buf, n) == PGBM_ERR_BOUNDS);
        struct rsec first_huge[2] = { { "SAVE", g_sav16, 16, 0x7FFFFFFF }, romf };
        n = raw_build(buf, 1, -1, -1, first_huge, 2, false);
        CHECK(t, parse_code(buf, n) == PGBM_ERR_BOUNDS);
        struct rsec shrink[2] = { { "SAVE", g_sav16, 16, 15 }, romf };    /* anuncia de menos: el resto desalinea */
        n = raw_build(buf, 1, -1, -1, shrink, 2, false);
        CHECK(t, parse_code(buf, n) != PGBM_OK);
        /* Cuenta de secciones distinta de la real. */
        struct rsec two[2] = { romf, sav };
        n = raw_build(buf, 1, 3, -1, two, 2, false);
        CHECK(t, parse_code(buf, n) == PGBM_ERR_BOUNDS);
        n = raw_build(buf, 1, 1, -1, two, 2, false);
        CHECK(t, parse_code(buf, n) == PGBM_ERR_BOUNDS);
        n = raw_build(buf, 1, 0, -1, two, 2, false);
        CHECK(t, parse_code(buf, n) == PGBM_ERR_BOUNDS);
        /* Bytes sueltos tras la última sección. */
        n = raw_build(buf, 1, -1, -1, two, 2, false);
        memmove(buf + n - 4 + 3, buf + n - 4, 4);          /* hueco de 3 bytes antes del CRC */
        memset(buf + n - 4, 0xEE, 3);
        le32(buf + 8, (uint32_t)(n + 3));
        le32(buf + n - 1, crc32_update(0, buf, n - 1));
        CHECK(t, parse_code(buf, n + 3) == PGBM_ERR_BOUNDS);
        /* Un resto de 1..7 bytes no basta para una cabecera de sección. */
        n = raw_build(buf, 1, 3, -1, two, 2, false);       /* anuncia 3 secciones y solo caben 5 bytes */
        memmove(buf + n - 4 + 5, buf + n - 4, 4);
        memset(buf + n - 4, 0x55, 5);
        le32(buf + 8, (uint32_t)(n + 5));
        le32(buf + n + 1, crc32_update(0, buf, n + 1));
        CHECK(t, parse_code(buf, n + 5) == PGBM_ERR_BOUNDS);
    }

    /* Topes por sección (todo con el total ≤ 4 MiB). */
    {
        struct rsec m_ok[3] = { romf, sav, RS("META", big, 0) };
        memset(big, 'a', PGBM_MAX_TOTAL);
        m_ok[2].len = PGBM_MAX_META;
        size_t n = raw_build(buf, 1, -1, -1, m_ok, 3, false);
        CHECK(t, parse_code(buf, n) == PGBM_OK);
        m_ok[2].len = PGBM_MAX_META + 1;
        n = raw_build(buf, 1, -1, -1, m_ok, 3, false);
        CHECK(t, parse_code(buf, n) == PGBM_ERR_TOO_LARGE);

        struct rsec s_ok[2] = { romf, { "SAVE", big, PGBM_MAX_SAV, -1 } };
        n = raw_build(buf, 1, -1, -1, s_ok, 2, false);
        CHECK(t, parse_code(buf, n) == PGBM_OK);
        s_ok[1].len = PGBM_MAX_SAV + 1;
        n = raw_build(buf, 1, -1, -1, s_ok, 2, false);
        CHECK(t, parse_code(buf, n) == PGBM_ERR_TOO_LARGE);

        struct rsec st[3] = { romf, sav, { "STAT", big, PGBM_MAX_STATE, -1 } };
        n = raw_build(buf, 1, -1, -1, st, 3, false);
        CHECK(t, parse_code(buf, n) == PGBM_OK);
        st[2].len = PGBM_MAX_STATE + 1;
        n = raw_build(buf, 1, -1, -1, st, 3, false);
        CHECK(t, parse_code(buf, n) == PGBM_ERR_TOO_LARGE);

        /* THMB: la firma PNG al principio y luego relleno hasta el tope. */
        memcpy(big, png, 8);
        struct rsec th[3] = { romf, sav, { "THMB", big, PGBM_MAX_THUMB, -1 } };
        n = raw_build(buf, 1, -1, -1, th, 3, false);
        CHECK(t, parse_code(buf, n) == PGBM_OK);
        th[2].len = PGBM_MAX_THUMB + 1;
        n = raw_build(buf, 1, -1, -1, th, 3, false);
        CHECK(t, parse_code(buf, n) == PGBM_ERR_TOO_LARGE);
        memset(big, 'a', 8);

        /* Una desconocida que lleva el archivo justo al tope total, y un byte más. */
        size_t fixed = HDR + CRCB + (SHDR + ROMF_LEN) + (SHDR + 16) + SHDR;
        struct rsec u[3] = { romf, sav, { "ZZZZ", big, (uint32_t)(PGBM_MAX_TOTAL - fixed), -1 } };
        n = raw_build(buf, 1, -1, -1, u, 3, false);
        CHECK(t, n == PGBM_MAX_TOTAL && parse_code(buf, n) == PGBM_OK);
        u[2].len++;
        n = raw_build(buf, 1, -1, -1, u, 3, false);
        CHECK(t, n == PGBM_MAX_TOTAL + 1 && parse_code(buf, n) == PGBM_ERR_TOO_LARGE);
    }

    /* Número de secciones: 64 sí, 65 no. */
    {
        struct rsec s[PGBM_MAX_SECTIONS + 1];
        s[0] = romf;
        s[1] = sav;
        for (unsigned i = 2; i < PGBM_MAX_SECTIONS + 1; i++)
            s[i] = (struct rsec){ "FILL", blob, 4, -1 };
        size_t n = raw_build(buf, 1, -1, -1, s, PGBM_MAX_SECTIONS, false);
        CHECK(t, parse_code(buf, n) == PGBM_OK);
        n = raw_build(buf, 1, -1, -1, s, PGBM_MAX_SECTIONS + 1, false);
        CHECK(t, parse_code(buf, n) == PGBM_ERR_TOO_LARGE);
    }

    /* Tipos desconocidos: se ignoran (aunque se repitan o vayan vacíos) pero cuentan para el CRC. */
    {
        /* Los tipos que solo se parecen a uno conocido (ROMX, METB, SAV_, STAT sin la última letra…) son desconocidos. */
        struct rsec s[12] = { RS("XTRA", blob, 4), romf, RS("XTRA", blob, 4), { "zzzz", blob, 0, -1 }, sav, RS("~!@#", blob, 4),
                              RS("ROMX", blob, 4), RS("METB", blob, 1), RS("SAV_", blob, 3), RS("STAU", blob, 2),
                              RS("THMC", blob, 1), RS("XOMF", blob, 4) };
        size_t n = raw_build(buf, 1, -1, -1, s, 12, false);
        uint8_t *c = dup_exact(buf, n);
        CHECK(t, c && pgbm_parse(c, n, &v) == PGBM_OK);
        CHECK(t, c && span_is(v.sav, g_sav16, 16) && memcmp(v.rom_fp, g_rom, ROMF_LEN) == 0);
        CHECK(t, c && v.meta.len == 0 && v.state.len == 0 && v.thumb.len == 0);
        /* Al volver a codificar se pierde lo desconocido y el resultado sigue siendo válido. */
        if (c) {
            uint8_t *re = malloc(n);
            size_t w = 0;
            CHECK(t, re && pgbm_encode(&v, re, n, &w) == PGBM_OK && w == 64);
            pgbm_view v2;
            CHECK(t, re && pgbm_parse(re, w, &v2) == PGBM_OK && span_is(v2.sav, g_sav16, 16));
            free(re);
        }
        /* Tocar un byte de la sección desconocida rompe el CRC. */
        if (c) {
            size_t xpos = 0;
            for (size_t i = HDR; i + 4 < n; i++)
                if (memcmp(c + i, "XTRA", 4) == 0) {
                    xpos = i + SHDR;
                    break;
                }
            CHECK(t, xpos != 0);
            c[xpos] ^= 0x10;
            CHECK(t, pgbm_parse(c, n, &v) == PGBM_ERR_CRC);
        }
        free(c);
        n = raw_build(buf, 1, -1, -1, s, 12, true);
        CHECK(t, parse_code(buf, n) == PGBM_ERR_CRC);
        /* Un tipo no ASCII imprimible no es un tipo. */
        static const char *const bad_tags[] = { "\x01XYZ", "XY Z", "XYZ\x7F", "\x80XYZ", "XYZ\xFF", "\0XYZ", " XYZ" };
        for (size_t i = 0; i < sizeof bad_tags / sizeof bad_tags[0]; i++) {
            struct rsec b[3] = { romf, sav, { bad_tags[i], blob, 4, -1 } };
            n = raw_build(buf, 1, -1, -1, b, 3, false);
            CHECK(t, parse_code(buf, n) == PGBM_ERR_BOUNDS);
        }
        /* Mayúsculas y minúsculas importan: «meta» es desconocido, no META. */
        struct rsec lc[3] = { romf, sav, RS("meta", big, 3) };
        memset(big, 0xFF, 8);
        n = raw_build(buf, 1, -1, -1, lc, 3, false);
        CHECK(t, parse_code(buf, n) == PGBM_OK);
    }

    /* Versión y CRC en paquetes bien formados. */
    {
        struct rsec s[2] = { romf, sav };
        static const unsigned vers[] = { 0, 2, 0xFFFF };
        for (size_t i = 0; i < sizeof vers / sizeof vers[0]; i++) {
            size_t n = raw_build(buf, vers[i], -1, -1, s, 2, false);
            CHECK(t, parse_code(buf, n) == PGBM_ERR_VERSION);
        }
        size_t n = raw_build(buf, 1, -1, -1, s, 2, true);
        CHECK(t, parse_code(buf, n) == PGBM_ERR_CRC);
    }

    /* Éxito y fallo dejan *out como se documenta. */
    {
        struct rsec s[2] = { romf, sav };
        size_t n = raw_build(buf, 1, -1, -1, s, 2, true);
        memset(&v, 0xA5, sizeof v);
        CHECK(t, parse_copy(buf, n, &v) == PGBM_ERR_CRC && view_zero(&v));
        n = raw_build(buf, 1, -1, -1, s, 2, false);
        uint8_t *c = dup_exact(buf, n);
        memset(&v, 0xA5, sizeof v);
        CHECK(t, c && pgbm_parse(c, n, &v) == PGBM_OK);
        CHECK(t, v.meta.data == NULL && v.meta.len == 0 && v.state.data == NULL && v.state.len == 0);
        CHECK(t, v.thumb.data == NULL && v.thumb.len == 0 && v.version == 1);
        free(c);
    }
    free(buf);
    free(big);
}

/* ---- UTF-8 de META y firma PNG de THMB ---- */

static void test_content_checks(struct ut *t)
{
    base_init();
    uint8_t buf[512];
    struct rsec romf = RS("ROMF", g_rom, ROMF_LEN), sav = RS("SAVE", g_sav16, 16);

    static const struct { const uint8_t b[8]; size_t n; bool ok; } utf8[] = {
        /* válidos */
        { { 'a' }, 1, true },
        { { 0x7F }, 1, true },                               /* DEL no es NUL */
        { { 0xC2, 0x80 }, 2, true },                         /* U+0080 */
        { { 0xDF, 0xBF }, 2, true },                         /* U+07FF */
        { { 0xE0, 0xA0, 0x80 }, 3, true },                   /* U+0800 */
        { { 0xE1, 0x80, 0x80 }, 3, true },
        { { 0xEC, 0xBF, 0xBF }, 3, true },
        { { 0xED, 0x9F, 0xBF }, 3, true },                   /* U+D7FF */
        { { 0xEE, 0x80, 0x80 }, 3, true },                   /* U+E000 */
        { { 0xEF, 0xBF, 0xBF }, 3, true },                   /* U+FFFF */
        { { 0xEF, 0xBB, 0xBF }, 3, true },                   /* BOM */
        { { 0xF0, 0x90, 0x80, 0x80 }, 4, true },             /* U+10000 */
        { { 0xF3, 0xBF, 0xBF, 0xBF }, 4, true },
        { { 0xF4, 0x8F, 0xBF, 0xBF }, 4, true },             /* U+10FFFF */
        { { 'a', 0xC3, 0xA9, 'b', 0xE2, 0x82, 0xAC }, 7, true },   /* mezcla de 1, 2 y 3 bytes */
        /* inválidos */
        { { 0x00 }, 1, false },                              /* NUL */
        { { 'a', 0x00, 'b' }, 3, false },
        { { 0x80 }, 1, false },                              /* continuación suelta */
        { { 0xBF }, 1, false },
        { { 0xC0, 0x80 }, 2, false },                        /* sobrelargo del NUL */
        { { 0xC1, 0xBF }, 2, false },
        { { 0xC2 }, 1, false },                              /* cortada */
        { { 0xC2, 0x41 }, 2, false },
        { { 0xE0, 0x80, 0x80 }, 3, false },                  /* sobrelargo */
        { { 0xE0, 0x9F, 0xBF }, 3, false },
        { { 0xE2, 0x82 }, 2, false },
        { { 0xE2, 0x82, 0x41 }, 3, false },
        { { 0xED, 0xA0, 0x80 }, 3, false },                  /* sustituto U+D800 */
        { { 0xED, 0xBF, 0xBF }, 3, false },                  /* sustituto U+DFFF */
        { { 0xF0, 0x80, 0x80, 0x80 }, 4, false },            /* sobrelargo */
        { { 0xF0, 0x8F, 0xBF, 0xBF }, 4, false },
        { { 0xF4, 0x90, 0x80, 0x80 }, 4, false },            /* > U+10FFFF */
        { { 0xF5, 0x80, 0x80, 0x80 }, 4, false },
        { { 0xF8, 0x88, 0x80, 0x80 }, 4, false },
        { { 0xFE }, 1, false },
        { { 0xFF }, 1, false },
        { { 0xF0, 0x9F, 0x98 }, 3, false },                  /* 4 bytes cortados */
        { { 0xC3, 0xA9, 0xA9 }, 3, false },                  /* continuación de más */
    };
    for (size_t i = 0; i < sizeof utf8 / sizeof utf8[0]; i++) {
        size_t n = utf8[i].n;
        /* Por el lector. */
        struct rsec s[3] = { romf, sav, { "META", utf8[i].b, (uint32_t)n, -1 } };
        size_t len = raw_build(buf, 1, -1, -1, s, 3, false);
        pgbm_result want = utf8[i].ok ? PGBM_OK : PGBM_ERR_UTF8;
        pgbm_result got = parse_code(buf, len);
        CHECK(t, got == want);
        if (got != want)
            fprintf(stderr, "    caso UTF-8 nº %zu: %s\n", i, pgbm_result_name(got));
        /* Y por el codificador, que acepta y rechaza exactamente lo mismo. */
        pgbm_view v;
        memset(&v, 0, sizeof v);
        v.sav = (pgbm_span){ g_sav16, 16 };
        v.meta = (pgbm_span){ utf8[i].b, (uint32_t)n };
        size_t w = 0;
        got = pgbm_encode(&v, buf, sizeof buf, &w);
        CHECK(t, got == want);
        CHECK(t, (got == PGBM_OK) == (w != 0));
    }

    static const uint8_t sig[8] = { 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A };
    uint8_t thumb[64];
    memset(thumb, 7, sizeof thumb);
    memcpy(thumb, sig, 8);
    for (size_t n = 1; n <= sizeof thumb; n++) {
        struct rsec s[3] = { romf, sav, { "THMB", thumb, (uint32_t)n, -1 } };
        size_t len = raw_build(buf, 1, -1, -1, s, 3, false);
        CHECK(t, parse_code(buf, len) == (n >= 8 ? PGBM_OK : PGBM_ERR_PNG));
        pgbm_view v;
        memset(&v, 0, sizeof v);
        v.sav = (pgbm_span){ g_sav16, 16 };
        v.thumb = (pgbm_span){ thumb, (uint32_t)n };
        CHECK(t, pgbm_encode(&v, buf, sizeof buf, NULL) == (n >= 8 ? PGBM_OK : PGBM_ERR_PNG));
    }
    for (size_t i = 0; i < 8; i++) {          /* un solo byte de la firma distinto */
        uint8_t bad[16];
        memcpy(bad, thumb, sizeof bad);
        bad[i] ^= 0x40;
        struct rsec s[3] = { romf, sav, { "THMB", bad, 16, -1 } };
        size_t len = raw_build(buf, 1, -1, -1, s, 3, false);
        CHECK(t, parse_code(buf, len) == PGBM_ERR_PNG);
    }
}

/* ---- Codificador: errores y garantías ---- */

static void test_encode(struct ut *t)
{
    base_init();
    uint8_t *big = calloc(PGBM_MAX_TOTAL + 64, 1);
    uint8_t out[1024];
    CHECK(t, big != NULL);
    if (!big)
        return;
    static const uint8_t png[] = { 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 9 };
    pgbm_view v;
    memset(&v, 0, sizeof v);
    pat_fill(v.rom_fp, ROMF_LEN, 0x10);
    v.sav = (pgbm_span){ g_sav16, 16 };
    v.meta = (pgbm_span){ (const uint8_t *)"{}", 2 };
    v.thumb = (pgbm_span){ png, sizeof png };
    size_t size = pgbm_encoded_size(&v);
    CHECK(t, size == HDR + CRCB + (SHDR + 16) + (SHDR + 16) + (SHDR + 2) + (SHDR + sizeof png));
    size_t w = 99;

    /* Argumentos. */
    CHECK(t, pgbm_encoded_size(NULL) == 0);
    CHECK(t, pgbm_encode(NULL, out, sizeof out, &w) == PGBM_ERR_ARG && w == 0);
    CHECK(t, pgbm_encode(&v, NULL, sizeof out, &w) == PGBM_ERR_ARG && w == 0);
    pgbm_view bad = v;
    bad.meta.data = NULL;                                      /* longitud 2 sin datos */
    CHECK(t, pgbm_encoded_size(&bad) == 0);
    CHECK(t, pgbm_encode(&bad, out, sizeof out, &w) == PGBM_ERR_ARG);
    bad = v;
    bad.sav.data = NULL;
    CHECK(t, pgbm_encode(&bad, out, sizeof out, &w) == PGBM_ERR_ARG);
    bad = v;
    bad.state = (pgbm_span){ NULL, 5 };
    CHECK(t, pgbm_encode(&bad, out, sizeof out, &w) == PGBM_ERR_ARG);
    bad = v;
    bad.thumb = (pgbm_span){ NULL, 5 };
    CHECK(t, pgbm_encode(&bad, out, sizeof out, &w) == PGBM_ERR_ARG);
    /* Datos no nulos con longitud 0 = sección ausente. */
    bad = v;
    bad.state = (pgbm_span){ g_sav16, 0 };
    CHECK(t, pgbm_encoded_size(&bad) == size);
    /* `written` puede ser NULL. */
    CHECK(t, pgbm_encode(&v, out, sizeof out, NULL) == PGBM_OK);

    /* Sin sitio: no se toca la salida y se avisa; con el tamaño justo cabe. */
    memset(out, 0xA5, sizeof out);
    w = 99;
    CHECK(t, pgbm_encode(&v, out, size - 1, &w) == PGBM_ERR_NOSPACE && w == 0);
    CHECK(t, pgbm_encode(&v, out, 0, &w) == PGBM_ERR_NOSPACE);
    bool intact = true;
    for (size_t i = 0; i < sizeof out; i++)
        intact = intact && out[i] == 0xA5;
    CHECK(t, intact);
    CHECK(t, pgbm_encode(&v, out, size, &w) == PGBM_OK && w == size);

    /* Versión. */
    bad = v;
    bad.version = 1;
    CHECK(t, pgbm_encode(&bad, out, sizeof out, &w) == PGBM_OK);
    bad.version = 0;
    CHECK(t, pgbm_encode(&bad, out, sizeof out, &w) == PGBM_OK);
    bad.version = 2;
    CHECK(t, pgbm_encode(&bad, out, sizeof out, &w) == PGBM_ERR_VERSION && w == 0);
    bad.version = 0xFFFF;
    CHECK(t, pgbm_encode(&bad, out, sizeof out, &w) == PGBM_ERR_VERSION);

    /* Topes: cada sección un byte por encima da TOO_LARGE y tamaño 0; el tope exacto cabe. */
    {
        pgbm_view b = v;
        b.meta = (pgbm_span){ big, PGBM_MAX_META + 1 };
        CHECK(t, pgbm_encoded_size(&b) == 0 && pgbm_encode(&b, out, sizeof out, &w) == PGBM_ERR_TOO_LARGE);
        b = v;
        b.sav = (pgbm_span){ big, PGBM_MAX_SAV + 1 };
        CHECK(t, pgbm_encoded_size(&b) == 0 && pgbm_encode(&b, out, sizeof out, &w) == PGBM_ERR_TOO_LARGE);
        b = v;
        b.state = (pgbm_span){ big, PGBM_MAX_STATE + 1 };
        CHECK(t, pgbm_encoded_size(&b) == 0 && pgbm_encode(&b, out, sizeof out, &w) == PGBM_ERR_TOO_LARGE);
        b = v;
        b.thumb = (pgbm_span){ big, PGBM_MAX_THUMB + 1 };
        CHECK(t, pgbm_encoded_size(&b) == 0 && pgbm_encode(&b, out, sizeof out, &w) == PGBM_ERR_TOO_LARGE);
        b = v;
        b.sav = (pgbm_span){ big, 0xFFFFFFFFu };
        CHECK(t, pgbm_encoded_size(&b) == 0 && pgbm_encode(&b, out, sizeof out, &w) == PGBM_ERR_TOO_LARGE);
        /* El tamaño se mide antes del contenido: una carga enorme no llega a validarse. */
        b = v;
        b.meta = (pgbm_span){ big, PGBM_MAX_META + 1 };      /* todo ceros: sería UTF8 si se mirara */
        CHECK(t, pgbm_encode(&b, out, sizeof out, &w) == PGBM_ERR_TOO_LARGE);
    }

    /* Contenido. */
    {
        pgbm_view b = v;
        b.meta = (pgbm_span){ (const uint8_t *)"a\xFF", 2 };
        CHECK(t, pgbm_encode(&b, out, sizeof out, &w) == PGBM_ERR_UTF8 && w == 0);
        b.meta = (pgbm_span){ (const uint8_t *)"a\0b", 3 };
        CHECK(t, pgbm_encode(&b, out, sizeof out, &w) == PGBM_ERR_UTF8);
        b = v;
        b.thumb = (pgbm_span){ (const uint8_t *)"GIF89a\x01\x02", 8 };
        CHECK(t, pgbm_encode(&b, out, sizeof out, &w) == PGBM_ERR_PNG);
        b.thumb = (pgbm_span){ png, 7 };
        CHECK(t, pgbm_encode(&b, out, sizeof out, &w) == PGBM_ERR_PNG);
    }

    /* Determinismo y forma canónica: ROMF, META, THMB, SAVE, STAT, con el CRC al final. */
    {
        pgbm_view b = v;
        b.state = (pgbm_span){ g_rom, 16 };
        size_t s2 = pgbm_encoded_size(&b);
        uint8_t *a1 = malloc(s2), *a2 = malloc(s2);
        CHECK(t, a1 && a2);
        if (a1 && a2) {
            memset(a1, 1, s2);
            memset(a2, 2, s2);
            CHECK(t, pgbm_encode(&b, a1, s2, NULL) == PGBM_OK && pgbm_encode(&b, a2, s2, NULL) == PGBM_OK);
            CHECK(t, memcmp(a1, a2, s2) == 0);
            static const char *const order[5] = { "ROMF", "META", "THMB", "SAVE", "STAT" };
            size_t pos = HDR;
            for (int i = 0; i < 5; i++) {
                CHECK(t, memcmp(a1 + pos, order[i], 4) == 0);
                pos += SHDR + ((uint32_t)a1[pos + 4] | ((uint32_t)a1[pos + 5] << 8));
            }
            CHECK(t, pos + CRCB == s2 && a1[6] == 5 && a1[7] == 0 && a1[4] == 1 && a1[5] == 0);
        }
        free(a1);
        free(a2);
    }
    free(big);
}

static void test_names(struct ut *t)
{
    const char *seen[16];
    unsigned n = 0;
    for (int r = PGBM_OK; r <= PGBM_ERR_NOSPACE; r++) {
        const char *s = pgbm_result_name((pgbm_result)r);
        CHECK(t, s != NULL && strncmp(s, "PGBM_", 5) == 0 && strcmp(s, "PGBM_ERR_UNKNOWN") != 0);
        bool dup = false;
        for (unsigned i = 0; i < n; i++)
            dup = dup || strcmp(seen[i], s) == 0;
        CHECK(t, !dup);
        if (n < 16)
            seen[n++] = s;
    }
    CHECK(t, n == 13);
    CHECK(t, strcmp(pgbm_result_name(PGBM_ERR_CRC), "PGBM_ERR_CRC") == 0);
    CHECK(t, strcmp(pgbm_result_name((pgbm_result)99), "PGBM_ERR_UNKNOWN") == 0);
    CHECK(t, strcmp(pgbm_result_name((pgbm_result)-1), "PGBM_ERR_UNKNOWN") == 0);
}

void unit_pgbm(struct ut *t)
{
    test_golden(t);
    test_roundtrip(t);
    test_header_errors(t);
    test_structure(t);
    test_content_checks(t);
    test_encode(t);
    test_names(t);
}

/* ---- Semillas de fuzz_pgbm ---- */

/* Escribe la entrada nº `which` (0..UT_PGBM_SEEDS-1): [indicadores][paquete]. Los indicadores
 * valen 0 en las que son un paquete tal cual; la última es del modo codificador (bit 7). */
size_t ut_pgbm_seed(unsigned which, uint8_t *out, size_t cap)
{
    base_init();
    uint8_t pkg[2048];
    size_t n = 0;
    if (which == 0 || which == 1) {                 /* G2 y G3 (mínimo y con sección desconocida) */
        n = build_golden(which == 0 ? 1 : 2, pkg, sizeof pkg);
    } else if (which == 2 || which == 3) {          /* completo pequeño; el 3 con el CRC roto y los arreglos activados */
        pgbm_view v;
        memset(&v, 0, sizeof v);
        uint8_t sav[600], state[300], thumb[40] = { 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A };
        pat_fill(sav, sizeof sav, 0x21);
        pat_fill(state, sizeof state, 0x42);
        pat_fill(thumb + 8, sizeof thumb - 8, 0x63);
        pat_fill(v.rom_fp, ROMF_LEN, 0x10);
        v.meta = (pgbm_span){ (const uint8_t *)GOLDEN_META, GOLDEN_META_LEN };
        v.sav = (pgbm_span){ sav, sizeof sav };
        v.state = (pgbm_span){ state, sizeof state };
        v.thumb = (pgbm_span){ thumb, sizeof thumb };
        if (pgbm_encode(&v, pkg, sizeof pkg, &n) != PGBM_OK)
            return 0;
        if (which == 3)
            pkg[n - 1] ^= 0x5A;
    } else {                                        /* modo codificador: [huella 16][longitudes meta, thumb, state, sav][datos] */
        static const uint8_t ascii[] = {
            'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p',   /* huella */
            8, 8, 4, 6,                                                                        /* meta, thumb, state, sav */
            '{', '"', 'k', '"', ':', '1', '}', ' ',
            0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
            1, 2, 3, 4,
            5, 6, 7, 8, 9, 10
        };
        static const uint8_t utf8[] = {                 /* META con secuencias de 1, 2, 3 y 4 bytes, firma PNG forzada por el indicador */
            'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z', 'A', 'B', 'C', 'D', 'E', 'F',
            15, 12, 0, 3,
            'a', 0xC3, 0xA9, 0xE2, 0x82, 0xAC, 0xF0, 0x9F, 0x98, 0x80, 'b', 0xF4, 0x8F, 0xBF, 0xBF,   /* 15 bytes de META */
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11,                                                 /* 12 de THMB */
            7, 8, 9
        };
        const uint8_t *src = which == 4 ? ascii : utf8;
        size_t len = which == 4 ? sizeof ascii : sizeof utf8;
        if (cap < 1 + len)
            return 0;
        out[0] = which == 4 ? 0x80 : 0xC0;
        memcpy(out + 1, src, len);
        return 1 + len;
    }
    if (n == 0 || cap < 1 + n)
        return 0;
    out[0] = which == 3 ? 0x0F : 0x00;
    memcpy(out + 1, pkg, n);
    return 1 + n;
}
