/*
 * cart.c — medio de guardado y RTC del cartucho GBA (GBATEK §GBA Cart Backup
 * IDs, §GBA Cart Backup SRAM/FRAM, §Flash ROM, §EEPROM, §GBA Cart Real-Time
 * Clock). Código propio.
 *
 * - Detección: cadena de la biblioteca de Nintendo en el ROM (EEPROM_V,
 *   SRAM_V, SRAM_F_V, FLASH_V, FLASH512_V, FLASH1M_V) o el ajuste del juego.
 * - SRAM 32 KiB y Flash 64/128 KiB en 0x0E000000 (bus de 8 bits, espejos).
 * - EEPROM de 512 B / 8 KiB por bits en 0x0D000000 (0x0DFFFF00 con ROM de
 *   32 MiB); el tamaño se deduce de la longitud de la primera DMA si no viene
 *   del .sav ni del ajuste.
 * - RTC S-3511A por GPIO (0x080000C4–C9) en los juegos que lo llevan.
 * Todo índice se acota con el tamaño del medio (regla dura 3).
 */
#include "gba_internal.h"

/* IDs de fabricante/dispositivo que esperan los juegos (GBATEK). */
#define FLASH64_ID  0x1B32u   /* Panasonic MN63F805MNP */
#define FLASH128_ID 0x1362u   /* Sanyo LE26FV10N1TS */

static bool rom_has(const gba *g, const char *s)
{
    size_t n = strlen(s);
    if (g->rom_size < n) return false;
    for (uint32_t i = 0; i + n <= g->rom_size; i += 4)
        if (g->rom[i] == (uint8_t)s[0] && memcmp(&g->rom[i], s, n) == 0) return true;
    return false;
}

static uint32_t type_bytes(gba_save_type t)
{
    switch (t) {
    case GBA_SAVE_SRAM: return 32u * 1024u;
    case GBA_SAVE_FLASH64: return 64u * 1024u;
    case GBA_SAVE_FLASH128: return 128u * 1024u;
    case GBA_SAVE_EEPROM512: return 512u;
    case GBA_SAVE_EEPROM8K: return 8u * 1024u;
    default: return 0;
    }
}

/* Juegos con RTC por código (los tres primeros caracteres). */
static bool game_has_rtc(const gba *g)
{
    static const char codes[][4] = {"AXV", "AXP", "BPE", "U3I", "U32", "U33", "BR4", "BKA"};
    for (size_t i = 0; i < sizeof codes / sizeof codes[0]; i++)
        if (memcmp(&g->rom[0xAC], codes[i], 3) == 0) return true;
    return false;
}

void gba_cart_init(gba *g)
{
    gba_save_type t = g->opts.save_type;
    if (t == GBA_SAVE_AUTO) {
        if (rom_has(g, "EEPROM_V")) t = GBA_SAVE_EEPROM512;   /* tamaño provisional */
        else if (rom_has(g, "SRAM_V") || rom_has(g, "SRAM_F_V")) t = GBA_SAVE_SRAM;
        else if (rom_has(g, "FLASH1M_V")) t = GBA_SAVE_FLASH128;
        else if (rom_has(g, "FLASH_V") || rom_has(g, "FLASH512_V")) t = GBA_SAVE_FLASH64;
        else t = GBA_SAVE_NONE;
        g->eeprom.addr_bits = 0;                  /* EEPROM: se confirma con la primera DMA */
    } else {
        g->eeprom.addr_bits = t == GBA_SAVE_EEPROM8K ? 14 : 6;
    }
    g->save_type = t;
    g->save_bytes = type_bytes(t);
    memset(g->save, 0xFF, sizeof g->save);
    g->save_dirty = false;
    memset(&g->flash, 0, sizeof g->flash);
    g->eeprom.phase = 0;
    g->eeprom.nbits = 0;
    g->eeprom.read_pos = -1;
    g->eeprom.dma_len = 0;
    g->has_rtc = g->opts.rtc == GBA_RTC_ON || (g->opts.rtc == GBA_RTC_AUTO && game_has_rtc(g));
    memset(&g->rtc, 0, sizeof g->rtc);
    g->rtc.status = 0x40;                         /* 24 horas, sin fallo de alimentación */
    g->rtc_base = 0;
    g->rtc_base_cycles = 0;
    gba_rtc_set_time(g, g->opts.unix_time);
    g->rtc_base_cycles = 0;
}

/* ------------------------------------------------------------ SRAM y Flash */

uint8_t gba_cart_read8(gba *g, uint32_t addr)
{
    switch (g->save_type) {
    case GBA_SAVE_SRAM:
        return g->save[addr & 0x7FFFu];
    case GBA_SAVE_FLASH64:
    case GBA_SAVE_FLASH128: {
        uint32_t off = addr & 0xFFFFu;
        if (g->flash.id_mode && off < 2) {
            uint16_t id = g->save_type == GBA_SAVE_FLASH128 ? FLASH128_ID : FLASH64_ID;
            return (uint8_t)(id >> (off * 8u));
        }
        return g->save[((uint32_t)g->flash.bank << 16) + off];
    }
    default:
        return 0xFF;
    }
}

static void flash_write(gba *g, uint32_t off, uint8_t v)
{
    gba_flash *f = &g->flash;
    if (f->state == 3) {                          /* programar un byte: solo baja bits a 0 */
        uint8_t *p = &g->save[((uint32_t)f->bank << 16) + off];
        uint8_t nv = (uint8_t)(*p & v);
        if (nv != *p) { *p = nv; g->save_dirty = true; }
        f->state = 0;
        return;
    }
    if (f->state == 4) {                          /* elegir banco (solo 128 KiB) */
        if (off == 0) f->bank = v & 1u;
        f->state = 0;
        return;
    }
    if (f->state == 0) {
        if (off == 0x5555 && v == 0xAA) f->state = 1;
        else if (v == 0xF0) { f->id_mode = false; f->erase_armed = false; }   /* reinicio suelto */
        else f->erase_armed = false;
        return;
    }
    if (f->state == 1) {
        if (off == 0x2AAA && v == 0x55) f->state = 2;
        else { f->state = 0; f->erase_armed = false; }   /* secuencia rota */
        return;
    }
    /* state 2: comando */
    f->state = 0;
    if (f->erase_armed) {
        f->erase_armed = false;
        if (off == 0x5555 && v == 0x10) {         /* borrar el chip */
            memset(g->save, 0xFF, g->save_bytes);
            g->save_dirty = true;
        } else if (v == 0x30) {                   /* borrar un sector de 4 KiB */
            memset(&g->save[((uint32_t)f->bank << 16) + (off & 0xF000u)], 0xFF, 0x1000);
            g->save_dirty = true;
        }
        return;
    }
    if (off != 0x5555) return;
    switch (v) {
    case 0x90: f->id_mode = true; break;
    case 0xF0: f->id_mode = false; break;
    case 0x80: f->erase_armed = true; break;
    case 0xA0: f->state = 3; break;
    case 0xB0: if (g->save_type == GBA_SAVE_FLASH128) f->state = 4; break;
    default: break;
    }
}

void gba_cart_write8(gba *g, uint32_t addr, uint8_t v)
{
    switch (g->save_type) {
    case GBA_SAVE_SRAM:
        if (g->save[addr & 0x7FFFu] != v) {
            g->save[addr & 0x7FFFu] = v;
            g->save_dirty = true;
        }
        break;
    case GBA_SAVE_FLASH64:
    case GBA_SAVE_FLASH128:
        flash_write(g, addr & 0xFFFFu, v);
        break;
    default:
        break;
    }
}

/* ------------------------------------------------------------ EEPROM */

bool gba_cart_is_eeprom(const gba *g, uint32_t addr)
{
    if (g->save_type != GBA_SAVE_EEPROM512 && g->save_type != GBA_SAVE_EEPROM8K) return false;
    if ((addr >> 24) != 0x0D) return false;
    return g->rom_size <= 16u * 1024u * 1024u || addr >= 0x0DFFFF00u;
}

void gba_eeprom_dma(gba *g, uint32_t count)
{
    gba_eeprom *e = &g->eeprom;
    e->dma_len = count;
    if (e->addr_bits) return;
    /* Lectura: 2 + dirección + 1; escritura: 2 + dirección + 64 + 1. */
    if (count == 9 || count == 73) e->addr_bits = 6;
    else if (count == 17 || count == 81) e->addr_bits = 14;
    else return;
    g->save_type = e->addr_bits == 14 ? GBA_SAVE_EEPROM8K : GBA_SAVE_EEPROM512;
    g->save_bytes = type_bytes(g->save_type);
}

void gba_eeprom_write(gba *g, uint16_t v)
{
    gba_eeprom *e = &g->eeprom;
    unsigned bit = v & 1u;
    unsigned abits = e->addr_bits ? e->addr_bits : 6;
    switch (e->phase) {
    case 0:
        e->cmd = (uint8_t)((e->cmd << 1) | bit);
        if (++e->nbits == 2) {
            e->cmd &= 3u;
            if (e->cmd == 2 || e->cmd == 3) { e->phase = 1; e->addr = 0; }
            e->nbits = 0;
            if (e->cmd != 2 && e->cmd != 3) e->cmd = 0;
        }
        break;
    case 1:
        e->addr = (e->addr << 1) | bit;
        if (++e->nbits == abits) {
            e->nbits = 0;
            if (e->cmd == 3) e->phase = 3;
            else { e->phase = 2; e->data = 0; }
        }
        break;
    case 2:
        e->data = (e->data << 1) | bit;
        if (++e->nbits == 64) { e->nbits = 0; e->phase = 3; }
        break;
    default: {                                    /* bit final */
        uint32_t blocks = g->save_bytes / 8u;
        uint32_t block = blocks ? (e->addr & 0x3FFu) % blocks : 0;
        if (e->cmd == 2 && g->save_bytes) {
            for (int i = 0; i < 8; i++) {
                uint8_t b = (uint8_t)(e->data >> (56 - 8 * i));
                if (g->save[block * 8u + (uint32_t)i] != b) {
                    g->save[block * 8u + (uint32_t)i] = b;
                    g->save_dirty = true;
                }
            }
        } else if (e->cmd == 3) {
            e->read_addr = block;
            e->read_pos = 0;
        }
        e->phase = 0;
        e->cmd = 0;
        e->nbits = 0;
        break;
    }
    }
}

uint16_t gba_eeprom_read(gba *g)
{
    gba_eeprom *e = &g->eeprom;
    if (e->read_pos < 0) return 1;                /* listo */
    int pos = e->read_pos++;
    if (e->read_pos >= 68) e->read_pos = -1;
    if (pos < 4) return 0;
    int b = pos - 4;
    uint8_t byte = g->save_bytes ? g->save[e->read_addr * 8u + (uint32_t)(b >> 3)] : 0xFF;
    return (uint16_t)((byte >> (7 - (b & 7))) & 1u);
}

/* ------------------------------------------------------------ RTC (S-3511A) */

static uint8_t bcd(unsigned v) { return (uint8_t)(((v / 10u) << 4) | (v % 10u)); }

/* Días desde 1970-01-01 → (año, mes, día); algoritmo civil estándar. */
static void civil(int64_t days, int *y, unsigned *m, unsigned *d)
{
    days += 719468;
    int64_t era = (days >= 0 ? days : days - 146096) / 146097;
    unsigned doe = (unsigned)(days - era * 146097);
    unsigned yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    unsigned doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    unsigned mp = (5 * doy + 2) / 153;
    *d = doy - (153 * mp + 2) / 5 + 1;
    *m = mp < 10 ? mp + 3 : mp - 9;
    *y = (int)(yoe + era * 400) + (*m <= 2);
}

static int64_t rtc_now(const gba *g)
{
    return g->rtc_base + (int64_t)((g->cycles - g->rtc_base_cycles) / GBA_CLOCK_HZ) + g->rtc.offset;
}

static void rtc_fill(gba *g, unsigned cmd)
{
    gba_rtc *r = &g->rtc;
    int64_t t = rtc_now(g);
    int64_t days = t >= 0 ? t / 86400 : -((-t + 86399) / 86400);
    int64_t secs = t - days * 86400;
    int y; unsigned m, d;
    civil(days, &y, &m, &d);
    unsigned wd = (unsigned)(((days % 7) + 11) % 7);   /* 1970-01-01 fue jueves (4) */
    unsigned hh = (unsigned)(secs / 3600), mm = (unsigned)(secs / 60 % 60), ss = (unsigned)(secs % 60);
    uint8_t hour = bcd(hh);
    if (!(r->status & 0x40)) hour = (uint8_t)(bcd(hh % 12) | (hh >= 12 ? 0x80 : 0));
    else if (hh >= 12) hour |= 0x80;              /* bit AM/PM también en 24 h */
    if (cmd == 2) {
        int yy = y - 2000;
        if (yy < 0) yy = 0;
        r->buf[0] = bcd((unsigned)yy % 100u); r->buf[1] = bcd(m); r->buf[2] = bcd(d); r->buf[3] = (uint8_t)wd;
        r->buf[4] = hour; r->buf[5] = bcd(mm); r->buf[6] = bcd(ss);
        r->nbytes = 7;
    } else if (cmd == 3) {
        r->buf[0] = hour; r->buf[1] = bcd(mm); r->buf[2] = bcd(ss);
        r->nbytes = 3;
    } else if (cmd == 1) {
        r->buf[0] = r->status;
        r->nbytes = 1;
    } else {
        r->nbytes = 0;
    }
}

static unsigned unbcd(uint8_t v) { return (v >> 4) * 10u + (v & 15u); }

static void rtc_commit(gba *g)
{
    gba_rtc *r = &g->rtc;
    if (r->cmd == 1 && r->bytepos >= 1) {
        r->status = (uint8_t)((r->buf[0] & 0x6Au));
    } else if (r->cmd == 2 && r->bytepos >= 7) {
        /* El juego fija fecha y hora: se guarda como desplazamiento. */
        int y = 2000 + (int)unbcd(r->buf[0]);
        unsigned m = unbcd(r->buf[1]), d = unbcd(r->buf[2]);
        unsigned hh = unbcd(r->buf[4] & 0x3F), mm = unbcd(r->buf[5]), ss = unbcd(r->buf[6]);
        if (!(r->status & 0x40) && (r->buf[4] & 0x80) && hh < 12) hh += 12;   /* 12 h con PM */
        if (m < 1 || m > 12 || d < 1 || d > 31 || hh > 23 || mm > 59 || ss > 59) return;
        int yy = y - (m <= 2);
        int64_t era = yy / 400;
        unsigned yoe = (unsigned)(yy - era * 400);
        unsigned doy = (153 * (m > 2 ? m - 3 : m + 9) + 2) / 5 + d - 1;
        unsigned doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        int64_t days = era * 146097 + (int64_t)doe - 719468;
        int64_t want = days * 86400 + hh * 3600 + mm * 60 + ss;
        int64_t no = r->offset + (want - rtc_now(g));
        if (no <= GBA_RTC_MAX_OFFSET && no >= -GBA_RTC_MAX_OFFSET) r->offset = no;
    } else if (r->cmd == 0) {
        r->status = 0;
        r->offset = 0;
    }
}

bool gba_gpio_readable(const gba *g, uint32_t addr)
{
    return g->has_rtc && (g->rtc.ctrl & 1u) && addr >= 0x080000C4u && addr <= 0x080000C9u;
}

uint16_t gba_gpio_read(gba *g, uint32_t addr)
{
    switch (addr & ~1u) {
    case 0x080000C4u: return g->rtc.data & 0xFu;
    case 0x080000C6u: return g->rtc.dir & 0xFu;
    default: return g->rtc.ctrl & 1u;
    }
}

/* Pines: 0 = SCK, 1 = SIO, 2 = CS. Comando MSB primero; datos LSB primero. */
static void rtc_pins(gba *g, uint8_t pins)
{
    gba_rtc *r = &g->rtc;
    uint8_t old = r->data;
    uint8_t outmask = r->dir & 7u;
    uint8_t v = (uint8_t)((old & ~outmask) | (pins & outmask));
    bool cs = v & 4u, sck = v & 1u, old_sck = old & 1u;
    if (!cs) {
        if (r->state == 2 && !r->reading) rtc_commit(g);
        r->state = 0;
        r->data = v;
        return;
    }
    if (r->state == 0 && (old & 4u) == 0) {      /* CS sube: empieza un comando */
        r->state = 1;
        r->bitpos = 0;
        r->shift = 0;
    }
    if (!old_sck && sck) {                         /* flanco de subida de SCK */
        if (r->state == 1) {
            r->shift = (uint8_t)((r->shift << 1) | ((v >> 1) & 1u));
            if (++r->bitpos == 8) {
                uint8_t c = r->shift;
                if ((c >> 4) != 6) {               /* algunos juegos lo mandan invertido */
                    uint8_t rev = 0;
                    for (int i = 0; i < 8; i++) rev = (uint8_t)(rev | (((c >> i) & 1u) << (7 - i)));
                    c = rev;
                }
                r->cmd = (c >> 1) & 7u;
                r->reading = c & 1u;
                r->state = 2;
                r->bitpos = 0;
                r->bytepos = 0;
                memset(r->buf, 0, sizeof r->buf);
                if (r->reading) rtc_fill(g, r->cmd);
                else if (r->cmd == 0 || r->cmd == 6) rtc_commit(g);
            }
        } else if (r->state == 2 && !r->reading && r->bytepos < sizeof r->buf) {
            r->buf[r->bytepos] = (uint8_t)(r->buf[r->bytepos] | (((v >> 1) & 1u) << r->bitpos));
            if (++r->bitpos == 8) { r->bitpos = 0; r->bytepos++; }
        } else if (r->state == 2 && r->reading) {
            /* Lectura: en cada flanco de subida el RTC pone el bit siguiente en
             * SIO (LSB primero) y el juego lo lee con SCK alto. */
            unsigned b = r->bytepos < r->nbytes ? (r->buf[r->bytepos] >> r->bitpos) & 1u : 1u;
            if (!(r->dir & 2u)) v = (uint8_t)((v & ~2u) | (b << 1));
            if (++r->bitpos == 8) { r->bitpos = 0; r->bytepos++; }
        }
    }
    r->data = v;
}

void gba_gpio_write(gba *g, uint32_t addr, uint16_t v)
{
    if (!g->has_rtc) return;
    switch (addr & ~1u) {
    case 0x080000C4u: rtc_pins(g, (uint8_t)(v & 0xFu)); break;
    case 0x080000C6u: g->rtc.dir = (uint8_t)(v & 0xFu); break;
    case 0x080000C8u: g->rtc.ctrl = (uint8_t)(v & 1u); break;
    default: break;
    }
}

/* ------------------------------------------------------------ API */

gba_result gba_save_load(gba *g, const uint8_t *data, size_t len)
{
    if (!g || !data) return GBA_ERR_NULL_ARG;
    if (!g->rom) return GBA_ERR_NO_ROM;
    bool eeprom = g->save_type == GBA_SAVE_EEPROM512 || g->save_type == GBA_SAVE_EEPROM8K;
    if (eeprom && g->opts.save_type == GBA_SAVE_AUTO && g->eeprom.addr_bits == 0 && (len == 512 || len == 8192)) {
        /* EEPROM sin ajuste y sin tamaño confirmado: el tamaño del .sav decide.
         * Una vez confirmado (por un .sav o por la DMA) ya no cambia: así una
         * recarga de otro tamaño nunca trunca la partida (auditoría G4, A1). */
        g->save_type = len == 8192 ? GBA_SAVE_EEPROM8K : GBA_SAVE_EEPROM512;
        g->save_bytes = (uint32_t)len;
        g->eeprom.addr_bits = len == 8192 ? 14 : 6;
    }
    if (len != g->save_bytes || len == 0) return GBA_ERR_SAVE_SIZE;
    memcpy(g->save, data, len);
    g->save_dirty = false;
    return GBA_OK;
}

size_t gba_save_size(const gba *g) { return g ? g->save_bytes : 0; }

gba_result gba_save_write(const gba *g, uint8_t *out, size_t cap)
{
    if (!g || !out) return GBA_ERR_NULL_ARG;
    if (!g->rom) return GBA_ERR_NO_ROM;
    if (cap < g->save_bytes) return GBA_ERR_BUFFER_TOO_SMALL;
    memcpy(out, g->save, g->save_bytes);
    return GBA_OK;
}

bool gba_save_dirty(const gba *g) { return g && g->save_dirty; }
void gba_save_clear_dirty(gba *g) { if (g) g->save_dirty = false; }

/* Formato del .rtc (16 bytes, little-endian): 0..7 desplazamiento en segundos
 * que fijó el juego, 8 registro de control, 9..15 cero. */
gba_result gba_rtc_load(gba *g, const uint8_t *data, size_t len)
{
    if (!g || !data) return GBA_ERR_NULL_ARG;
    if (len != GBA_RTC_BYTES) return GBA_ERR_SAVE_SIZE;
    uint64_t off = 0;
    for (int i = 7; i >= 0; i--) off = (off << 8) | data[i];
    /* ±200 años: un .rtc dañado no puede desbordar la hora (auditoría G4, M2). */
    if ((int64_t)off > GBA_RTC_MAX_OFFSET || (int64_t)off < -GBA_RTC_MAX_OFFSET) return GBA_ERR_SAVE_SIZE;
    g->rtc.offset = (int64_t)off;
    g->rtc.status = data[8] & 0x6Au;
    return GBA_OK;
}

gba_result gba_rtc_save(const gba *g, uint8_t *out, size_t cap)
{
    if (!g || !out) return GBA_ERR_NULL_ARG;
    if (cap < GBA_RTC_BYTES) return GBA_ERR_BUFFER_TOO_SMALL;
    memset(out, 0, GBA_RTC_BYTES);
    uint64_t off = (uint64_t)g->rtc.offset;
    for (int i = 0; i < 8; i++) out[i] = (uint8_t)(off >> (8 * i));
    out[8] = g->rtc.status;
    return GBA_OK;
}

void gba_rtc_set_time(gba *g, int64_t unix_time)
{
    if (!g) return;
    if (unix_time > GBA_RTC_MAX_OFFSET * 2) unix_time = GBA_RTC_MAX_OFFSET * 2;
    if (unix_time < -GBA_RTC_MAX_OFFSET * 2) unix_time = -GBA_RTC_MAX_OFFSET * 2;
    g->rtc_base = unix_time;
    g->rtc_base_cycles = g->cycles;
}
