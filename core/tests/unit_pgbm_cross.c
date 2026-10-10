/*
 * unit_pgbm_cross.c — vectores dorados CRUZADOS de N7b (X1…X7): los paquetes que iOS y Android
 * construyen con SUS PROPIAS llamadas a pgbm_encode y pasan a SU importador. Este archivo es el
 * generador de referencia: cada plataforma reproduce los mismos bytes (mismo SHA-256) y comprueba
 * el resultado de importación que indica la tabla de docs/12-formato-pgbm.md §Vectores cruzados.
 *
 * Todo es sintético: carga = pat(semilla, i) (la fórmula de §Vectores dorados), META ASCII puro
 * construido aquí con los SHA-256 calculados. Nada se escribe a disco (AGENTS.md, regla 1).
 *
 * Cartucho de referencia de los importadores: huella = pat(0x10, 32), GB, con batería, RAM de
 * 32 KiB (32768 B; sin RTC). X1/X2 encajan; X3 (SAVE vacía) y X4 (8000 B) no deben tocar el .sav.
 */
#include <stdlib.h>
#include <string.h>

#include "pocketgb_pgbm.h"
#include "unit.h"

enum { X_SAV = 32768, X_STAT = 4096, X_SHORT = 8000, X_COUNT = 8 };

static void xpat(uint8_t *p, size_t n, unsigned seed)
{
    for (size_t i = 0; i < n; i++)
        p[i] = (uint8_t)(seed + i * 37u + (i >> 8) * 11u);
}

static void xhex(const uint8_t *d, size_t n, char *out)
{
    for (size_t i = 0; i < n; i++)
        snprintf(out + 2 * i, 3, "%02x", d[i]);
}

static void sha_hex_of_pat(unsigned seed, size_t n, char out[65])
{
    uint8_t *b = malloc(n ? n : 1), d[32];
    xpat(b, n, seed);
    sha256(b, n, d);
    xhex(d, 32, out);
    free(b);
}

/* META v1 en una línea, claves en este orden; base/state_of NULL = «null» / ausente. */
static int xmeta(char *out, size_t cap, int format, const char *rom, const char *sav, const char *base,
                 const char *platform, const char *name, unsigned long long created, const char *state_of,
                 const char *extra)
{
    char basebuf[70];
    if (base)
        snprintf(basebuf, sizeof basebuf, "\"%s\"", base);
    else
        snprintf(basebuf, sizeof basebuf, "null");
    int n = snprintf(out, cap,
                     "{\"format\":%d,\"rom_sha256\":\"%s\",\"sav_sha256\":\"%s\",\"base_sav_sha256\":%s,"
                     "\"device\":{\"platform\":\"%s\",\"name\":\"%s\"},\"created_ms\":%llu,"
                     "\"core\":{\"name\":\"gb\",\"version\":\"1.0.0\"}%s%s%s%s}",
                     format, rom, sav, basebuf, platform, name, created,
                     state_of ? ",\"state_of_sav_sha256\":\"" : "", state_of ? state_of : "", state_of ? "\"" : "",
                     extra ? extra : "");
    return n;
}

/* Construye X(which+1). Devuelve la longitud (0 si falla). `meta_out` recibe el META (o "" si no hay). */
static size_t build_cross(unsigned which, uint8_t *out, size_t cap, char *meta_out, size_t meta_cap)
{
    uint8_t rom[PGBM_ROMF_BYTES];
    xpat(rom, sizeof rom, 0x10);
    char romhex[65], s1[65], b22[65], s23[65], s24[65], s4[65], empty[65];
    xhex(rom, 32, romhex);
    sha_hex_of_pat(0x21, X_SAV, s1);
    sha_hex_of_pat(0x22, X_SAV, b22);
    sha_hex_of_pat(0x23, X_SAV, s23);
    sha_hex_of_pat(0x24, X_SAV, s24);
    sha_hex_of_pat(0x21, X_SHORT, s4);
    sha_hex_of_pat(0, 0, empty);

    uint8_t *sav = malloc(X_SAV), *state = malloc(X_STAT);
    pgbm_view v;
    memset(&v, 0, sizeof v);
    memcpy(v.rom_fp, rom, sizeof rom);
    meta_out[0] = 0;
    switch (which) {
    case 0: /* X1: Android envía; estado de esa misma partida (continuación exacta). */
        xpat(sav, X_SAV, 0x21);
        xpat(state, X_STAT, 0x42);
        v.sav.len = X_SAV;
        v.state.len = X_STAT;
        xmeta(meta_out, meta_cap, 1, romhex, s1, b22, "android", "Pixel de prueba", 1790003600000ULL, s1,
              ",\"play_time_ms\":7200000,\"title\":\"Prueba cruzada\",\"tags\":[\"cruzado\"]");
        break;
    case 1: /* X2: iPhone avanza desde X1; el estado es de otra partida (no se ofrece continuar). */
        xpat(sav, X_SAV, 0x23);
        xpat(state, X_STAT, 0x43);
        v.sav.len = X_SAV;
        v.state.len = X_STAT;
        xmeta(meta_out, meta_cap, 1, romhex, s23, s1, "ios", "iPhone de prueba", 1790007200000ULL, s24, NULL);
        break;
    case 2: /* X3: SAVE vacía para un juego con batería. */
        v.sav.len = 0;
        xmeta(meta_out, meta_cap, 1, romhex, empty, NULL, "android", "Pixel de prueba", 1790000000000ULL, NULL, NULL);
        break;
    case 3: /* X4: SAVE de tamaño distinto al del cartucho. */
        xpat(sav, X_SHORT, 0x21);
        v.sav.len = X_SHORT;
        xmeta(meta_out, meta_cap, 1, romhex, s4, NULL, "ios", "iPhone de prueba", 1790000000000ULL, NULL, NULL);
        break;
    case 4: /* X5: META de otra versión del esquema (format 2). */
        xpat(sav, X_SAV, 0x21);
        v.sav.len = X_SAV;
        xmeta(meta_out, meta_cap, 2, romhex, s1, NULL, "android", "Pixel de prueba", 1790000000000ULL, NULL, NULL);
        break;
    case 5: /* X6: sin META. */
        xpat(sav, X_SAV, 0x21);
        v.sav.len = X_SAV;
        break;
    case 7: /* X8 (ND20 h): clave repetida («created_ms» dos veces) → META inválida. */
        xpat(sav, X_SAV, 0x21);
        v.sav.len = X_SAV;
        xmeta(meta_out, meta_cap, 1, romhex, s1, NULL, "android", "Pixel de prueba", 1790000000000ULL, NULL, NULL);
        {
            /* Se inserta la clave repetida justo después del primer «created_ms». */
            char *p = strstr(meta_out, ",\"core\":");
            const char *dup = ",\"created_ms\":1790000000001";
            size_t tail = strlen(p), dl = strlen(dup);
            if (strlen(meta_out) + dl < meta_cap) {
                memmove(p + dl, p, tail + 1);
                memcpy(p, dup, dl);
            }
        }
        break;
    case 6: /* X7: sav_sha256 no corresponde a SAVE. */
        xpat(sav, X_SAV, 0x21);
        v.sav.len = X_SAV;
        xmeta(meta_out, meta_cap, 1, romhex, b22, NULL, "android", "Pixel de prueba", 1790000000000ULL, NULL, NULL);
        break;
    }
    v.sav.data = sav;
    if (v.state.len)
        v.state.data = state;
    if (meta_out[0]) {
        v.meta.data = (const uint8_t *)meta_out;
        v.meta.len = (uint32_t)strlen(meta_out);
    }
    size_t written = 0;
    pgbm_result r = pgbm_encode(&v, out, cap, &written);
    free(sav);
    free(state);
    return r == PGBM_OK ? written : 0;
}

/* Valores esperados (docs/12-formato-pgbm.md §Vectores cruzados; contrastados con Python). */
static const struct {
    unsigned len, meta_len;
    const char *sha;
} XEXP[X_COUNT] = {
    { 37480, 536, "b7d163cf9fda7b6ccbcd240b1a25b97518caccdda45b5a38e37a021e713bf796" },
    { 37410, 466, "e79d7993c7cd380ac7496f792766362e0689fe285ff2c06a9424508b40db5f2f" },
    { 390, 318, "c1f033894a9d9f7bc15cfcc353b776caf6303fadb51f7168829045cfbb430e17" },
    { 8387, 315, "336401621230102beadbadbdba1643acdc58da343fe36e2105d9e07966933771" },
    { 33158, 318, "8b1fb567aa21ffd5c907b59111c93eb4f67d7dba91bb57aff5e4b5804f30718a" },
    { 32832, 0, "c7f5650966a804b620f8d29a63766b2583d53a2a30384b030ceafe224b236a2e" },
    { 33158, 318, "6d8b3af30e6fe76adf7fef36fc21f80a3db0a86d4df1a8faa00d573b07a39880" },
    { 33185, 345, "08c1ad1b5c7422765a54d1e2d0bc19276c5feeb9db8d1fe49f7aadc0ebf7c95b" },
};

void unit_pgbm_cross(struct ut *t)
{
    uint8_t *buf = malloc(65536);
    char meta[1024];
    CHECK(t, buf != NULL);
    if (!buf)
        return;
    for (unsigned w = 0; w < X_COUNT; w++) {
        size_t n = build_cross(w, buf, 65536, meta, sizeof meta);
        uint8_t d[32];
        char h[65];
        sha256(buf, n, d);
        xhex(d, 32, h);
        if (n != XEXP[w].len || strcmp(h, XEXP[w].sha) != 0 || strlen(meta) != XEXP[w].meta_len)
            fprintf(stderr, "  X%u: len %zu meta %zu sha %s\n", w + 1, n, strlen(meta), h);
        CHECK(t, n == XEXP[w].len);
        CHECK(t, strlen(meta) == XEXP[w].meta_len);
        CHECK(t, strcmp(h, XEXP[w].sha) == 0);
        /* Todos son contenedores válidos: el rechazo de X3…X7 es de las apps, no del C. */
        uint8_t *exact = malloc(n);
        memcpy(exact, buf, n);
        pgbm_view v;
        CHECK(t, pgbm_parse(exact, n, &v) == PGBM_OK);
        CHECK(t, (v.meta.len != 0) == (w != 5));
        free(exact);
        /* Determinismo: una segunda construcción da los mismos bytes. */
        uint8_t *again = malloc(65536);
        size_t n2 = build_cross(w, again, 65536, meta, sizeof meta);
        CHECK(t, n2 == n && memcmp(again, buf, n) == 0);
        free(again);
    }
    free(buf);
}
