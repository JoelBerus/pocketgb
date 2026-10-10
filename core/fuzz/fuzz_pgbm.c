/*
 * fuzz_pgbm.c — libFuzzer: contenedor .pgbm (pgbm_parse / pgbm_encode / pgbm_encoded_size, N7-C).
 *
 * Entrada: [indicadores][bytes...]
 *   Modo lector (bit 7 = 0): los bytes son el archivo. El CRC y la cabecera harían que casi toda
 *     entrada muriera en la primera comprobación, así que los indicadores los reparan sobre una
 *     copia: bit 0 = escribir el mágico «PGBM» y la versión 1; bit 1 = poner la longitud total = la
 *     longitud real; bit 2 = recalcular el CRC-32 (con una tabla propia, independiente de
 *     state.c); bit 3 = poner el nº de secciones = las cabeceras de sección que caben.
 *     Combinándolos se llega a cada rechazo de estructura y a la rama de éxito.
 *   Modo codificador (bit 7 = 1): [huella: 32][longitudes de meta, thumb, state, sav: 4 bytes][datos].
 *     Los datos se reparten en ese orden (lo que falte se recorta). bit 6 = forzar la firma PNG
 *     al principio de THMB; bit 5 = corregir META a UTF-8 válido; bit 4 = pedir la versión 2.
 * Cada fragmento va a su propio bloque del montón de tamaño EXACTO, para que ASan detecte
 * cualquier lectura fuera de rango. Invariantes con abort(): determinismo; el fallo deja la salida
 * a cero; nunca se acepta un tipo desconocido crítico (mayúscula inicial; contra un recorrido propio);
 * spans dentro del buffer y de los topes; META UTF-8 estricto sin NUL y THMB con firma PNG (contra un
 * validador independiente de pgbm.c); lo que se lee se vuelve a codificar a un
 * paquete no mayor, que vuelve a leerse igual y cuyos bytes son idénticos al codificar de nuevo;
 * sin sitio (cap-1) nada se escribe; en el modo codificador el resultado coincide con el que
 * predice el validador independiente.
 *
 * Con -DFUZZ_STANDALONE se compila además un conductor sin libFuzzer (make fuzz-pgbm-smoke): parte
 * de las semillas del corpus y las muta al azar durante N segundos. No guía por cobertura.
 */
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>

#include "pocketgb_pgbm.h"

enum { HDR = 12, CRCB = 4, SHDR = 8, ROMF = PGBM_ROMF_BYTES };

enum {
    F_MAGIC = 0x01, F_TOTAL = 0x02, F_CRC = 0x04, F_COUNT = 0x08,
    F_VERSION2 = 0x10, F_UTF8 = 0x20, F_PNG = 0x40, F_ENCODE = 0x80
};

/* ---- Validadores independientes de pgbm.c ---- */

/* UTF-8 por decodificación (sobrelargos, sustitutos, rango) y sin NUL. */
static bool oracle_utf8(const uint8_t *p, size_t n)
{
    static const uint32_t min_cp[5] = { 0, 0, 0x80, 0x800, 0x10000 };
    size_t i = 0;
    while (i < n) {
        uint8_t c = p[i];
        size_t len;
        uint32_t cp;
        if (c < 0x80) { len = 1; cp = c; }
        else if ((c & 0xE0) == 0xC0) { len = 2; cp = c & 0x1Fu; }
        else if ((c & 0xF0) == 0xE0) { len = 3; cp = c & 0x0Fu; }
        else if ((c & 0xF8) == 0xF0) { len = 4; cp = c & 0x07u; }
        else return false;
        if (len > n - i)
            return false;
        for (size_t k = 1; k < len; k++) {
            if ((p[i + k] & 0xC0) != 0x80)
                return false;
            cp = (cp << 6) | (p[i + k] & 0x3Fu);
        }
        if (cp < min_cp[len] && len > 1)
            return false;
        if (cp > 0x10FFFFu || (cp >= 0xD800u && cp <= 0xDFFFu) || cp == 0)
            return false;
        i += len;
    }
    return true;
}

static bool oracle_png(const uint8_t *p, size_t n)
{
    static const uint8_t sig[8] = { 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };
    return n >= 8 && memcmp(p, sig, 8) == 0;
}

static uint32_t crc_table[256];
static bool crc_ready;

static uint32_t oracle_crc(const uint8_t *p, size_t n)
{
    if (!crc_ready) {
        for (uint32_t i = 0; i < 256; i++) {
            uint32_t c = i;
            for (int k = 0; k < 8; k++)
                c = (c & 1) ? 0xEDB88320u ^ (c >> 1) : c >> 1;
            crc_table[i] = c;
        }
        crc_ready = true;
    }
    uint32_t c = 0xFFFFFFFFu;
    for (size_t i = 0; i < n; i++)
        c = crc_table[(c ^ p[i]) & 0xFF] ^ (c >> 8);
    return ~c;
}

static void w16(uint8_t *p, uint32_t v)
{
    p[0] = (uint8_t)v;
    p[1] = (uint8_t)(v >> 8);
}

static void w32(uint8_t *p, uint32_t v)
{
    w16(p, v);
    w16(p + 2, v >> 16);
}

static uint32_t r32(const uint8_t *p)
{
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

/* ---- Comprobaciones ---- */

static bool span_inside(const pgbm_span *s, const uint8_t *base, size_t n)
{
    if (s->len == 0)
        return true;
    if (!s->data)
        return false;
    uintptr_t b = (uintptr_t)base, d = (uintptr_t)s->data;
    return d >= b && d - b <= n && s->len <= n - (d - b);
}

static bool views_equal(const pgbm_view *a, const pgbm_view *b)
{
    return memcmp(a->rom_fp, b->rom_fp, ROMF) == 0 &&
           a->meta.len == b->meta.len && a->sav.len == b->sav.len && a->state.len == b->state.len &&
           a->thumb.len == b->thumb.len &&
           (!a->meta.len || memcmp(a->meta.data, b->meta.data, a->meta.len) == 0) &&
           (!a->sav.len || memcmp(a->sav.data, b->sav.data, a->sav.len) == 0) &&
           (!a->state.len || memcmp(a->state.data, b->state.data, a->state.len) == 0) &&
           (!a->thumb.len || memcmp(a->thumb.data, b->thumb.data, a->thumb.len) == 0);
}

/* Lo que se escribe con éxito se lee igual, es canónico y no cabe en menos sitio. */
static void check_encode_roundtrip(const pgbm_view *in, size_t max_size)
{
    size_t size = pgbm_encoded_size(in);
    if (size == 0 || size > PGBM_MAX_TOTAL || size > max_size)
        abort();
    uint8_t *out = malloc(size);
    uint8_t *again = malloc(size);
    uint8_t *small = malloc(size);          /* tamaño size: se usa solo size-1 */
    if (!out || !again || !small) {
        free(out);
        free(again);
        free(small);
        return;
    }
    size_t w = 0, w2 = 0;
    if (pgbm_encode(in, out, size, &w) != PGBM_OK || w != size)
        abort();
    if (pgbm_encode(in, again, size, &w2) != PGBM_OK || w2 != size || memcmp(out, again, size) != 0)
        abort();                            /* determinista */
    memset(small, 0xA5, size);
    w = 99;
    if (pgbm_encode(in, small, size - 1, &w) != PGBM_ERR_NOSPACE || w != 0)
        abort();
    for (size_t i = 0; i < size; i++)
        if (small[i] != 0xA5)
            abort();                        /* sin sitio no se escribe nada */
    pgbm_view back;
    if (pgbm_parse(out, size, &back) != PGBM_OK || !views_equal(in, &back) || back.version != PGBM_VERSION)
        abort();
    if (r32(out + 8) != size || r32(out + size - CRCB) != oracle_crc(out, size - CRCB))
        abort();                            /* longitud y CRC contra el cálculo independiente */
    free(out);
    free(again);
    free(small);
}

/* Contra el recorrido propio de las secciones (independiente de walk_sections): si el paquete se leyó con éxito, no puede
 * llevar un tipo desconocido que empiece por mayúscula (convención de PNG). Un archivo aceptado tiene la estructura bien. */
static bool oracle_has_critical(const uint8_t *pkg, size_t n)
{
    static const char known[5][4] = { { 'R', 'O', 'M', 'F' }, { 'M', 'E', 'T', 'A' }, { 'T', 'H', 'M', 'B' },
                                      { 'S', 'A', 'V', 'E' }, { 'S', 'T', 'A', 'T' } };
    size_t pos = HDR, end = n - CRCB;
    for (unsigned i = 0, count = (unsigned)pkg[6] | ((unsigned)pkg[7] << 8); i < count && pos <= end && end - pos >= SHDR; i++) {
        const uint8_t *tag = pkg + pos;
        bool is_known = false;
        for (int k = 0; k < 5; k++)
            is_known = is_known || memcmp(tag, known[k], 4) == 0;
        if (!is_known && tag[0] >= 'A' && tag[0] <= 'Z')
            return true;
        pos += SHDR + r32(pkg + pos + 4);
    }
    return false;
}

static void check_parse(const uint8_t *pkg, size_t n)
{
    pgbm_view a, b;
    memset(&a, 0xA5, sizeof a);
    memset(&b, 0x5A, sizeof b);
    pgbm_result ra = pgbm_parse(pkg, n, &a);
    pgbm_result rb = pgbm_parse(pkg, n, &b);
    if (ra != rb || (unsigned)ra > (unsigned)PGBM_ERR_CRITICAL || ra == PGBM_ERR_ARG || ra == PGBM_ERR_NOSPACE)
        abort();
    if (ra != PGBM_OK) {
        pgbm_view zero;
        memset(&zero, 0, sizeof zero);
        if (memcmp(&a, &zero, sizeof a) != 0 || memcmp(&b, &zero, sizeof b) != 0)
            abort();                        /* un fallo deja la salida a cero */
        return;
    }
    if (!views_equal(&a, &b) || a.sav.data != b.sav.data || a.meta.data != b.meta.data ||
        a.state.data != b.state.data || a.thumb.data != b.thumb.data)
        abort();                            /* determinista, y sin bytes sin inicializar */
    if (a.version != PGBM_VERSION || n > PGBM_MAX_TOTAL)
        abort();
    if (a.meta.len > PGBM_MAX_META || a.sav.len > PGBM_MAX_SAV || a.state.len > PGBM_MAX_STATE ||
        a.thumb.len > PGBM_MAX_THUMB)
        abort();
    if (!span_inside(&a.meta, pkg, n) || !span_inside(&a.sav, pkg, n) || !span_inside(&a.state, pkg, n) ||
        !span_inside(&a.thumb, pkg, n))
        abort();
    if (!a.sav.data || (a.meta.len == 0 && a.meta.data) || (a.state.len == 0 && a.state.data) ||
        (a.thumb.len == 0 && a.thumb.data))
        abort();
    if (oracle_has_critical(pkg, n))
        abort();                            /* una sección crítica desconocida nunca se acepta */
    if (a.meta.len && !oracle_utf8(a.meta.data, a.meta.len))
        abort();
    if (a.thumb.len && !oracle_png(a.thumb.data, a.thumb.len))
        abort();
    check_encode_roundtrip(&a, n);
}

/* ---- Modo lector ---- */

static void run_parse(unsigned flags, const uint8_t *body, size_t n)
{
    uint8_t *pkg = malloc(n ? n : 1);
    if (!pkg)
        return;
    memcpy(pkg, body, n);
    if ((flags & F_MAGIC) && n >= 4)
        memcpy(pkg, "PGBM", 4);
    if ((flags & F_MAGIC) && n >= 6)
        w16(pkg + 4, PGBM_VERSION);
    if ((flags & F_COUNT) && n >= HDR) {
        size_t pos = HDR, count = 0;
        while (n >= CRCB && pos + SHDR <= n - CRCB) {
            uint32_t len = r32(pkg + pos + 4);
            if (len > n - CRCB - pos - SHDR)
                break;
            pos += SHDR + len;
            count++;
        }
        w16(pkg + 6, (uint32_t)count);
    }
    if ((flags & F_TOTAL) && n >= HDR)
        w32(pkg + 8, (uint32_t)n);
    if ((flags & F_CRC) && n >= HDR + CRCB)
        w32(pkg + n - CRCB, oracle_crc(pkg, n - CRCB));
    check_parse(pkg, n);
    free(pkg);
}

/* ---- Modo codificador ---- */

static uint8_t *take(const uint8_t **cur, size_t *left, size_t want, size_t *got)
{
    size_t n = want < *left ? want : *left;
    uint8_t *p = malloc(n ? n : 1);
    if (p)
        memcpy(p, *cur, n);
    *cur += n;
    *left -= n;
    *got = n;
    return p;
}

static void run_encode(unsigned flags, const uint8_t *body, size_t n)
{
    uint8_t head[ROMF + 4];
    memset(head, 0, sizeof head);
    memcpy(head, body, n < sizeof head ? n : sizeof head);
    const uint8_t *cur = body + (n < sizeof head ? n : sizeof head);
    size_t left = n - (n < sizeof head ? n : sizeof head);

    size_t ml, tl, sl, vl;
    uint8_t *meta = take(&cur, &left, head[ROMF + 0], &ml);
    uint8_t *thumb = take(&cur, &left, head[ROMF + 1], &tl);
    uint8_t *state = take(&cur, &left, head[ROMF + 2], &sl);
    uint8_t *sav = take(&cur, &left, head[ROMF + 3], &vl);
    if (!meta || !thumb || !state || !sav)
        goto done;
    if ((flags & F_PNG) && tl >= 8)
        memcpy(thumb, "\x89PNG\r\n\x1a\n", 8);
    if (flags & F_UTF8) {                   /* sustituye cada secuencia inválida byte a byte */
        size_t i = 0;
        while (i < ml) {
            size_t len = 0;
            for (size_t k = 1; k <= 4 && i + k <= ml; k++)
                if (oracle_utf8(meta + i, k)) {
                    len = k;
                    break;
                }
            if (len == 0) {
                meta[i] = '?';
                len = 1;
            }
            i += len;
        }
    }

    pgbm_view in;
    memset(&in, 0, sizeof in);
    memcpy(in.rom_fp, head, ROMF);
    in.meta = (pgbm_span){ ml ? meta : NULL, (uint32_t)ml };
    in.thumb = (pgbm_span){ tl ? thumb : NULL, (uint32_t)tl };
    in.state = (pgbm_span){ sl ? state : NULL, (uint32_t)sl };
    in.sav = (pgbm_span){ vl ? sav : NULL, (uint32_t)vl };
    in.version = (flags & F_VERSION2) ? 2 : 0;

    pgbm_result want = PGBM_OK;
    if (in.version != 0)
        want = PGBM_ERR_VERSION;
    else if (ml && !oracle_utf8(meta, ml))
        want = PGBM_ERR_UTF8;
    else if (tl && !oracle_png(thumb, tl))
        want = PGBM_ERR_PNG;

    size_t size = pgbm_encoded_size(&in);
    if (size == 0 || size > PGBM_MAX_TOTAL)
        abort();                            /* con ≤ 255 bytes por sección siempre cabe */
    uint8_t *out = malloc(size);
    if (out) {
        size_t w = 99;
        pgbm_result got = pgbm_encode(&in, out, size, &w);
        if (got != want || (got == PGBM_OK) != (w == size) || (got != PGBM_OK && w != 0))
            abort();
        if (got == PGBM_OK) {
            check_encode_roundtrip(&in, size);
            check_parse(out, size);
        }
        free(out);
    }
done:
    free(meta);
    free(thumb);
    free(state);
    free(sav);
}

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size);

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size)
{
    if (size < 1)
        return 0;
    unsigned flags = data[0];
    if (flags & F_ENCODE)
        run_encode(flags, data + 1, size - 1);
    else
        run_parse(flags, data + 1, size - 1);
    return 0;
}

#ifdef FUZZ_STANDALONE
/* Conductor sin libFuzzer: uso `fuzz_pgbm_smoke SEGUNDOS CARPETA_DE_SEMILLAS`. */
#include <dirent.h>
#include <stdio.h>
#include <time.h>

static uint64_t rng_state = 0x9E3779B97F4A7C15ull;

static uint32_t rnd(void)
{
    rng_state ^= rng_state << 13;
    rng_state ^= rng_state >> 7;
    rng_state ^= rng_state << 17;
    return (uint32_t)(rng_state >> 16);
}

enum { MAX_SEEDS = 256, MAX_LEN = 20000 };

int main(int argc, char **argv)
{
    if (argc < 3)
        return 2;
    double secs = atof(argv[1]);
    static uint8_t *seed[MAX_SEEDS];
    static size_t seed_len[MAX_SEEDS];
    int ns = 0;
    DIR *d = opendir(argv[2]);
    struct dirent *e;
    while (d && (e = readdir(d)) && ns < MAX_SEEDS) {
        char path[1024];
        snprintf(path, sizeof path, "%s/%s", argv[2], e->d_name);
        FILE *f = fopen(path, "rb");
        if (!f)
            continue;
        uint8_t *buf = malloc(MAX_LEN);
        size_t n = buf ? fread(buf, 1, MAX_LEN, f) : 0;
        fclose(f);
        if (n >= 1) {
            uint8_t *fit = realloc(buf, n);
            seed[ns] = fit ? fit : buf;
            seed_len[ns++] = n;
        } else {
            free(buf);
        }
    }
    if (d)
        closedir(d);
    if (!ns) {
        fprintf(stderr, "sin semillas en %s (make fuzz-pgbm-smoke las genera)\n", argv[2]);
        return 2;
    }
    static const uint32_t interesting[] = { 0, 1, 7, 8, 15, 16, 17, 0x7FFFFFFFu, 0x80000000u, 0xFFFFFFFFu, 0xFFFFFFF8u,
                                            PGBM_MAX_TOTAL, PGBM_MAX_TOTAL + 1, PGBM_MAX_META, PGBM_MAX_META + 1,
                                            PGBM_MAX_SAV, PGBM_MAX_SAV + 1, PGBM_MAX_STATE, PGBM_MAX_STATE + 1,
                                            PGBM_MAX_THUMB, PGBM_MAX_THUMB + 1 };
    uint8_t *work = malloc(MAX_LEN + 64);
    if (!work)
        return 2;
    clock_t end = clock() + (clock_t)(secs * CLOCKS_PER_SEC);
    unsigned long runs = 0;
    for (int i = 0; i < ns; i++) {              /* primero las semillas tal cual */
        LLVMFuzzerTestOneInput(seed[i], seed_len[i]);
        runs++;
    }
    while (clock() < end) {
        int s = (int)(rnd() % (uint32_t)ns);
        size_t n = seed_len[s];
        memcpy(work, seed[s], n);
        unsigned ops = 1 + rnd() % 6;
        for (unsigned k = 0; k < ops; k++) {
            switch (rnd() % 12) {
            case 11: {                                                       /* secuencias UTF-8 en el borde de la validez (como el diccionario de libFuzzer) */
                static const struct { uint8_t b[4]; uint8_t n; } seq[] = {
                    { { 0xED, 0xA0, 0x80 }, 3 }, { { 0xED, 0xBF, 0xBF }, 3 }, { { 0xED, 0x9F, 0xBF }, 3 },
                    { { 0xF4, 0x90, 0x80, 0x80 }, 4 }, { { 0xF4, 0x8F, 0xBF, 0xBF }, 4 }, { { 0xC0, 0x80 }, 2 },
                    { { 0xC1, 0xBF }, 2 }, { { 0xE0, 0x80, 0x80 }, 3 }, { { 0xE0, 0xA0, 0x80 }, 3 },
                    { { 0xF0, 0x80, 0x80, 0x80 }, 4 }, { { 0xF0, 0x90, 0x80, 0x80 }, 4 }, { { 0xF5, 0x80, 0x80, 0x80 }, 4 },
                    { { 0xEF, 0xBF, 0xBF }, 3 }, { { 0xC2, 0x80 }, 2 }, { { 0xE2, 0x82, 0xAC }, 3 }, { { 0x00 }, 1 },
                    { { 0x89, 'P', 'N', 'G' }, 4 }, { { 0x0D, 0x0A, 0x1A, 0x0A }, 4 },
                };
                size_t k = rnd() % (sizeof seq / sizeof seq[0]);
                if (n > seq[k].n)
                    memcpy(work + rnd() % (n - seq[k].n), seq[k].b, seq[k].n);
                break;
            }
            case 0: work[rnd() % n] ^= (uint8_t)(1u << (rnd() % 8)); break;
            case 1: work[rnd() % n] = (uint8_t)rnd(); break;
            case 10: {                                                       /* bytes que deciden la validez de UTF-8 y de la firma PNG */
                static const uint8_t edge[] = { 0x00, 0x7F, 0x80, 0x89, 0x8F, 0x90, 0x9F, 0xA0, 0xBF, 0xC0, 0xC1, 0xC2, 0xDF,
                                                0xE0, 0xED, 0xEF, 0xF0, 0xF4, 0xF5, 0xFF, 0x0D, 0x0A, 0x1A, 'P', 'N', 'G' };
                work[rnd() % n] = edge[rnd() % sizeof edge];
                break;
            }
            case 2:
            case 3:                                                         /* cabecera y campos de longitud */
                if (n > 1) {
                    size_t at = 1 + (rnd() & 1 ? rnd() % 16 : rnd() % (n - 1));
                    if (at + 4 <= n)
                        w32(work + at, interesting[rnd() % (sizeof interesting / sizeof interesting[0])]);
                }
                break;
            case 4: work[0] = (uint8_t)rnd(); break;                         /* indicadores */
            case 5: work[0] |= (uint8_t)(F_MAGIC | F_TOTAL | F_CRC | F_COUNT); break;
            case 6: n = 1 + rnd() % n; break;                                /* recorta */
            case 7:
                if (n + 64 < (size_t)MAX_LEN) {                              /* alarga */
                    memset(work + n, (int)(rnd() & 1 ? 0xFF : 0x00), 64);
                    n += 64;
                }
                break;
            case 8:                                                          /* copia un trozo sobre otro sitio */
                if (n > 8) {
                    size_t len = 1 + rnd() % 8, from = rnd() % (n - len), to = rnd() % (n - len);
                    memmove(work + to, work + from, len);
                }
                break;
            default:                                                         /* tipo de sección conocido en una posición cualquiera */
                if (n > 8) {
                    static const char *const tags[] = { "ROMF", "META", "THMB", "SAVE", "STAT", "XTRA", "xtra", "Zabc" };
                    memcpy(work + 1 + rnd() % (n - 5), tags[rnd() % 8], 4);
                }
                break;
            }
            if (n < 1)
                n = 1;
        }
        LLVMFuzzerTestOneInput(work, n);
        runs++;
    }
    printf("fuzz_pgbm_smoke: %lu ejecuciones en %.0f s, sin fallos\n", runs, secs);
    return 0;
}
#endif
