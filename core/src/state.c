/*
 * state.c — save states (docs/03-core-spec.md §Save states).
 *
 *   "PGBS" | u32 versión (3) | u8 sha256_rom[32] | u32 modelo (1 DMG, 2 CGB, 3 compat.) |
 *   secciones {u32 tag, u32 len, bytes}... | u32 crc32
 *
 * Little-endian. Cada campo se serializa por separado (sin memcpy de structs).
 * Un único recorrido (`visit`) sirve para medir, guardar, validar y aplicar:
 * gb_state_load hace primero una pasada que solo valida (CRC, huella, tags,
 * longitudes y rangos de cada campo) y solo si todo es correcto una segunda que
 * escribe en la instancia. Nunca confía en longitudes del archivo y no reserva
 * memoria. CRC-32 (IEEE 802.3, polinomio reflejado 0xEDB88320) con tabla
 * constante generada fuera de línea.
 */
#include <string.h>

#include "internal.h"

/* v2: sección APU (M5) · v3: VRAM/WRAM con bancos y sección CGB (M8) */
enum { STATE_VERSION = 3, STATE_MODEL_DMG = 1, STATE_MODEL_CGB = 2, STATE_MODEL_COMPAT = 3 };
enum { HEADER_BYTES = 4 + 4 + 32 + 4, CRC_BYTES = 4 };

static const uint32_t crc_table[256] = {
    0x00000000, 0x77073096, 0xee0e612c, 0x990951ba, 0x076dc419, 0x706af48f,
    0xe963a535, 0x9e6495a3, 0x0edb8832, 0x79dcb8a4, 0xe0d5e91e, 0x97d2d988,
    0x09b64c2b, 0x7eb17cbd, 0xe7b82d07, 0x90bf1d91, 0x1db71064, 0x6ab020f2,
    0xf3b97148, 0x84be41de, 0x1adad47d, 0x6ddde4eb, 0xf4d4b551, 0x83d385c7,
    0x136c9856, 0x646ba8c0, 0xfd62f97a, 0x8a65c9ec, 0x14015c4f, 0x63066cd9,
    0xfa0f3d63, 0x8d080df5, 0x3b6e20c8, 0x4c69105e, 0xd56041e4, 0xa2677172,
    0x3c03e4d1, 0x4b04d447, 0xd20d85fd, 0xa50ab56b, 0x35b5a8fa, 0x42b2986c,
    0xdbbbc9d6, 0xacbcf940, 0x32d86ce3, 0x45df5c75, 0xdcd60dcf, 0xabd13d59,
    0x26d930ac, 0x51de003a, 0xc8d75180, 0xbfd06116, 0x21b4f4b5, 0x56b3c423,
    0xcfba9599, 0xb8bda50f, 0x2802b89e, 0x5f058808, 0xc60cd9b2, 0xb10be924,
    0x2f6f7c87, 0x58684c11, 0xc1611dab, 0xb6662d3d, 0x76dc4190, 0x01db7106,
    0x98d220bc, 0xefd5102a, 0x71b18589, 0x06b6b51f, 0x9fbfe4a5, 0xe8b8d433,
    0x7807c9a2, 0x0f00f934, 0x9609a88e, 0xe10e9818, 0x7f6a0dbb, 0x086d3d2d,
    0x91646c97, 0xe6635c01, 0x6b6b51f4, 0x1c6c6162, 0x856530d8, 0xf262004e,
    0x6c0695ed, 0x1b01a57b, 0x8208f4c1, 0xf50fc457, 0x65b0d9c6, 0x12b7e950,
    0x8bbeb8ea, 0xfcb9887c, 0x62dd1ddf, 0x15da2d49, 0x8cd37cf3, 0xfbd44c65,
    0x4db26158, 0x3ab551ce, 0xa3bc0074, 0xd4bb30e2, 0x4adfa541, 0x3dd895d7,
    0xa4d1c46d, 0xd3d6f4fb, 0x4369e96a, 0x346ed9fc, 0xad678846, 0xda60b8d0,
    0x44042d73, 0x33031de5, 0xaa0a4c5f, 0xdd0d7cc9, 0x5005713c, 0x270241aa,
    0xbe0b1010, 0xc90c2086, 0x5768b525, 0x206f85b3, 0xb966d409, 0xce61e49f,
    0x5edef90e, 0x29d9c998, 0xb0d09822, 0xc7d7a8b4, 0x59b33d17, 0x2eb40d81,
    0xb7bd5c3b, 0xc0ba6cad, 0xedb88320, 0x9abfb3b6, 0x03b6e20c, 0x74b1d29a,
    0xead54739, 0x9dd277af, 0x04db2615, 0x73dc1683, 0xe3630b12, 0x94643b84,
    0x0d6d6a3e, 0x7a6a5aa8, 0xe40ecf0b, 0x9309ff9d, 0x0a00ae27, 0x7d079eb1,
    0xf00f9344, 0x8708a3d2, 0x1e01f268, 0x6906c2fe, 0xf762575d, 0x806567cb,
    0x196c3671, 0x6e6b06e7, 0xfed41b76, 0x89d32be0, 0x10da7a5a, 0x67dd4acc,
    0xf9b9df6f, 0x8ebeeff9, 0x17b7be43, 0x60b08ed5, 0xd6d6a3e8, 0xa1d1937e,
    0x38d8c2c4, 0x4fdff252, 0xd1bb67f1, 0xa6bc5767, 0x3fb506dd, 0x48b2364b,
    0xd80d2bda, 0xaf0a1b4c, 0x36034af6, 0x41047a60, 0xdf60efc3, 0xa867df55,
    0x316e8eef, 0x4669be79, 0xcb61b38c, 0xbc66831a, 0x256fd2a0, 0x5268e236,
    0xcc0c7795, 0xbb0b4703, 0x220216b9, 0x5505262f, 0xc5ba3bbe, 0xb2bd0b28,
    0x2bb45a92, 0x5cb36a04, 0xc2d7ffa7, 0xb5d0cf31, 0x2cd99e8b, 0x5bdeae1d,
    0x9b64c2b0, 0xec63f226, 0x756aa39c, 0x026d930a, 0x9c0906a9, 0xeb0e363f,
    0x72076785, 0x05005713, 0x95bf4a82, 0xe2b87a14, 0x7bb12bae, 0x0cb61b38,
    0x92d28e9b, 0xe5d5be0d, 0x7cdcefb7, 0x0bdbdf21, 0x86d3d2d4, 0xf1d4e242,
    0x68ddb3f8, 0x1fda836e, 0x81be16cd, 0xf6b9265b, 0x6fb077e1, 0x18b74777,
    0x88085ae6, 0xff0f6a70, 0x66063bca, 0x11010b5c, 0x8f659eff, 0xf862ae69,
    0x616bffd3, 0x166ccf45, 0xa00ae278, 0xd70dd2ee, 0x4e048354, 0x3903b3c2,
    0xa7672661, 0xd06016f7, 0x4969474d, 0x3e6e77db, 0xaed16a4a, 0xd9d65adc,
    0x40df0b66, 0x37d83bf0, 0xa9bcae53, 0xdebb9ec5, 0x47b2cf7f, 0x30b5ffe9,
    0xbdbdf21c, 0xcabac28a, 0x53b39330, 0x24b4a3a6, 0xbad03605, 0xcdd70693,
    0x54de5729, 0x23d967bf, 0xb3667a2e, 0xc4614ab8, 0x5d681b02, 0x2a6f2b94,
    0xb40bbe37, 0xc30c8ea1, 0x5a05df1b, 0x2d02ef8d
};

uint32_t crc32_update(uint32_t crc, const uint8_t *data, size_t len)
{
    crc = ~crc;
    for (size_t i = 0; i < len; i++)
        crc = crc_table[(crc ^ data[i]) & 0xFF] ^ (crc >> 8);
    return ~crc;
}

/* ---- Recorrido de campos ---- */

enum io_mode { IO_COUNT, IO_SAVE, IO_CHECK, IO_APPLY };

struct io {
    enum io_mode mode;
    uint8_t *out;          /* IO_SAVE */
    const uint8_t *in;     /* IO_CHECK / IO_APPLY */
    size_t len, pos;       /* len: tamaño del búfer (sin el CRC final al leer) */
    bool ok;
};

static bool loading(const struct io *io)
{
    return io->mode == IO_CHECK || io->mode == IO_APPLY;
}

/* Entero sin signo de `n` bytes. Al leer, rechaza valores > max. */
static uint64_t io_uint(struct io *io, uint64_t value, unsigned n, uint64_t max)
{
    if (!io->ok)
        return value;
    if (io->mode == IO_COUNT) {
        io->pos += n;
        return value;
    }
    if (io->len - io->pos < n) {   /* pos ≤ len siempre */
        io->ok = false;
        return value;
    }
    if (io->mode == IO_SAVE) {
        for (unsigned i = 0; i < n; i++)
            io->out[io->pos + i] = (uint8_t)(value >> (8 * i));
        io->pos += n;
        return value;
    }
    uint64_t v = 0;
    for (unsigned i = 0; i < n; i++)
        v |= (uint64_t)io->in[io->pos + i] << (8 * i);
    io->pos += n;
    if (v > max) {
        io->ok = false;
        return value;
    }
    return v;
}

static void u8(struct io *io, uint8_t *f, uint8_t max)
{
    uint8_t v = (uint8_t)io_uint(io, *f, 1, max);
    if (io->mode == IO_APPLY)
        *f = v;
}

/* Byte cuyos bits fuera de `mask` deben ser 0. */
static void u8m(struct io *io, uint8_t *f, uint8_t mask)
{
    uint8_t v = (uint8_t)io_uint(io, *f, 1, 0xFF);
    if (loading(io) && (v & ~mask))
        io->ok = false;
    if (io->mode == IO_APPLY)
        *f = v;
}

static void u16(struct io *io, uint16_t *f, uint16_t max)
{
    uint16_t v = (uint16_t)io_uint(io, *f, 2, max);
    if (io->mode == IO_APPLY)
        *f = v;
}

static void u32(struct io *io, uint32_t *f, uint32_t max)
{
    uint32_t v = (uint32_t)io_uint(io, *f, 4, max);
    if (io->mode == IO_APPLY)
        *f = v;
}

static void u64(struct io *io, uint64_t *f)
{
    uint64_t v = io_uint(io, *f, 8, UINT64_MAX);
    if (io->mode == IO_APPLY)
        *f = v;
}

static void i64(struct io *io, int64_t *f)
{
    uint64_t v = io_uint(io, (uint64_t)*f, 8, UINT64_MAX);
    if (io->mode == IO_APPLY)
        *f = (int64_t)v;
}

static void flag(struct io *io, bool *f)
{
    uint8_t v = (uint8_t)io_uint(io, *f ? 1 : 0, 1, 1);
    if (io->mode == IO_APPLY)
        *f = v != 0;
}

static void bytes(struct io *io, uint8_t *f, size_t n)
{
    if (!io->ok)
        return;
    if (io->mode == IO_COUNT) {
        io->pos += n;
        return;
    }
    if (io->len - io->pos < n) {
        io->ok = false;
        return;
    }
    if (io->mode == IO_SAVE)
        memcpy(io->out + io->pos, f, n);
    else if (io->mode == IO_APPLY)
        memcpy(f, io->in + io->pos, n);
    io->pos += n;
}

/* Sección: tag + longitud. Al leer, el tag debe coincidir y la longitud declarada
 * debe ser exactamente la que consumen los campos (se comprueba en section_end). */
static size_t section_begin(struct io *io, uint32_t tag)
{
    uint32_t t = tag, len = 0;
    u32(io, &t, UINT32_MAX);
    size_t len_pos = io->pos;
    u32(io, &len, UINT32_MAX);
    if (loading(io) && io->ok) {
        uint32_t read_tag = (uint32_t)io->in[len_pos - 4] | (uint32_t)io->in[len_pos - 3] << 8 |
                            (uint32_t)io->in[len_pos - 2] << 16 | (uint32_t)io->in[len_pos - 1] << 24;
        uint32_t read_len = (uint32_t)io->in[len_pos] | (uint32_t)io->in[len_pos + 1] << 8 |
                            (uint32_t)io->in[len_pos + 2] << 16 | (uint32_t)io->in[len_pos + 3] << 24;
        if (read_tag != tag || read_len > io->len - io->pos)
            io->ok = false;
    }
    return len_pos;
}

static void section_end(struct io *io, size_t len_pos)
{
    if (!io->ok || io->mode == IO_COUNT)
        return;
    size_t body = io->pos - len_pos - 4;
    if (io->mode == IO_SAVE) {
        for (int i = 0; i < 4; i++)
            io->out[len_pos + i] = (uint8_t)(body >> (8 * i));
        return;
    }
    uint32_t read_len = (uint32_t)io->in[len_pos] | (uint32_t)io->in[len_pos + 1] << 8 |
                        (uint32_t)io->in[len_pos + 2] << 16 | (uint32_t)io->in[len_pos + 3] << 24;
    if (read_len != body)
        io->ok = false;
}

#define TAG(a, b, c, d) ((uint32_t)(a) | (uint32_t)(b) << 8 | (uint32_t)(c) << 16 | (uint32_t)(d) << 24)

static void visit(struct io *io, gb *g)
{
    size_t s;

    struct gb_cpu *c = &g->cpu;
    s = section_begin(io, TAG('C', 'P', 'U', ' '));
    u8(io, &c->a, 0xFF); u8m(io, &c->f, 0xF0);
    u8(io, &c->b, 0xFF); u8(io, &c->c, 0xFF); u8(io, &c->d, 0xFF); u8(io, &c->e, 0xFF);
    u8(io, &c->h, 0xFF); u8(io, &c->l, 0xFF);
    u16(io, &c->sp, 0xFFFF); u16(io, &c->pc, 0xFFFF);
    flag(io, &c->ime); flag(io, &c->ei_pending); flag(io, &c->halted);
    flag(io, &c->halt_bug); flag(io, &c->stopped); flag(io, &c->locked);
    section_end(io, s);

    struct gb_mem *m = &g->mem;
    s = section_begin(io, TAG('M', 'E', 'M', ' '));
    bytes(io, m->vram, sizeof m->vram);
    bytes(io, m->wram, sizeof m->wram);
    bytes(io, m->oam, sizeof m->oam);
    bytes(io, m->hram, sizeof m->hram);
    u8(io, &m->ie, 0xFF); u8m(io, &m->if_, 0x1F);
    section_end(io, s);

    struct gb_timer *t = &g->timer;
    s = section_begin(io, TAG('T', 'I', 'M', 'R'));
    u16(io, &t->counter, 0xFFFF);
    u8(io, &t->tima, 0xFF); u8(io, &t->tma, 0xFF); u8m(io, &t->tac, 0x07);
    u8(io, &t->reload, TIMA_RELOAD_B);
    section_end(io, s);

    /* PPU: dot múltiplo de 4 y mode3_end en su rango real (172 + SCX%8 + 6×10 como
     * máximo). El modo y next_event del archivo se ignoran: ppu_resync los deriva
     * al aplicar, para que nunca haya un modo incoherente con LY/dot (auditoría M3, H1). */
    struct gb_ppu *p = &g->ppu;
    s = section_begin(io, TAG('P', 'P', 'U', ' '));
    u16(io, &p->dot, 454); u16(io, &p->mode3_end, 80 + 172 + 7 + 6 * PPU_MAX_OBJS);
    u16(io, &p->next_event, 456);
    if (loading(io) && io->ok) {
        unsigned dot = io->in[io->pos - 6] | io->in[io->pos - 5] << 8;
        unsigned mode3_end = io->in[io->pos - 4] | io->in[io->pos - 3] << 8;
        if (dot % 2 != 0 || mode3_end < 80 + 172)   /* pasos de 4 dots, o de 2 en doble velocidad */
            io->ok = false;
    }
    u8(io, &p->ly, 153); u8(io, &p->mode, 3);
    u8(io, &p->lcdc, 0xFF); u8m(io, &p->stat, 0x78);
    u8(io, &p->scy, 0xFF); u8(io, &p->scx, 0xFF); u8(io, &p->lyc, 0xFF); u8(io, &p->dma, 0xFF);
    u8(io, &p->bgp, 0xFF); u8(io, &p->obp0, 0xFF); u8(io, &p->obp1, 0xFF);
    u8(io, &p->wy, 0xFF); u8(io, &p->wx, 0xFF);
    u8(io, &p->window_line, 0xFF); flag(io, &p->wy_triggered);
    u8(io, &p->obj_count, PPU_MAX_OBJS); u8(io, &p->obj_height, 16);
    for (int i = 0; i < PPU_MAX_OBJS; i++)
        u8(io, &p->objs[i], 39);
    flag(io, &p->stat_line); flag(io, &p->frame_done);
    section_end(io, s);

    /* DMA: index vale 160 al terminar, pero con active=1 debe ser < 160 (OAM).
     * Se leen en copias locales para poder comprobar la relación entre campos. */
    struct gb_dma d = g->dma;
    struct io local = *io;
    if (local.mode == IO_CHECK)
        local.mode = IO_APPLY;   /* escribe en las copias, no en la instancia */
    s = section_begin(&local, TAG('D', 'M', 'A', ' '));
    flag(&local, &d.active); flag(&local, &d.bus_busy);
    u8(&local, &d.start_delay, 1); u8(&local, &d.index, 160);
    u16(&local, &d.src, 0xFFFF); u16(&local, &d.next_src, 0xFFFF);
    section_end(&local, s);
    local.mode = io->mode;
    *io = local;
    if (loading(io) && d.active && d.index >= 160)
        io->ok = false;
    if (io->mode == IO_APPLY)
        g->dma = d;

    /* APU: el anillo de salida y el filtro no se guardan (se vacían al cargar). */
    struct gb_apu *ap = &g->apu;
    s = section_begin(io, TAG('A', 'P', 'U', ' '));
    flag(io, &ap->power);
    u32(io, &ap->pending, 1u << 20);
    bytes(io, ap->regs, sizeof ap->regs);
    u8(io, &ap->fs_step, 7);
    for (int i = 0; i < 4; i++) {
        struct gb_apu_ch *c = &ap->ch[i];
        flag(io, &c->enabled); flag(io, &c->dac); flag(io, &c->len_en);
        u16(io, &c->length, i == 2 ? 256 : 64);
        u16(io, &c->freq, 2047);
        uint32_t timer = (uint32_t)c->timer;
        u32(io, &timer, 1u << 23);         /* ≤ el mayor periodo posible */
        /* Un canal activo siempre tiene el temporizador > 0 (auditoría M5, H1):
         * con 0, apu_sync lo dejaría bajar sin cota hasta desbordar. */
        if (loading(io) && io->ok) {
            bool en = io->in[io->pos - 4 - 2 - 2 - 1 - 1 - 1] != 0;   /* flag enabled */
            uint32_t tr = (uint32_t)io->in[io->pos - 4] | (uint32_t)io->in[io->pos - 3] << 8 |
                          (uint32_t)io->in[io->pos - 2] << 16 | (uint32_t)io->in[io->pos - 1] << 24;
            if (en && tr == 0)
                io->ok = false;
        }
        if (io->mode == IO_APPLY)
            c->timer = (int32_t)timer;
        u8(io, &c->duty, 3);
        u8(io, &c->pos, i == 2 ? 31 : 7);
        u8(io, &c->env.vol, 15); u8(io, &c->env.init, 15);
        u8(io, &c->env.period, 7); u8(io, &c->env.timer, 8);
        flag(io, &c->env.up);
    }
    u8(io, &ap->sweep_timer, 8); u16(io, &ap->sweep_shadow, 2047);
    flag(io, &ap->sweep_enabled); flag(io, &ap->sweep_neg_used);
    u8(io, &ap->wave_sample, 15); u16(io, &ap->lfsr, 0x7FFF);
    section_end(io, s);

    struct gb_serial *se = &g->serial;
    s = section_begin(io, TAG('S', 'E', 'R', ' '));
    u8(io, &se->sb, 0xFF); u8m(io, &se->sc, cgb_native(g) ? 0x83 : 0x81); u8(io, &se->bits, 7); u8(io, &se->out, 0xFF);
    section_end(io, s);

    struct gb_joypad *j = &g->joy;
    s = section_begin(io, TAG('J', 'O', 'Y', ' '));
    u8m(io, &j->select, 0x30); u8(io, &j->buttons, 0xFF);
    section_end(io, s);

    struct gb_cart *ca = &g->cart;
    s = section_begin(io, TAG('C', 'A', 'R', 'T'));
    flag(io, &ca->ram_enabled);
    u8(io, &ca->bank_lo, 0xFF); u8(io, &ca->bank_hi, 0x03); u8(io, &ca->ram_sel, 0x0F);
    u8(io, &ca->mode, 1); flag(io, &ca->rumble_on);
    flag(io, &ca->ram_written); flag(io, &ca->sram_dirty);
    if (ca->ram_size)
        bytes(io, ca->ram, ca->ram_size);
    if (ca->has_rtc) {
        struct gb_rtc *r = &ca->rtc;
        static const uint8_t mask[RTC_REGS] = { 0x3F, 0x3F, 0x1F, 0xFF, 0xC1 };
        for (int i = 0; i < RTC_REGS; i++)
            u8m(io, &r->reg[i], mask[i]);
        for (int i = 0; i < RTC_REGS; i++)
            u8m(io, &r->latched[i], mask[i]);
        u32(io, &r->sub, RTC_CYCLES_PER_SECOND - 1);
        u32(io, &r->wall_sub, RTC_CYCLES_PER_SECOND - 1);
        u8(io, &r->latch_last, 0xFF);
        i64(io, &r->unix);
        if (loading(io) && io->ok) {
            int64_t unix_read = 0;
            for (int k = 0; k < 8; k++)
                unix_read |= (int64_t)((uint64_t)io->in[io->pos - 8 + k] << (8 * k));
            if (!rtc_unix_valid(unix_read))
                io->ok = false;   /* H2: hora fuera de rango (evita desbordes al restar) */
        }
    }
    section_end(io, s);

    /* CGB: se lee en copias locales para validar la relación entre campos (fuera
     * de CGB nativo, bancos a 0 y sin doble velocidad ni HDMA). */
    struct gb_cgb cg = g->cgb;
    uint8_t vbk = g->mem.vbk, svbk = g->mem.svbk;
    local = *io;
    if (local.mode == IO_CHECK)
        local.mode = IO_APPLY;
    s = section_begin(&local, TAG('C', 'G', 'B', ' '));
    u8(&local, &vbk, 1); u8(&local, &svbk, 7);
    flag(&local, &cg.double_speed); flag(&local, &cg.speed_prepare);
    u8m(&local, &cg.bcps, 0xBF); u8m(&local, &cg.ocps, 0xBF);
    bytes(&local, cg.bg_pal, sizeof cg.bg_pal);
    bytes(&local, cg.obj_pal, sizeof cg.obj_pal);
    u8(&local, &cg.opri, 1);
    u16(&local, &cg.hdma_src, 0xFFF0); u16(&local, &cg.hdma_dst, 0x1FF0);
    u8(&local, &cg.hdma_len, 0x7F);
    flag(&local, &cg.hdma_active); flag(&local, &cg.hdma_req);
    /* Máximo alcanzable: un bloque de HBlank en el mismo M-ciclo que arranca un
     * HDMA general de 128 bloques, en doble velocidad (auditoría M8, H1). */
    u16(&local, &cg.stall, 16 + 128 * 16);
    u8m(&local, &cg.rp, 0xC1);
    u8(&local, &cg.ff72, 0xFF); u8(&local, &cg.ff73, 0xFF); u8(&local, &cg.ff74, 0xFF);
    u8m(&local, &cg.ff75, 0x70);
    section_end(&local, s);
    local.mode = io->mode;
    *io = local;
    if (loading(io) && ((cg.hdma_src & 0x0F) || (cg.hdma_dst & 0x0F)))
        io->ok = false;
    if (loading(io) && !cgb_native(g) &&
        (vbk || svbk || cg.double_speed || cg.speed_prepare || cg.hdma_active || cg.hdma_req || cg.stall))
        io->ok = false;
    if (io->mode == IO_APPLY) {
        cg.on = g->cgb.on;           /* el modelo lo fija gb_load_rom, no el archivo */
        cg.compat = g->cgb.compat;
        g->cgb = cg;
        g->mem.vbk = vbk;
        g->mem.svbk = svbk;
        g->mem.wram_bank = svbk ? svbk : 1;
        cgb_update_rgba(g);
        if (g->cgb.compat)
            cgb_load_compat_palettes(g);   /* manda la selección actual, no la del archivo (H5) */
    }

    s = section_begin(io, TAG('M', 'I', 'S', 'C'));
    u64(io, &g->cycles);
    for (size_t i = 0; i < GB_SCREEN_W * GB_SCREEN_H; i++)
        u32(io, &g->framebuffer[i], UINT32_MAX);
    section_end(io, s);
}

/* ---- API pública ---- */

static uint32_t state_model(const gb *g)
{
    if (!g->cgb.on)
        return STATE_MODEL_DMG;
    return g->cgb.compat ? STATE_MODEL_COMPAT : STATE_MODEL_CGB;
}

size_t gb_state_size(const gb *g)
{
    if (!g || !g->rom_loaded)
        return 0;
    struct io io = { IO_COUNT, NULL, NULL, 0, 0, true };
    visit(&io, (gb *)g);   /* IO_COUNT no modifica nada */
    return HEADER_BYTES + io.pos + CRC_BYTES;
}

static void put32(uint8_t *p, uint32_t v)
{
    for (int i = 0; i < 4; i++)
        p[i] = (uint8_t)(v >> (8 * i));
}

static uint32_t get32(const uint8_t *p)
{
    return (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24;
}

gb_result gb_state_save(const gb *g, uint8_t *out, size_t cap)
{
    if (!g || !out)
        return GB_ERR_NULL_ARG;
    if (!g->rom_loaded)
        return GB_ERR_NO_ROM;
    size_t total = gb_state_size(g);
    if (cap < total)
        return GB_ERR_BUFFER_TOO_SMALL;
    memcpy(out, "PGBS", 4);
    put32(out + 4, STATE_VERSION);
    memcpy(out + 8, g->info.fingerprint, 32);
    put32(out + 40, state_model(g));
    struct io io = { IO_SAVE, out + HEADER_BYTES, NULL, total - HEADER_BYTES - CRC_BYTES, 0, true };
    visit(&io, (gb *)g);   /* IO_SAVE solo lee la instancia */
    if (!io.ok || io.pos != io.len)
        return GB_ERR_BUFFER_TOO_SMALL;   /* no debería ocurrir: tamaño medido arriba */
    /* Lo guardado tiene que poder cargarse: se valida con la misma pasada que
     * gb_state_load (auditoría M8, H1). Si no, es un error del núcleo, y es
     * mejor fallar ahora que dejar al jugador un estado ilegible. */
    struct io check = { IO_CHECK, NULL, out + HEADER_BYTES, total - HEADER_BYTES - CRC_BYTES, 0, true };
    visit(&check, (gb *)g);               /* IO_CHECK no modifica la instancia */
    if ((!check.ok || check.pos != check.len)
#ifdef GB_TEST_HOOKS
        && !g->dbg.unchecked_save
#endif
    )
        return GB_ERR_STATE_CORRUPT;
    put32(out + total - CRC_BYTES, crc32_update(0, out, total - CRC_BYTES));
    return GB_OK;
}

gb_result gb_state_load(gb *g, const uint8_t *data, size_t len)
{
    if (!g || !data)
        return GB_ERR_NULL_ARG;
    if (!g->rom_loaded)
        return GB_ERR_NO_ROM;
    if (len < 4 || memcmp(data, "PGBS", 4) != 0)
        return GB_ERR_STATE_MAGIC;
    if (len < HEADER_BYTES + CRC_BYTES)
        return GB_ERR_STATE_CORRUPT;
    uint32_t version = get32(data + 4);
    if (version != STATE_VERSION)
        return GB_ERR_STATE_VERSION;
    if (crc32_update(0, data, len - CRC_BYTES) != get32(data + len - CRC_BYTES))
        return GB_ERR_STATE_CORRUPT;
    if (memcmp(data + 8, g->info.fingerprint, 32) != 0)
        return GB_ERR_STATE_ROM_MISMATCH;
    uint32_t model = get32(data + 40);
    if (model < STATE_MODEL_DMG || model > STATE_MODEL_COMPAT)
        return GB_ERR_STATE_CORRUPT;
    if (model != state_model(g))
        return GB_ERR_STATE_ROM_MISMATCH;   /* mismo ROM, pero cargado en otro modelo */

    size_t body = len - HEADER_BYTES - CRC_BYTES;
    struct io io = { IO_CHECK, NULL, data + HEADER_BYTES, body, 0, true };
    visit(&io, g);
    if (!io.ok || io.pos != body)
        return GB_ERR_STATE_CORRUPT;
    /* Todo validado: ahora sí se escribe en la instancia. */
    io = (struct io){ IO_APPLY, NULL, data + HEADER_BYTES, body, 0, true };
    visit(&io, g);
    cart_update_banks(g);
    ppu_resync(g);
    g->apu.head = g->apu.count = 0;   /* audio del estado anterior: descartado */
    g->apu.mix_dirty = true;
    g->apu.phase = 0;                  /* salida PCM determinista tras cargar */
    g->apu.cap_l = g->apu.cap_r = 0.0;
    g->apu.acc_l = g->apu.acc_r = 0;
    g->apu.acc_n = 0;
    memset(&g->dbg, 0, sizeof g->dbg);
    return GB_OK;
}
