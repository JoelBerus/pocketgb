/*
 * hle.c — BIOS del GBA en alto nivel, sin la BIOS de Nintendo (regla dura 1).
 * Comportamiento según GBATEK §BIOS Functions. Código propio.
 *
 * - En la zona de la BIOS se coloca un manejador de IRQ propio en las mismas
 *   direcciones que documenta GBATEK, para que la protección de lectura de la
 *   BIOS ("devuelve la última instrucción leída desde la BIOS") dé los mismos
 *   valores que el hardware: 0xE129F000 tras el arranque, 0xE25EF004 durante
 *   una IRQ, 0xE55EC002 después y 0xE3A02004 tras una SWI.
 * - Las SWI se ejecutan en C en el modo del llamador (sin entrar en SVC).
 *   IntrWait/VBlankIntrWait paran la CPU y reejecutan la SWI al volver de la
 *   IRQ hasta que el manejador del juego marca el bit en 0x03007FF8.
 */
#include "gba_internal.h"

/* sin(2*pi*i/256) * 0x4000, redondeado (tabla calculada, no copiada). */
static const int16_t hle_sin[256] = {
    0, 402, 804, 1205, 1606, 2006, 2404, 2801, 3196, 3590, 3981, 4370, 4756, 5139, 5520, 5897,
    6270, 6639, 7005, 7366, 7723, 8076, 8423, 8765, 9102, 9434, 9760, 10080, 10394, 10702, 11003, 11297,
    11585, 11866, 12140, 12406, 12665, 12916, 13160, 13395, 13623, 13842, 14053, 14256, 14449, 14635, 14811, 14978,
    15137, 15286, 15426, 15557, 15679, 15791, 15893, 15986, 16069, 16143, 16207, 16261, 16305, 16340, 16364, 16379,
    16383, 16379, 16364, 16340, 16305, 16261, 16207, 16143, 16069, 15986, 15893, 15791, 15679, 15557, 15426, 15286,
    15137, 14978, 14811, 14635, 14449, 14256, 14053, 13842, 13623, 13395, 13160, 12916, 12665, 12406, 12140, 11866,
    11585, 11297, 11003, 10702, 10394, 10080, 9760, 9434, 9102, 8765, 8423, 8076, 7723, 7366, 7005, 6639,
    6270, 5897, 5520, 5139, 4756, 4370, 3981, 3590, 3196, 2801, 2404, 2006, 1606, 1205, 804, 402,
    0, -402, -804, -1205, -1606, -2006, -2404, -2801, -3196, -3590, -3981, -4370, -4756, -5139, -5520, -5897,
    -6270, -6639, -7005, -7366, -7723, -8076, -8423, -8765, -9102, -9434, -9760, -10080, -10394, -10702, -11003, -11297,
    -11585, -11866, -12140, -12406, -12665, -12916, -13160, -13395, -13623, -13842, -14053, -14256, -14449, -14635, -14811, -14978,
    -15137, -15286, -15426, -15557, -15679, -15791, -15893, -15986, -16069, -16143, -16207, -16261, -16305, -16340, -16364, -16379,
    -16384, -16379, -16364, -16340, -16305, -16261, -16207, -16143, -16069, -15986, -15893, -15791, -15679, -15557, -15426, -15286,
    -15137, -14978, -14811, -14635, -14449, -14256, -14053, -13842, -13623, -13395, -13160, -12916, -12665, -12406, -12140, -11866,
    -11585, -11297, -11003, -10702, -10394, -10080, -9760, -9434, -9102, -8765, -8423, -8076, -7723, -7366, -7005, -6639,
    -6270, -5897, -5520, -5139, -4756, -4370, -3981, -3590, -3196, -2801, -2404, -2006, -1606, -1205, -804, -402
};

/* 2^(-s/12) y 2^(-f/3072) en Q16 (MidiKey2Freq en entero: determinista en
 * todas las plataformas, sin libm; tablas calculadas, no copiadas). */
static const uint32_t hle_semi[12] = {65536, 61858, 58386, 55109, 52016, 49097, 46341, 43740, 41285, 38968, 36781, 34716};
static const uint32_t hle_fine[256] = {
    65536, 65521, 65506, 65492, 65477, 65462, 65447, 65433, 65418, 65403, 65388, 65374, 65359, 65344, 65329, 65315,
    65300, 65285, 65270, 65256, 65241, 65226, 65211, 65197, 65182, 65167, 65153, 65138, 65123, 65109, 65094, 65079,
    65065, 65050, 65035, 65020, 65006, 64991, 64976, 64962, 64947, 64933, 64918, 64903, 64889, 64874, 64859, 64845,
    64830, 64815, 64801, 64786, 64772, 64757, 64742, 64728, 64713, 64699, 64684, 64669, 64655, 64640, 64626, 64611,
    64596, 64582, 64567, 64553, 64538, 64524, 64509, 64494, 64480, 64465, 64451, 64436, 64422, 64407, 64393, 64378,
    64364, 64349, 64335, 64320, 64306, 64291, 64277, 64262, 64248, 64233, 64219, 64204, 64190, 64175, 64161, 64146,
    64132, 64117, 64103, 64088, 64074, 64059, 64045, 64030, 64016, 64002, 63987, 63973, 63958, 63944, 63929, 63915,
    63901, 63886, 63872, 63857, 63843, 63829, 63814, 63800, 63785, 63771, 63757, 63742, 63728, 63713, 63699, 63685,
    63670, 63656, 63642, 63627, 63613, 63599, 63584, 63570, 63555, 63541, 63527, 63512, 63498, 63484, 63470, 63455,
    63441, 63427, 63412, 63398, 63384, 63369, 63355, 63341, 63326, 63312, 63298, 63284, 63269, 63255, 63241, 63227,
    63212, 63198, 63184, 63169, 63155, 63141, 63127, 63112, 63098, 63084, 63070, 63056, 63041, 63027, 63013, 62999,
    62984, 62970, 62956, 62942, 62928, 62913, 62899, 62885, 62871, 62857, 62843, 62828, 62814, 62800, 62786, 62772,
    62757, 62743, 62729, 62715, 62701, 62687, 62673, 62658, 62644, 62630, 62616, 62602, 62588, 62574, 62560, 62545,
    62531, 62517, 62503, 62489, 62475, 62461, 62447, 62433, 62419, 62404, 62390, 62376, 62362, 62348, 62334, 62320,
    62306, 62292, 62278, 62264, 62250, 62236, 62222, 62208, 62194, 62180, 62166, 62152, 62138, 62124, 62109, 62095,
    62081, 62067, 62053, 62039, 62025, 62011, 61997, 61983, 61970, 61956, 61942, 61928, 61914, 61900, 61886, 61872
};

static void put32(gba *g, uint32_t off, uint32_t v)
{
    g->bios[off] = (uint8_t)v;
    g->bios[off + 1] = (uint8_t)(v >> 8);
    g->bios[off + 2] = (uint8_t)(v >> 16);
    g->bios[off + 3] = (uint8_t)(v >> 24);
}

void gba_hle_install(gba *g)
{
    memset(g->bios, 0, sizeof g->bios);
    put32(g, 0x00, 0xEAFFFFFEu);   /* reset: b . (nunca se usa: se arranca en el cartucho) */
    put32(g, 0x04, 0xE1B0F00Eu);   /* indefinida: movs pc, lr */
    put32(g, 0x08, 0xE1B0F00Eu);   /* SWI (solo si una SWI llegara sin HLE) */
    put32(g, 0x0C, 0xE25EF004u);   /* abort: subs pc, lr, #4 */
    put32(g, 0x10, 0xE25EF008u);
    put32(g, 0x18, 0xEA000042u);   /* IRQ: b 0x128 */
    put32(g, 0x1C, 0xE25EF004u);   /* FIQ */
    put32(g, 0x128, 0xE92D500Fu);  /* stmfd sp!, {r0-r3, r12, lr} */
    put32(g, 0x12C, 0xE3A00301u);  /* mov r0, #0x04000000 */
    put32(g, 0x130, 0xE28FE000u);  /* add lr, pc, #0 */
    put32(g, 0x134, 0xE510F004u);  /* ldr pc, [r0, #-4]  (manejador del juego en 0x03007FFC) */
    put32(g, 0x138, 0xE8BD500Fu);  /* ldmfd sp!, {r0-r3, r12, lr} */
    put32(g, 0x13C, 0xE25EF004u);  /* subs pc, lr, #4 */
    put32(g, 0x144, 0xE55EC002u);  /* lo que la CPU ya ha leído al salir de la IRQ */
    put32(g, 0x0E4, 0xE129F000u);
    put32(g, 0x190, 0xE3A02004u);
    g->bios_last = 0xE129F000u;
}

/* ------------------------------------------------------------ acceso a memoria */

static uint32_t rd32(gba *g, uint32_t a) { return gba_bus_read32(g, a); }
static uint16_t rd16(gba *g, uint32_t a) { return gba_bus_read16(g, a); }
static uint8_t rd8(gba *g, uint32_t a) { return gba_bus_read8(g, a); }
static void wr32(gba *g, uint32_t a, uint32_t v) { gba_bus_write32(g, a, v); }
static void wr16(gba *g, uint32_t a, uint16_t v) { gba_bus_write16(g, a, v); }

/* ------------------------------------------------------------ funciones */

static void hle_register_ram_reset(gba *g, uint32_t flags)
{
    if (flags & 0x01) memset(g->ewram, 0, sizeof g->ewram);
    if (flags & 0x02) memset(g->iwram, 0, sizeof g->iwram - 0x200);
    if (flags & 0x04) memset(g->pal, 0, sizeof g->pal);
    if (flags & 0x08) memset(g->vram, 0, sizeof g->vram);
    if (flags & 0x10) memset(g->oam, 0, sizeof g->oam);
    if (flags & 0x20) memset(&g->io[0x120], 0, 0x40);
    if (flags & 0x40) {
        for (uint32_t off = 0x060; off < 0x0A0; off += 2) if (off != 0x084 && off != 0x082) gba_io_write16(g, off, 0);
        gba_io_write16(g, 0x082, 0x8800);       /* SOUNDCNT_H = 0 y vaciar las dos FIFO */
        gba_io_write16(g, 0x082, 0);
        gba_io_write16(g, 0x084, 0);
    }
    if (flags & 0x80) {
        for (uint32_t off = 0; off < 0x060; off += 2) gba_io_write16(g, off, 0);
        gba_io_write16(g, 0x020, 0x100); gba_io_write16(g, 0x026, 0x100);
        gba_io_write16(g, 0x030, 0x100); gba_io_write16(g, 0x036, 0x100);
        for (uint32_t off = 0x0B0; off < 0x0E0; off += 2) gba_io_write16(g, off, 0);
        for (uint32_t off = 0x100; off < 0x110; off += 2) gba_io_write16(g, off, 0);
        gba_io_write16(g, 0x200, 0);
        gba_io_write16(g, 0x202, 0xFFFF);
        gba_io_write16(g, 0x204, 0);
        gba_io_write16(g, 0x208, 0);
    }
}

static void hle_soft_reset(gba *g)
{
    uint8_t flag = g->iwram[0x7FFA];
    g->hle_waiting = false;
    memset(&g->iwram[0x7E00], 0, 0x200);
    gba_arm *c = &g->cpu;
    gba_arm_set_cpsr(g, ARM_MODE_SVC);
    c->r[13] = 0x03007FE0; c->r[14] = 0; c->spsr = 0;
    gba_arm_set_cpsr(g, ARM_MODE_IRQ);
    c->r[13] = 0x03007FA0; c->r[14] = 0; c->spsr = 0;
    gba_arm_set_cpsr(g, ARM_MODE_SYS);
    c->r[13] = 0x03007F00;
    for (int i = 0; i < 13; i++) c->r[i] = 0;
    c->r[14] = 0;
    gba_arm_branch(g, flag ? 0x02000000u : 0x08000000u);
}

static void hle_div(gba *g, int32_t num, int32_t den)
{
    uint32_t *r = g->cpu.r;
    if (den == 0) {
        /* La BIOS real no termina; se devuelve un valor estable (sin cuelgue). */
        r[0] = num < 0 ? 0xFFFFFFFFu : 1u;
        r[1] = (uint32_t)num;
        r[3] = 1;
        return;
    }
    if (num == INT32_MIN && den == -1) {
        r[0] = (uint32_t)INT32_MIN; r[1] = 0; r[3] = (uint32_t)INT32_MIN;
        return;
    }
    int32_t q = num / den, m = num % den;
    r[0] = (uint32_t)q;
    r[1] = (uint32_t)m;
    r[3] = q < 0 ? 0u - (uint32_t)q : (uint32_t)q;
}

static uint32_t hle_isqrt(uint32_t v)
{
    uint32_t res = 0, bit = 1u << 30;
    while (bit > v) bit >>= 2;
    while (bit) {
        if (v >= res + bit) { v -= res + bit; res = (res >> 1) + bit; }
        else res >>= 1;
        bit >>= 2;
    }
    return res;
}

/* ArcTan (GBATEK): polinomio en coma fija 1.14; resultado en 1/0x10000 de vuelta. */
/* Desplazamiento aritmético a la derecha sin depender de la implementación. */
static inline int64_t hle_asr(int64_t v, unsigned n)
{
    return v >= 0 ? v >> n : -((-v - 1) >> n) - 1;
}

static int32_t hle_atan(int32_t x)
{
    int64_t a = -hle_asr((int64_t)x * x, 14);
    int64_t b = hle_asr(0xA9 * a, 14) + 0x390;
    b = hle_asr(b * a, 14) + 0x91C;
    b = hle_asr(b * a, 14) + 0xFB6;
    b = hle_asr(b * a, 14) + 0x16AA;
    b = hle_asr(b * a, 14) + 0x2081;
    b = hle_asr(b * a, 14) + 0x3651;
    b = hle_asr(b * a, 14) + 0xA2F9;
    return (int32_t)hle_asr((int64_t)x * b, 16);
}

static uint32_t hle_atan2(int32_t x, int32_t y)
{
    if (y == 0) return x >= 0 ? 0 : 0x8000;
    if (x == 0) return y >= 0 ? 0x4000 : 0xC000;
    int32_t ax = x < 0 ? -x : x, ay = y < 0 ? -y : y;
    int32_t r;
    if (ax >= ay) {
        r = hle_atan((int32_t)(((int64_t)y * 16384) / x));
        if (x < 0) r += 0x8000;
    } else {
        r = -hle_atan((int32_t)(((int64_t)x * 16384) / y));
        r += y > 0 ? 0x4000 : 0xC000;
    }
    return (uint32_t)r & 0xFFFFu;
}

static void hle_cpu_set(gba *g, uint32_t src, uint32_t dst, uint32_t ctl, bool fast)
{
    /* La BIOS se niega a copiar desde su propia zona. */
    if ((src & 0x0E000000u) == 0) return;
    uint32_t count = ctl & 0x1FFFFFu;
    bool fill = ctl & (1u << 24);
    bool word = fast || (ctl & (1u << 26));
    if (fast) count = (count + 7u) & ~7u;
    if (word) {
        src &= ~3u; dst &= ~3u;
        uint32_t v = fill ? rd32(g, src) : 0;
        for (uint32_t i = 0; i < count; i++) {
            if (!fill) v = rd32(g, src + i * 4u);
            wr32(g, dst + i * 4u, v);
        }
    } else {
        src &= ~1u; dst &= ~1u;
        uint16_t v = fill ? rd16(g, src) : 0;
        for (uint32_t i = 0; i < count; i++) {
            if (!fill) v = rd16(g, src + i * 2u);
            wr16(g, dst + i * 2u, v);
        }
    }
}

static void hle_bg_affine(gba *g, uint32_t src, uint32_t dst, uint32_t n)
{
    for (uint32_t i = 0; i < n; i++, src += 20, dst += 16) {
        int32_t ox = (int32_t)rd32(g, src), oy = (int32_t)rd32(g, src + 4);
        int16_t cx = (int16_t)rd16(g, src + 8), cy = (int16_t)rd16(g, src + 10);
        int16_t sx = (int16_t)rd16(g, src + 12), sy = (int16_t)rd16(g, src + 14);
        uint16_t th = (uint16_t)(rd16(g, src + 16) >> 8);
        int64_t s = hle_sin[th], c = hle_sin[(th + 64) & 255];
        int64_t pa = hle_asr(sx * c, 14), pb = -hle_asr(sx * s, 14);
        int64_t pc = hle_asr(sy * s, 14), pd = hle_asr(sy * c, 14);
        wr16(g, dst, (uint16_t)pa);
        wr16(g, dst + 2, (uint16_t)pb);
        wr16(g, dst + 4, (uint16_t)pc);
        wr16(g, dst + 6, (uint16_t)pd);
        wr32(g, dst + 8, (uint32_t)(int64_t)(ox - (pa * cx + pb * cy)));
        wr32(g, dst + 12, (uint32_t)(int64_t)(oy - (pc * cx + pd * cy)));
    }
}

static void hle_obj_affine(gba *g, uint32_t src, uint32_t dst, uint32_t n, uint32_t stride)
{
    for (uint32_t i = 0; i < n; i++, src += 8) {
        int16_t sx = (int16_t)rd16(g, src), sy = (int16_t)rd16(g, src + 2);
        uint16_t th = (uint16_t)(rd16(g, src + 4) >> 8);
        int64_t s = hle_sin[th], c = hle_sin[(th + 64) & 255];
        wr16(g, dst, (uint16_t)hle_asr(sx * c, 14)); dst += stride;
        wr16(g, dst, (uint16_t)-hle_asr(sx * s, 14)); dst += stride;
        wr16(g, dst, (uint16_t)hle_asr(sy * s, 14)); dst += stride;
        wr16(g, dst, (uint16_t)hle_asr(sy * c, 14)); dst += stride;
    }
}

/* Salida por bytes: a WRAM directa; a VRAM (solo admite 16 bits) por pares. */
typedef struct { gba *g; uint32_t dst, pos; bool vram; uint8_t pend; } hle_out;

static void out_put(hle_out *o, uint8_t b)
{
    if (!o->vram) {
        gba_bus_write8(o->g, o->dst + o->pos, b);
    } else if (!(o->pos & 1u)) {
        o->pend = b;
    } else {
        gba_bus_write16(o->g, (o->dst + o->pos - 1u) & ~1u, (uint16_t)(o->pend | (b << 8)));
    }
    o->pos++;
}

/* Byte ya producido `back` posiciones atrás (referencias de LZ77). */
static uint8_t out_peek(hle_out *o, uint32_t back)
{
    if (o->vram && (o->pos & 1u) && back == 1) return o->pend;
    return rd8(o->g, o->dst + o->pos - back);
}

static void hle_lz77(gba *g, uint32_t src, uint32_t dst, bool vram)
{
    uint32_t hdr = rd32(g, src);
    uint32_t size = hdr >> 8;
    if (size > 0x40000u) size = 0x40000u;     /* acota por EWRAM; un ROM no confiable no puede pedir más */
    src += 4;
    hle_out o = {g, dst, 0, vram, 0};
    uint32_t done = 0;
    while (done < size) {
        uint8_t flags = rd8(g, src++);
        for (int i = 0; i < 8 && done < size; i++, flags <<= 1) {
            if (flags & 0x80u) {
                uint8_t b0 = rd8(g, src), b1 = rd8(g, src + 1);
                src += 2;
                uint32_t len = (uint32_t)(b0 >> 4) + 3u;
                uint32_t disp = (((uint32_t)(b0 & 15u) << 8) | b1) + 1u;
                for (uint32_t k = 0; k < len && done < size; k++, done++) out_put(&o, out_peek(&o, disp));
            } else {
                out_put(&o, rd8(g, src++));
                done++;
            }
        }
    }
}

static void hle_rl(gba *g, uint32_t src, uint32_t dst, bool vram)
{
    uint32_t size = rd32(g, src) >> 8;
    if (size > 0x40000u) size = 0x40000u;
    src += 4;
    hle_out o = {g, dst, 0, vram, 0};
    uint32_t done = 0;
    while (done < size) {
        uint8_t f = rd8(g, src++);
        if (f & 0x80u) {
            uint32_t len = (uint32_t)(f & 0x7Fu) + 3u;
            uint8_t b = rd8(g, src++);
            for (uint32_t k = 0; k < len && done < size; k++, done++) out_put(&o, b);
        } else {
            uint32_t len = (uint32_t)(f & 0x7Fu) + 1u;
            for (uint32_t k = 0; k < len && done < size; k++, done++) out_put(&o, rd8(g, src++));
        }
    }
}

static void hle_huff(gba *g, uint32_t src, uint32_t dst)
{
    uint32_t hdr = rd32(g, src);
    unsigned bits = hdr & 15u;
    if (bits != 4 && bits != 8) bits = 8;
    uint32_t size = hdr >> 8;
    if (size > 0x40000u) size = 0x40000u;
    uint32_t tree = src + 5;
    uint32_t data = src + 4 + ((uint32_t)rd8(g, src + 4) + 1u) * 2u;
    uint32_t outw = 0, outbits = 0, done = 0;
    uint32_t node = tree;
    uint8_t nv = rd8(g, node);
    /* Cada bit de salida necesita al menos un bit de entrada: con un árbol
     * malformado (ROM no confiable) el bucle termina igual. */
    uint32_t words_left = size * 2u + 16u;
    while (done < size && words_left--) {
        uint32_t word = rd32(g, data);
        data += 4;
        for (int i = 31; i >= 0 && done < size; i--) {
            unsigned bit = (word >> i) & 1u;
            uint32_t next = (node & ~1u) + ((uint32_t)(nv & 0x3Fu) + 1u) * 2u + bit;
            bool leaf = bit ? (nv & 0x40u) : (nv & 0x80u);
            node = next;
            nv = rd8(g, node);
            if (leaf) {
                outw |= (uint32_t)nv << outbits;
                outbits += bits;
                node = tree;
                nv = rd8(g, node);
                if (outbits == 32) {
                    wr32(g, dst, outw);
                    dst += 4;
                    done += 4;
                    outw = 0;
                    outbits = 0;
                }
            }
        }
    }
}

static void hle_diff(gba *g, uint32_t src, uint32_t dst, bool wide, bool vram)
{
    uint32_t size = rd32(g, src) >> 8;
    if (size > 0x40000u) size = 0x40000u;
    src += 4;
    if (wide) {
        uint16_t acc = 0;
        for (uint32_t i = 0; i < size; i += 2) {
            acc = (uint16_t)(acc + rd16(g, src + i));
            wr16(g, dst + i, acc);
        }
    } else {
        hle_out o = {g, dst, 0, vram, 0};
        uint8_t acc = 0;
        for (uint32_t i = 0; i < size; i++) {
            acc = (uint8_t)(acc + rd8(g, src + i));
            out_put(&o, acc);
        }
    }
}

static void hle_bit_unpack(gba *g, uint32_t src, uint32_t dst, uint32_t info)
{
    uint16_t len = rd16(g, info);
    unsigned sw = rd8(g, info + 2), dw = rd8(g, info + 3);
    uint32_t off = rd32(g, info + 4);
    bool zero = off & 0x80000000u;
    off &= 0x7FFFFFFFu;
    if (!(sw == 1 || sw == 2 || sw == 4 || sw == 8) || !(dw == 1 || dw == 2 || dw == 4 || dw == 8 || dw == 16 || dw == 32))
        return;
    uint32_t out = 0, outbits = 0;
    for (uint32_t i = 0; i < len; i++) {
        uint8_t b = rd8(g, src + i);
        for (unsigned s = 0; s < 8; s += sw) {
            uint32_t v = (b >> s) & ((1u << sw) - 1u);
            if (v || zero) v += off;
            out |= (dw == 32 ? v : (v & ((1u << dw) - 1u))) << outbits;
            outbits += dw;
            if (outbits >= 32) {
                wr32(g, dst, out);
                dst += 4;
                out = 0;
                outbits = 0;
            }
        }
    }
}

/* ------------------------------------------------------------ despacho */

static void hle_intr_wait(gba *g, bool discard, uint16_t mask)
{
    gba_arm *c = &g->cpu;
    /* Al reejecutarse tras la IRQ ya no se descarta (auditoría G2 H2). */
    if (g->hle_waiting) discard = false;
    g->ime = 1;
    uint16_t flags = (uint16_t)(g->iwram[0x7FF8] | (g->iwram[0x7FF9] << 8));
    if (discard) {
        flags &= (uint16_t)~mask;
        g->iwram[0x7FF8] = (uint8_t)flags;
        g->iwram[0x7FF9] = (uint8_t)(flags >> 8);
    }
    if (!discard && (flags & mask)) {
        flags &= (uint16_t)~mask;
        g->iwram[0x7FF8] = (uint8_t)flags;
        g->iwram[0x7FF9] = (uint8_t)(flags >> 8);
        g->hle_waiting = false;
        return;
    }
    /* Aún no: parar y volver a ejecutar esta SWI tras la IRQ (ya sin descartar). */
    g->hle_waiting = true;
    c->halted = true;
    uint32_t here = c->r[15] - ((c->cpsr & ARM_T) ? 4u : 8u);
    gba_arm_branch(g, here);
}

bool gba_hle_swi(gba *g, uint32_t number)
{
    uint32_t *r = g->cpu.r;
    gba_bus_idle(g, 8);
    switch (number) {
    case 0x00: hle_soft_reset(g); break;
    case 0x01: hle_register_ram_reset(g, r[0]); break;
    case 0x02: case 0x03: g->cpu.halted = true; break;
    case 0x04: hle_intr_wait(g, r[0] != 0, (uint16_t)r[1]); break;
    case 0x05: r[0] = 1; r[1] = 1; hle_intr_wait(g, true, 1); break;
    case 0x06: hle_div(g, (int32_t)r[0], (int32_t)r[1]); gba_bus_idle(g, 40); break;
    case 0x07: hle_div(g, (int32_t)r[1], (int32_t)r[0]); gba_bus_idle(g, 40); break;
    case 0x08: r[0] = hle_isqrt(r[0]); gba_bus_idle(g, 40); break;
    case 0x09: r[0] = (uint32_t)hle_atan((int16_t)r[0]); break;
    case 0x0A: r[0] = hle_atan2((int32_t)(int16_t)r[0], (int32_t)(int16_t)r[1]); break;
    case 0x0B: hle_cpu_set(g, r[0], r[1], r[2], false); break;
    case 0x0C: hle_cpu_set(g, r[0], r[1], r[2], true); break;
    case 0x0D: r[0] = 0xBAAE187Fu; break;
    case 0x0E: hle_bg_affine(g, r[0], r[1], r[2]); break;
    case 0x0F: hle_obj_affine(g, r[0], r[1], r[2], r[3]); break;
    case 0x10: hle_bit_unpack(g, r[0], r[1], r[2]); break;
    case 0x11: hle_lz77(g, r[0], r[1], false); break;
    case 0x12: hle_lz77(g, r[0], r[1], true); break;
    case 0x13: hle_huff(g, r[0], r[1]); break;
    case 0x14: hle_rl(g, r[0], r[1], false); break;
    case 0x15: hle_rl(g, r[0], r[1], true); break;
    case 0x16: hle_diff(g, r[0], r[1], false, false); break;
    case 0x17: hle_diff(g, r[0], r[1], false, true); break;
    case 0x18: hle_diff(g, r[0], r[1], true, false); break;
    case 0x19: {
        uint16_t bias = gba_io_read16(g, 0x088);
        bias = (uint16_t)((bias & ~0x3FEu) | (r[0] ? 0x200u : 0u));
        gba_io_write16(g, 0x088, bias);
        break;
    }
    case 0x1F: {
        /* MidiKey2Freq: freq = [wave+4] / 2^((180 - key - fine/256) / 12),
         * en unidades de 1/256 de semitono y coma fija; satura a 32 bits. */
        uint64_t base = rd32(g, r[0] + 4);
        int32_t n = 180 * 256 - (int32_t)((r[1] & 0xFFu) * 256u + (r[2] & 0xFFu));
        int32_t oct = n >= 0 ? n / 3072 : -((-n + 3071) / 3072);
        int32_t rem = n - oct * 3072;              /* 0..3071 */
        /* Producto en Q8 (8 bits de fracción) y redondeo al final: el error no
         * se amplifica al subir de octava (auditoría G2, segunda vuelta, N1). */
        uint64_t v = (base * hle_semi[rem / 256]) >> 8;          /* < 2^40 */
        v = (v * hle_fine[rem % 256]) >> 16;                     /* Q8, < 2^40 */
        if (oct >= 0) v = oct >= 48 ? 0 : v >> oct;
        else v = v > (0xFFFFFFFFFFull >> -oct) ? 0xFFFFFFFFFFull : v << -oct;
        v = (v + 0x80u) >> 8;
        r[0] = v > 0xFFFFFFFFull ? 0xFFFFFFFFu : (uint32_t)v;
        break;
    }
    default:
        return false;          /* driver de sonido de la BIOS y otras: no emuladas */
    }
    return true;
}
