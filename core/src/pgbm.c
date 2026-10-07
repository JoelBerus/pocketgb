/*
 * pgbm.c — contenedor `.pgbm` (N7-C). API en include/pocketgb_pgbm.h; formato byte a byte,
 * topes y vectores dorados en docs/12-formato-pgbm.md.
 *
 *   "PGBM" | u16 versión (1) | u16 nº de secciones | u32 longitud total |
 *   secciones {u8 tipo[4], u32 longitud, bytes}... | u32 crc32
 *
 * Little-endian. El CRC-32 (IEEE 802.3) es el de state.c (`crc32_update`) y cubre todo lo que
 * precede al propio CRC. El archivo es entrada no confiable: se comprueba la longitud total
 * y el CRC antes de recorrer las secciones, y cada longitud de sección se compara con lo que
 * queda del contenedor (sin aritmética que pueda desbordar) antes de usarla. No hay malloc,
 * ni estado global, ni I/O; pgbm_parse no copia (los spans apuntan dentro del buffer).
 */
#include <string.h>

#include "internal.h"
#include "pocketgb_pgbm.h"

enum {
    HEADER_BYTES = 4 + 2 + 2 + 4,      /* mágico, versión, nº de secciones, longitud total */
    CRC_BYTES = 4,
    SECTION_HEADER_BYTES = 4 + 4       /* tipo y longitud */
};

static const uint8_t MAGIC[4] = { 'P', 'G', 'B', 'M' };
static const uint8_t PNG_SIGNATURE[8] = { 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A };

/* Secciones conocidas. Cada una ocupa un bit de la máscara de «vistas». */
enum kind { K_UNKNOWN = -1, K_ROMF = 0, K_META, K_THUMB, K_SAV, K_STATE, K_COUNT };

/* Matrices de char (no de punteros): sin relocaciones, así que van a .rodata incluso con -fPIC. */
static const char TAGS[K_COUNT][5] = { "ROMF", "META", "THMB", "SAVE", "STAT" };

static enum kind classify(const uint8_t *tag)
{
    for (int k = 0; k < K_COUNT; k++)
        if (memcmp(tag, TAGS[k], 4) == 0)
            return (enum kind)k;
    return K_UNKNOWN;
}

static uint32_t cap_of(enum kind k)
{
    switch (k) {
    case K_ROMF: return PGBM_ROMF_BYTES;
    case K_META: return PGBM_MAX_META;
    case K_THUMB: return PGBM_MAX_THUMB;
    case K_SAV: return PGBM_MAX_SAV;
    case K_STATE: return PGBM_MAX_STATE;
    default: return PGBM_MAX_TOTAL;
    }
}

/* ---- Little-endian ---- */

static uint32_t get16(const uint8_t *p)
{
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8);
}

static uint32_t get32(const uint8_t *p)
{
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

static void put16(uint8_t *p, uint32_t v)
{
    p[0] = (uint8_t)v;
    p[1] = (uint8_t)(v >> 8);
}

static void put32(uint8_t *p, uint32_t v)
{
    p[0] = (uint8_t)v;
    p[1] = (uint8_t)(v >> 8);
    p[2] = (uint8_t)(v >> 16);
    p[3] = (uint8_t)(v >> 24);
}

/* ---- Validación de contenido ---- */

/* UTF-8 estricto (RFC 3629: sin sobrelargos, sin sustitutos, hasta U+10FFFF) y sin NUL. */
static bool utf8_without_nul(const uint8_t *p, size_t n)
{
    size_t i = 0;
    while (i < n) {
        uint8_t c = p[i];
        if (c == 0)
            return false;
        if (c < 0x80) {
            i++;
            continue;
        }
        size_t need;                   /* bytes de continuación que siguen al primero */
        uint8_t lo = 0x80, hi = 0xBF;  /* rango del segundo byte */
        if (c >= 0xC2 && c <= 0xDF) {
            need = 1;
        } else if (c >= 0xE0 && c <= 0xEF) {
            need = 2;
            if (c == 0xE0)
                lo = 0xA0;             /* sin sobrelargos */
            else if (c == 0xED)
                hi = 0x9F;             /* sin sustitutos U+D800..DFFF */
        } else if (c >= 0xF0 && c <= 0xF4) {
            need = 3;
            if (c == 0xF0)
                lo = 0x90;
            else if (c == 0xF4)
                hi = 0x8F;             /* nada por encima de U+10FFFF */
        } else {
            return false;              /* continuación suelta, C0/C1, F5..FF */
        }
        if (n - i <= need)
            return false;              /* secuencia cortada por el final */
        if (p[i + 1] < lo || p[i + 1] > hi)
            return false;
        for (size_t k = 2; k <= need; k++)
            if ((p[i + k] & 0xC0) != 0x80)
                return false;
        i += need + 1;
    }
    return true;
}

static bool is_png(const uint8_t *p, size_t n)
{
    return n >= sizeof PNG_SIGNATURE && memcmp(p, PNG_SIGNATURE, sizeof PNG_SIGNATURE) == 0;
}

/* Un tipo de sección son 4 bytes ASCII imprimibles (sin espacio): 0x21..0x7E. */
static bool tag_is_ascii(const uint8_t *tag)
{
    for (int i = 0; i < 4; i++)
        if (tag[i] < 0x21 || tag[i] > 0x7E)
            return false;
    return true;
}

/* ---- Lectura ---- */

/* Recorre las secciones de buf[HEADER_BYTES, end) y rellena `v`. */
static pgbm_result walk_sections(const uint8_t *buf, size_t end, unsigned count, pgbm_view *v)
{
    unsigned seen = 0;
    size_t pos = HEADER_BYTES;
    for (unsigned i = 0; i < count; i++) {
        if (end - pos < SECTION_HEADER_BYTES)
            return PGBM_ERR_BOUNDS;
        const uint8_t *tag = buf + pos;
        uint32_t slen = get32(buf + pos + 4);
        pos += SECTION_HEADER_BYTES;
        if (!tag_is_ascii(tag))
            return PGBM_ERR_BOUNDS;
        if (slen > end - pos)
            return PGBM_ERR_BOUNDS;    /* la sección se sale del contenedor */
        enum kind k = classify(tag);
        if (k != K_UNKNOWN) {
            if (seen & (1u << k))
                return PGBM_ERR_DUPLICATE;
            seen |= 1u << k;
            if (k == K_ROMF && slen != PGBM_ROMF_BYTES)
                return PGBM_ERR_BOUNDS;
            if (slen > cap_of(k))
                return PGBM_ERR_TOO_LARGE;
            if (slen == 0 && k != K_SAV)
                return PGBM_ERR_BOUNDS;    /* una sección opcional presente no puede ir vacía */
            const uint8_t *data = buf + pos;
            switch (k) {
            case K_ROMF: memcpy(v->rom_fp, data, PGBM_ROMF_BYTES); break;
            case K_META: v->meta.data = data; v->meta.len = slen; break;
            case K_THUMB: v->thumb.data = data; v->thumb.len = slen; break;
            case K_SAV: v->sav.data = data; v->sav.len = slen; break;
            case K_STATE: v->state.data = data; v->state.len = slen; break;
            default: break;
            }
        }
        pos += slen;
    }
    if (pos != end)
        return PGBM_ERR_BOUNDS;        /* bytes que ninguna sección reclama */
    if (!(seen & (1u << K_ROMF)) || !(seen & (1u << K_SAV)))
        return PGBM_ERR_MISSING;
    if (v->meta.len && !utf8_without_nul(v->meta.data, v->meta.len))
        return PGBM_ERR_UTF8;
    if (v->thumb.len && !is_png(v->thumb.data, v->thumb.len))
        return PGBM_ERR_PNG;
    return PGBM_OK;
}

pgbm_result pgbm_parse(const uint8_t *buf, size_t len, pgbm_view *out)
{
    if (!out)
        return PGBM_ERR_ARG;
    memset(out, 0, sizeof *out);
    if (!buf)
        return PGBM_ERR_ARG;
    size_t have = len < sizeof MAGIC ? len : sizeof MAGIC;
    if (memcmp(buf, MAGIC, have) != 0)
        return PGBM_ERR_MAGIC;
    if (len < 6)
        return PGBM_ERR_TRUNCATED;
    uint32_t version = get16(buf + 4);
    if (version != PGBM_VERSION)
        return PGBM_ERR_VERSION;       /* otra versión puede tener otra cabecera: no se sigue */
    if (len < HEADER_BYTES)
        return PGBM_ERR_TRUNCATED;
    unsigned count = (unsigned)get16(buf + 6);
    uint32_t total = get32(buf + 8);
    if (total < HEADER_BYTES + CRC_BYTES)
        return PGBM_ERR_BOUNDS;
    if (total > PGBM_MAX_TOTAL)
        return PGBM_ERR_TOO_LARGE;
    if (len < total)
        return PGBM_ERR_TRUNCATED;
    if (len > total)
        return PGBM_ERR_BOUNDS;        /* el archivo trae bytes de más */
    if (crc32_update(0, buf, total - CRC_BYTES) != get32(buf + total - CRC_BYTES))
        return PGBM_ERR_CRC;
    if (count > PGBM_MAX_SECTIONS)
        return PGBM_ERR_TOO_LARGE;
    pgbm_view v;
    memset(&v, 0, sizeof v);
    pgbm_result r = walk_sections(buf, total - CRC_BYTES, count, &v);
    if (r != PGBM_OK)
        return r;
    v.version = (uint16_t)version;
    *out = v;
    return PGBM_OK;
}

/* ---- Escritura ---- */

static bool span_has_data(const pgbm_span *s)
{
    return s->len == 0 || s->data != NULL;
}

size_t pgbm_encoded_size(const pgbm_view *in)
{
    if (!in)
        return 0;
    if (!span_has_data(&in->meta) || !span_has_data(&in->sav) || !span_has_data(&in->state) ||
        !span_has_data(&in->thumb))
        return 0;
    if (in->meta.len > PGBM_MAX_META || in->sav.len > PGBM_MAX_SAV || in->state.len > PGBM_MAX_STATE ||
        in->thumb.len > PGBM_MAX_THUMB)
        return 0;
    size_t total = HEADER_BYTES + CRC_BYTES;
    total += SECTION_HEADER_BYTES + PGBM_ROMF_BYTES;     /* ROMF y SAVE se escriben siempre */
    total += SECTION_HEADER_BYTES + in->sav.len;
    if (in->meta.len)
        total += SECTION_HEADER_BYTES + in->meta.len;
    if (in->thumb.len)
        total += SECTION_HEADER_BYTES + in->thumb.len;
    if (in->state.len)
        total += SECTION_HEADER_BYTES + in->state.len;
    return total > PGBM_MAX_TOTAL ? 0 : total;
}

static uint8_t *put_section(uint8_t *p, enum kind k, const uint8_t *data, uint32_t len)
{
    memcpy(p, TAGS[k], 4);
    put32(p + 4, len);
    if (len)
        memcpy(p + SECTION_HEADER_BYTES, data, len);
    return p + SECTION_HEADER_BYTES + len;
}

pgbm_result pgbm_encode(const pgbm_view *in, uint8_t *out, size_t cap, size_t *written)
{
    if (written)
        *written = 0;
    if (!in || !out)
        return PGBM_ERR_ARG;
    if (!span_has_data(&in->meta) || !span_has_data(&in->sav) || !span_has_data(&in->state) ||
        !span_has_data(&in->thumb))
        return PGBM_ERR_ARG;
    if (in->version != 0 && in->version != PGBM_VERSION)
        return PGBM_ERR_VERSION;
    size_t total = pgbm_encoded_size(in);
    if (total == 0)
        return PGBM_ERR_TOO_LARGE;
    if (in->meta.len && !utf8_without_nul(in->meta.data, in->meta.len))
        return PGBM_ERR_UTF8;
    if (in->thumb.len && !is_png(in->thumb.data, in->thumb.len))
        return PGBM_ERR_PNG;
    if (cap < total)
        return PGBM_ERR_NOSPACE;

    unsigned count = 2u + (in->meta.len ? 1u : 0u) + (in->thumb.len ? 1u : 0u) + (in->state.len ? 1u : 0u);
    memcpy(out, MAGIC, sizeof MAGIC);
    put16(out + 4, PGBM_VERSION);
    put16(out + 6, count);
    put32(out + 8, (uint32_t)total);
    uint8_t *p = out + HEADER_BYTES;
    p = put_section(p, K_ROMF, in->rom_fp, PGBM_ROMF_BYTES);
    if (in->meta.len)
        p = put_section(p, K_META, in->meta.data, in->meta.len);
    if (in->thumb.len)
        p = put_section(p, K_THUMB, in->thumb.data, in->thumb.len);
    p = put_section(p, K_SAV, in->sav.data, in->sav.len);
    if (in->state.len)
        p = put_section(p, K_STATE, in->state.data, in->state.len);
    put32(p, crc32_update(0, out, total - CRC_BYTES));
    if (written)
        *written = total;
    return PGBM_OK;
}

const char *pgbm_result_name(pgbm_result r)
{
    /* Un solo bloque de texto sin punteros: una tabla de punteros iría a .data.rel.ro con -fPIC (NDK)
     * y `make check-globals` la tomaría por estado mutable. */
    static const char NAMES[PGBM_ERR_NOSPACE + 1][20] = {
        "PGBM_OK", "PGBM_ERR_MAGIC", "PGBM_ERR_VERSION", "PGBM_ERR_TRUNCATED", "PGBM_ERR_BOUNDS",
        "PGBM_ERR_CRC", "PGBM_ERR_DUPLICATE", "PGBM_ERR_MISSING", "PGBM_ERR_UTF8", "PGBM_ERR_PNG",
        "PGBM_ERR_TOO_LARGE", "PGBM_ERR_ARG", "PGBM_ERR_NOSPACE"
    };
    if ((unsigned)r > (unsigned)PGBM_ERR_NOSPACE)
        return "PGBM_ERR_UNKNOWN";
    return NAMES[r];
}
