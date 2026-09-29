/* unit_link.c — cable link virtual (M9): deriva del lockstep, causalidad en
 * cada pulso, cable suelto, reentrada, intercambio de bytes entre dos ROMs
 * sintéticos (DMG, CGB con reloj rápido y doble velocidad, compatibilidad),
 * framebuffers sin cortes y robustez (recarga, save states, desconexión).
 * Los ROMs se generan en memoria con ut_make_rom; nunca se escriben a disco. */
#include <stdlib.h>
#include <string.h>

#include "unit.h"

/* ---- Mini ensamblador: el programa empieza en 0x0150 ---- */
struct prog {
    uint8_t b[1024];
    size_t n;
};

static void emit_bytes(struct prog *p, const uint8_t *b, size_t n)
{
    if (p->n + n <= sizeof p->b) {
        memcpy(p->b + p->n, b, n);
        p->n += n;
    }
}
#define EMIT(p, ...) \
    emit_bytes((p), (const uint8_t[]){ __VA_ARGS__ }, sizeof((const uint8_t[]){ __VA_ARGS__ }))

static void jr_to(struct prog *p, uint8_t opcode, size_t label)
{
    int off = (int)label - (int)(p->n + 2);
    EMIT(p, opcode, (uint8_t)(int8_t)off);
}
static void jr_nz(struct prog *p, size_t label) { jr_to(p, 0x20, label); }
static void jr(struct prog *p, size_t label) { jr_to(p, 0x18, label); }
static void jr_z(struct prog *p, size_t label) { jr_to(p, 0x28, label); }
static void jp(struct prog *p, size_t label)
{
    uint16_t a = (uint16_t)(0x150 + label);
    EMIT(p, 0xC3, (uint8_t)a, (uint8_t)(a >> 8));
}

enum kind { K_DMG, K_CGB, K_COMPAT };   /* DMG · CGB nativo · ROM DMG en CGB */

static gb *load(const struct prog *p, enum kind k, gb_options *opts)
{
    uint8_t *rom = ut_make_rom(0x8000, 0x00, 0x00, 0x00, p->b, p->n);
    if (!rom)
        return NULL;
    rom[0x143] = k == K_CGB ? 0x80 : 0x00;
    ut_fix_header_checksum(rom);
    gb_options o;
    if (opts)
        o = *opts;
    else
        gb_options_default(&o);
    o.model = k == K_DMG ? GB_MODEL_DMG : GB_MODEL_CGB;
    gb *g = gb_create();
    if (!g || gb_load_rom(g, rom, 0x8000, &o) != GB_OK) {
        gb_destroy(g);
        g = NULL;
    }
    free(rom);
    return g;
}

static const struct prog idle_prog = { { 0x18, 0xFE }, 2 };   /* JR -2 */

/* ---- (a) Deriva: NOP (4 T-ciclos) frente a CALL (24 T-ciclos) ---- */
static void build_nops(struct prog *p)
{
    EMIT(p, 0xAF, 0xE0, 0x40);                      /* LCD apagado (más rápido) */
    size_t loop = p->n;
    for (int i = 0; i < 200; i++)
        EMIT(p, 0x00);
    jp(p, loop);
}

static void build_calls(struct prog *p, bool double_speed)
{
    if (double_speed)
        EMIT(p, 0x3E, 0x01, 0xE0, 0x4D, 0x10, 0x00);   /* LD A,1; LDH (4D),A; STOP */
    EMIT(p, 0xAF, 0xE0, 0x40);
    size_t loop = p->n;
    EMIT(p, 0x31, 0xF0, 0xDF);                      /* LD SP,DFF0 */
    for (int i = 0; i < 100; i++) {
        uint16_t next = (uint16_t)(0x150 + p->n + 3);
        EMIT(p, 0xCD, (uint8_t)next, (uint8_t)(next >> 8));   /* CALL siguiente */
    }
    jp(p, loop);
}

static void drift_case(struct ut *t, enum kind kb, bool ds, long rounds)
{
    struct prog pa = { { 0 }, 0 }, pb = { { 0 }, 0 };
    build_nops(&pa);
    build_calls(&pb, ds);
    gb *a = load(&pa, K_DMG, NULL), *b = load(&pb, kb, NULL);
    gb_link *l = gb_link_create();
    CHECK(t, a && b && l);
    if (a && b && l) {
        gb_link_attach(l, a, b);
        uint64_t max_diff = 0, max_over = 0;
        bool behind = false;
        for (long r = 1; r <= rounds; r++) {
            gb_link_run_cycles(l, GB_LINK_BLOCK_CYCLES);
            uint64_t T = (uint64_t)r * GB_LINK_BLOCK_CYCLES;
            uint64_t ta = gb_cycle_count(a), tb = gb_cycle_count(b);
            uint64_t d = ta > tb ? ta - tb : tb - ta;
            if (d > max_diff)
                max_diff = d;
            if (ta < T || tb < T)
                behind = true;
            else if (ta - T > max_over || tb - T > max_over)
                max_over = ta - T > tb - T ? ta - T : tb - T;
        }
        CHECK(t, max_diff <= 44);
        CHECK(t, !behind);
        CHECK(t, max_over < 24);                    /* sin deriva: el error no se acumula */
        CHECK(t, !ds || b->cgb.double_speed);
        printf("  link deriva (%s%s, %ld vueltas): max |t_a - t_b| = %llu, max exceso = %llu\n",
               kb == K_DMG ? "DMG" : "CGB", ds ? " doble velocidad" : "", rounds,
               (unsigned long long)max_diff, (unsigned long long)max_over);
    }
    gb_link_destroy(l);
    gb_destroy(a);
    gb_destroy(b);
}

/* ---- (b) Causalidad en el primer flanco del maestro ---- */
/* El esclavo escribe SB=0xAA y, tras `nops` NOPs, SC=0x80. */
static void build_late_slave(struct prog *p, int nops)
{
    for (int i = 0; i < nops; i++)
        EMIT(p, 0x00);
    EMIT(p, 0x3E, 0x80, 0xE0, 0x02);                /* LD A,80; LDH (02),A */
    size_t self = p->n;
    jr(p, self);
}

/* El maestro (lado `ms`) tiene SB=0x55, SC=0x81 y su primer flanco del reloj
 * serie a 300 T-ciclos; el esclavo parte de SC=`slave_sc` (sin transferencia)
 * y activa SC=0x80 hacia `nops`*4+30. Los dos instantes caen dentro del primer
 * bloque [0, 456). Con slave_sc=0x01 los dos lados tienen el bit 0 a 1: el
 * orden del bloque lo decide la transferencia activa (auditoría M9, H1). */
static void causality_case(struct ut *t, unsigned ms, int nops, bool expect_first_bit,
                           uint8_t slave_sc)
{
    struct prog ps = { { 0 }, 0 };
    build_late_slave(&ps, nops);
    gb *m = load(&idle_prog, K_DMG, NULL), *s = load(&ps, K_DMG, NULL);
    gb_link *l = gb_link_create();
    CHECK(t, m && s && l);
    if (!m || !s || !l)
        goto out;
    gb_link_attach(l, ms == 0 ? m : s, ms == 0 ? s : m);
    mmu_write(m, 0xFF01, 0x55);
    mmu_write(m, 0xFF02, 0x81);
    m->timer.counter = 0x00D4;                      /* flanco de bajada del bit 8 en 75 M-ciclos */
    mmu_write(s, 0xFF01, 0xAA);
    mmu_write(s, 0xFF02, slave_sc);
    mmu_write(m, 0xFF0F, 0x00);
    mmu_write(s, 0xFF0F, 0x00);

    gb_link_run_cycles(l, GB_LINK_BLOCK_CYCLES);    /* un bloque: contiene el flanco */
    CHECK(t, m->serial.bits == 1);
    CHECK(t, s->serial.sc & 0x80);                  /* el esclavo ya activó SC dentro del bloque */
    CHECK(t, s->serial.bits == (expect_first_bit ? 1 : 0));
    gb_link_run_cycles(l, 8 * 512);
    CHECK(t, !(m->serial.sc & 0x80) && (m->mem.if_ & IRQ_SERIAL));
    if (expect_first_bit) {
        /* Byte completo en los dos sentidos. */
        CHECK(t, m->serial.sb == 0xAA && s->serial.sb == 0x55);
        CHECK(t, !(s->serial.sc & 0x80) && (s->mem.if_ & IRQ_SERIAL));
    } else {
        /* SC=0x80 llegó DESPUÉS del flanco: el primer bit no se transfiere
         * (ni antes de tiempo): el maestro recibe 1 y luego 7 bits de 0xAA. */
        CHECK(t, m->serial.sb == 0xD5);
        CHECK(t, s->serial.bits == 7 && (s->serial.sc & 0x80));
    }
out:
    gb_link_destroy(l);
    gb_destroy(m);
    gb_destroy(s);
}

/* ---- (c) Sin esclavo activo el maestro recibe 0xFF ---- */
static void no_slave(struct ut *t)
{
    gb *a = load(&idle_prog, K_DMG, NULL), *b = load(&idle_prog, K_DMG, NULL);
    gb_link *l = gb_link_create();
    CHECK(t, a && b && l);
    if (!a || !b || !l)
        goto out;

    /* 1) El otro extremo conectado pero sin transferencia (SC=0). */
    gb_link_attach(l, a, b);
    mmu_write(a, 0xFF01, 0x55);
    mmu_write(a, 0xFF02, 0x81);
    mmu_write(b, 0xFF01, 0x3C);
    gb_link_run_cycles(l, 9 * 512);
    CHECK(t, a->serial.sb == 0xFF && !(a->serial.sc & 0x80));
    CHECK(t, b->serial.sb == 0x3C && b->serial.bits == 0);

    /* 2) Cable suelto (lado 1 sin instancia). */
    gb_link_attach(l, a, NULL);
    mmu_write(a, 0xFF01, 0x12);
    mmu_write(a, 0xFF02, 0x81);
    gb_link_run_cycles(l, 9 * 512);
    CHECK(t, a->serial.sb == 0xFF && !(a->serial.sc & 0x80));

    /* 3) Los dos con reloj interno (indefinido en el hardware): cada uno recibe
     * 1 en cada pulso y no hay recursión. No lo garantiza solo el flag `busy`
     * (defensa en profundidad): el par va por detrás y gb_serial_clock_external
     * sobre un maestro ya devuelve 1. Ningún test puede aislar `busy` sin
     * romper esas dos condiciones desde dentro del núcleo (auditoría M9, H3). */
    gb_link_attach(l, a, b);
    mmu_write(a, 0xFF01, 0x0F);
    mmu_write(a, 0xFF02, 0x81);
    mmu_write(b, 0xFF01, 0xF0);
    mmu_write(b, 0xFF02, 0x81);
    gb_link_run_cycles(l, 9 * 512);
    CHECK(t, a->serial.sb == 0xFF && b->serial.sb == 0xFF);
    CHECK(t, !(a->serial.sc & 0x80) && !(b->serial.sc & 0x80));

    /* 4) Sin ninguna instancia o sin ROM: no se cuelga. */
    gb_link_attach(l, NULL, NULL);
    CHECK(t, gb_link_run_cycles(l, 1000) == 1000);
    gb *empty = gb_create();
    gb_link_attach(l, empty, a);
    CHECK(t, gb_link_run_cycles(l, 1000) == 1000);
    gb_link_detach(l);
    gb_destroy(empty);
    CHECK(t, gb_link_framebuffer(l, 2) == NULL && gb_link_framebuffer(l, 1) != NULL);
    CHECK(t, gb_link_run_cycles(NULL, 10) == 0 && gb_link_framebuffer(NULL, 0) == NULL);
out:
    gb_link_destroy(l);
    gb_destroy(a);
    gb_destroy(b);
}

/* ---- (d) Intercambio de 16 bytes entre dos ROMs que usan la serie ---- */
/* Cada ROM envía i ^ key (i = 0..15) y guarda lo recibido en C000+i; al
 * terminar escribe 1 en C100. El maestro (SC = 0x81 o 0x83) espera ~1000
 * ciclos de CPU antes de cada byte para que el esclavo (SC = 0x80) recargue. */
enum { XCHG_BYTES = 16 };

static void build_exchange(struct prog *p, bool double_speed, uint8_t sc, uint8_t key)
{
    if (double_speed)
        EMIT(p, 0x3E, 0x01, 0xE0, 0x4D, 0x10, 0x00);   /* LD A,1; LDH (4D),A; STOP */
    EMIT(p, 0x21, 0x00, 0xC0, 0x06, 0x00);          /* LD HL,C000; LD B,0 */
    size_t loop = p->n;
    EMIT(p, 0x0E, (sc & 1) ? 0x40 : 0x01);          /* LD C,espera */
    size_t dly = p->n;
    EMIT(p, 0x0D);                                  /* DEC C */
    jr_nz(p, dly);
    EMIT(p, 0x78, 0xEE, key, 0xE0, 0x01);           /* LD A,B; XOR key; LDH (01),A */
    EMIT(p, 0x3E, sc, 0xE0, 0x02);                  /* LD A,sc; LDH (02),A */
    size_t wait = p->n;
    EMIT(p, 0xF0, 0x02, 0xCB, 0x7F);                /* LDH A,(02); BIT 7,A */
    jr_nz(p, wait);
    EMIT(p, 0xF0, 0x01, 0x22, 0x04, 0x78, 0xFE, XCHG_BYTES);   /* guardar; INC B; CP 16 */
    jr_nz(p, loop);
    EMIT(p, 0x3E, 0x01, 0xEA, 0x00, 0xC1);          /* LD (C100),1 */
    size_t done = p->n;
    jr(p, done);
}

size_t ut_link_exchange_prog(uint8_t *out, size_t cap, bool double_speed, uint8_t sc, uint8_t key)
{
    struct prog p = { { 0 }, 0 };
    build_exchange(&p, double_speed, sc, key);
    if (p.n > cap)
        return 0;
    memcpy(out, p.b, p.n);
    return p.n;
}

struct xchg_side {
    enum kind kind;
    bool double_speed;
    uint8_t sc;
};

static const char *kind_name(enum kind k, bool ds)
{
    if (k == K_DMG)
        return "DMG";
    if (k == K_COMPAT)
        return "compat";
    return ds ? "CGB-2x" : "CGB";
}

static void exchange_case(struct ut *t, struct xchg_side sa, struct xchg_side sb)
{
    static const uint8_t keys[2] = { 0x5A, 0xA5 };
    struct prog pa = { { 0 }, 0 }, pb = { { 0 }, 0 };
    build_exchange(&pa, sa.double_speed, sa.sc, keys[0]);
    build_exchange(&pb, sb.double_speed, sb.sc, keys[1]);
    gb *g[2] = { load(&pa, sa.kind, NULL), load(&pb, sb.kind, NULL) };
    gb_link *l = gb_link_create();
    CHECK(t, g[0] && g[1] && l);
    if (!g[0] || !g[1] || !l)
        goto out;
    gb_link_attach(l, g[0], g[1]);
    int frames = 0;
    while (frames < 30 && !(mmu_read(g[0], 0xC100) == 1 && mmu_read(g[1], 0xC100) == 1)) {
        gb_link_run_frame(l);
        frames++;
    }
    int ok = 0;
    for (int s = 0; s < 2; s++)
        for (unsigned i = 0; i < XCHG_BYTES; i++)
            ok += mmu_read(g[s], (uint16_t)(0xC000 + i)) == (uint8_t)(i ^ keys[s ^ 1]);
    CHECK(t, mmu_read(g[0], 0xC100) == 1 && mmu_read(g[1], 0xC100) == 1);
    CHECK(t, ok == 2 * XCHG_BYTES);
    CHECK(t, g[0]->cgb.double_speed == sa.double_speed && g[1]->cgb.double_speed == sb.double_speed);
    printf("  link %s(%02X) <-> %s(%02X): %d/%d bytes en %d frames\n",
           kind_name(sa.kind, sa.double_speed), sa.sc, kind_name(sb.kind, sb.double_speed), sb.sc,
           ok, 2 * XCHG_BYTES, frames);
out:
    gb_link_destroy(l);
    gb_destroy(g[0]);
    gb_destroy(g[1]);
}

static void exchange(struct ut *t)
{
    const struct xchg_side dmg_m = { K_DMG, false, 0x81 }, dmg_s = { K_DMG, false, 0x80 };
    const struct xchg_side cgb_m = { K_CGB, false, 0x81 }, cgb_s = { K_CGB, false, 0x80 };
    const struct xchg_side cgb_fast = { K_CGB, false, 0x83 };
    const struct xchg_side ds_m = { K_CGB, true, 0x81 }, ds_s = { K_CGB, true, 0x80 };
    const struct xchg_side ds_fast = { K_CGB, true, 0x83 };
    const struct xchg_side compat_m = { K_COMPAT, false, 0x81 }, compat_s = { K_COMPAT, false, 0x80 };
    exchange_case(t, dmg_m, dmg_s);        /* criterio del hito */
    exchange_case(t, dmg_s, dmg_m);        /* sentido contrario: el maestro es el lado 1 */
    exchange_case(t, cgb_m, cgb_s);
    exchange_case(t, cgb_fast, cgb_s);     /* reloj rápido (SC bit 1): 16 T-ciclos por bit */
    exchange_case(t, cgb_s, cgb_fast);
    exchange_case(t, ds_m, dmg_s);         /* doble velocidad frente a velocidad normal */
    exchange_case(t, dmg_m, ds_s);
    exchange_case(t, ds_fast, ds_s);       /* rápido + doble velocidad: 8 T-ciclos por bit */
    exchange_case(t, cgb_s, ds_fast);
    exchange_case(t, compat_m, dmg_s);     /* Rojo en compatibilidad ↔ DMG */
    exchange_case(t, cgb_m, compat_s);     /* Amarillo en CGB ↔ Rojo en compatibilidad */
}

/* ---- Framebuffer sin cortes ---- */
/* Invierte BGP en cada VBlank: cada frame completo es de un solo color. */
static void build_flip(struct prog *p)
{
    size_t loop = p->n;
    EMIT(p, 0xF0, 0x44, 0xFE, 0x90);                /* LDH A,(44); CP 144 */
    jr_nz(p, loop);
    EMIT(p, 0xF0, 0x47, 0x2F, 0xE0, 0x47);          /* BGP = ~BGP */
    size_t wait = p->n;
    EMIT(p, 0xF0, 0x44, 0xFE, 0x90);
    jr_z(p, wait);
    jr(p, loop);
}

static bool uniform(const uint32_t *fb)
{
    for (size_t i = 1; i < GB_SCREEN_W * GB_SCREEN_H; i++)
        if (fb[i] != fb[0])
            return false;
    return true;
}

static void framebuffers(struct ut *t)
{
    struct prog pf = { { 0 }, 0 }, po = { { 0 }, 0 };
    build_flip(&pf);
    EMIT(&po, 0xAF, 0xE0, 0x40);                    /* LCD apagado */
    size_t self = po.n;
    jr(&po, self);
    gb *a = load(&pf, K_DMG, NULL), *b = load(&po, K_DMG, NULL);
    gb_link *l = gb_link_create();
    CHECK(t, a && b && l);
    if (!a || !b || !l)
        goto out;
    gb_link_attach(l, a, b);
    gb_link_run_cycles(l, 70 * GB_LINK_BLOCK_CYCLES);   /* terminar a mitad de pantalla */
    int torn = 0, clean = 0, flips = 0;
    gb_link_run_frame(l);                           /* primer frame completo */
    uint32_t last = gb_link_framebuffer(l, 0)[0];
    for (int f = 0; f < 6; f++) {
        gb_link_run_frame(l);
        const uint32_t *fb = gb_link_framebuffer(l, 0);
        clean += uniform(fb);
        torn += !uniform(gb_framebuffer(a));
        flips += fb[0] != last;
        last = fb[0];
    }
    CHECK(t, clean == 6 && flips == 6);            /* un frame nuevo y entero en cada llamada */
    CHECK(t, torn == 6);                            /* el framebuffer vivo sí está cortado */
    CHECK(t, memcmp(gb_link_framebuffer(l, 1), gb_framebuffer(b),
                    sizeof(uint32_t) * GB_SCREEN_W * GB_SCREEN_H) == 0);   /* LCD apagado */
out:
    gb_link_destroy(l);
    gb_destroy(a);
    gb_destroy(b);
}

/* ---- Robustez: callbacks del llamador, recarga del ROM y save states ---- */
struct byte_log {
    int count;
    uint8_t last;
};

static void on_byte(void *user, uint8_t byte)
{
    struct byte_log *log = user;
    log->count++;
    log->last = byte;
}

static void robustness(struct ut *t)
{
    struct byte_log log = { 0, 0 };
    gb_options o;
    gb_options_default(&o);
    o.serial_byte_cb = on_byte;
    o.serial_user = &log;
    struct prog pm = { { 0 }, 0 }, ps = { { 0 }, 0 };
    build_exchange(&pm, false, 0x81, 0x5A);
    build_exchange(&ps, false, 0x80, 0xA5);
    gb *a = load(&pm, K_DMG, &o), *b = load(&ps, K_DMG, NULL);
    gb_link *l = gb_link_create();
    CHECK(t, a && b && l);
    if (!a || !b || !l)
        goto out;
    gb_run_frame(b);                                /* relojes distintos al conectar (el esclavo espera) */
    gb_link_attach(l, a, b);
    for (int f = 0; f < 3; f++)
        gb_link_run_frame(l);
    /* El serial_byte_cb del llamador se sigue llamando con su propio user. */
    CHECK(t, log.count == XCHG_BYTES && log.last == (uint8_t)((XCHG_BYTES - 1) ^ 0x5A));
    CHECK(t, mmu_read(a, 0xC100) == 1 && mmu_read(b, 0xC100) == 1);

    /* Save state del lado 1, avanzar y volver atrás: su reloj se realinea. */
    size_t n = gb_state_size(b);
    uint8_t *st = malloc(n);
    CHECK(t, st && gb_state_save(b, st, n) == GB_OK);
    uint64_t saved_at = gb_cycle_count(b);
    for (int f = 0; f < 3; f++)
        gb_link_run_frame(l);
    CHECK(t, st && gb_state_load(b, st, n) == GB_OK);
    uint64_t a0 = gb_cycle_count(a);
    gb_link_run_cycles(l, GB_LINK_BLOCK_CYCLES);
    CHECK(t, gb_cycle_count(b) - saved_at >= GB_LINK_BLOCK_CYCLES &&
             gb_cycle_count(b) - saved_at < GB_LINK_BLOCK_CYCLES + 24);
    CHECK(t, gb_cycle_count(a) - a0 <= GB_LINK_BLOCK_CYCLES + 24);
    free(st);

    /* Recargar el ROM del lado 1 (reloj a 0, callbacks de sus opciones): el
     * siguiente avance los reinstala y el intercambio vuelve a funcionar. */
    uint8_t *rom = ut_make_rom(0x8000, 0x00, 0x00, 0x00, ps.b, ps.n);
    gb_options ob;
    gb_options_default(&ob);
    ob.model = GB_MODEL_DMG;
    CHECK(t, rom && gb_load_rom(b, rom, 0x8000, &ob) == GB_OK);
    free(rom);
    CHECK(t, b->opts.serial_bit_cb == NULL);
    gb_reset(a);
    log.count = 0;
    gb_link_run_cycles(l, GB_LINK_BLOCK_CYCLES);
    CHECK(t, b->opts.serial_bit_cb != NULL);
    CHECK(t, gb_cycle_count(b) >= GB_LINK_BLOCK_CYCLES && gb_cycle_count(b) < GB_LINK_BLOCK_CYCLES + 24);
    for (int f = 0; f < 3; f++)
        gb_link_run_frame(l);
    CHECK(t, mmu_read(a, 0xC100) == 1 && mmu_read(b, 0xC100) == 1);
    CHECK(t, mmu_read(a, 0xC00F) == (0x0F ^ 0xA5) && mmu_read(b, 0xC00F) == (0x0F ^ 0x5A));
    CHECK(t, log.count == XCHG_BYTES);

    /* Desconectar devuelve los callbacks del llamador. */
    gb_link_detach(l);
    CHECK(t, a->opts.serial_bit_cb == NULL && a->opts.serial_byte_cb == on_byte &&
             a->opts.serial_user == &log);
    CHECK(t, b->opts.serial_bit_cb == NULL && b->opts.serial_byte_cb == NULL);
    CHECK(t, !gb_link_attach(l, a, a));             /* consigo misma: solo el lado 0 */
    CHECK(t, a->opts.serial_bit_cb != NULL);
    gb_link_run_cycles(l, 100);
    gb_link_detach(l);
    CHECK(t, a->opts.serial_byte_cb == on_byte && a->opts.serial_user == &log);
out:
    gb_link_destroy(l);
    gb_destroy(a);
    gb_destroy(b);
}

/* Una instancia pertenece a un solo cable (auditoría M9, H4). */
static void ownership(struct ut *t)
{
    gb *a = load(&idle_prog, K_DMG, NULL), *b = load(&idle_prog, K_DMG, NULL);
    gb *c = load(&idle_prog, K_DMG, NULL);
    gb_link *l1 = gb_link_create(), *l2 = gb_link_create();
    CHECK(t, a && b && c && l1 && l2);
    if (!a || !b || !c || !l1 || !l2)
        goto out;
    CHECK(t, gb_link_attach(l1, a, b));
    const gb_options before = a->opts;
    CHECK(t, !gb_link_attach(l2, a, c));            /* a ya está en l1: lado 0 vacío */
    CHECK(t, a->opts.serial_bit_cb == before.serial_bit_cb && a->opts.serial_user == before.serial_user);
    CHECK(t, !gb_link_attach(l2, c, b));            /* b ya está en l1: lado 1 vacío */
    CHECK(t, b->opts.serial_user != c->opts.serial_user);
    /* l2 solo tiene a c: su avance no mueve a las instancias de l1. */
    uint64_t ta = gb_cycle_count(a), tb = gb_cycle_count(b);
    gb_link_run_cycles(l2, 1000);
    CHECK(t, gb_cycle_count(a) == ta && gb_cycle_count(b) == tb && gb_cycle_count(c) >= 1000);
    /* l1 sigue funcionando y, al desconectarlo, a y b quedan libres para l2. */
    mmu_write(a, 0xFF01, 0x55);
    mmu_write(a, 0xFF02, 0x81);
    mmu_write(b, 0xFF01, 0xAA);
    mmu_write(b, 0xFF02, 0x80);
    gb_link_run_cycles(l1, 9 * 512);
    CHECK(t, a->serial.sb == 0xAA && b->serial.sb == 0x55);
    CHECK(t, gb_link_attach(l1, a, b));             /* reconectar al mismo cable: vale */
    gb_link_detach(l1);
    CHECK(t, gb_link_attach(l2, a, b));
    gb_link_detach(l2);
    CHECK(t, gb_link_attach(l2, NULL, NULL) && !gb_link_attach(NULL, a, b));
out:
    gb_link_destroy(l1);
    gb_link_destroy(l2);
    gb_destroy(a);
    gb_destroy(b);
    gb_destroy(c);
}

void unit_link(struct ut *t)
{
    drift_case(t, K_DMG, false, 1000000);         /* (a) criterio del hito: 10^6 vueltas */
    drift_case(t, K_CGB, true, 100000);             /* CALL en doble velocidad (12 T-ciclos reales) */
    causality_case(t, 0, 42, true, 0x00);           /* (b) SC=0x80 ~200 T, flanco a 300 T */
    causality_case(t, 1, 42, true, 0x00);           /* mismo caso con el maestro en el lado 1 */
    causality_case(t, 0, 92, false, 0x00);          /* SC=0x80 ~400 T: después del flanco */
    causality_case(t, 1, 92, false, 0x00);          /* ídem, maestro en el lado 1 (H2) */
    causality_case(t, 1, 92, false, 0x01);          /* esclavo con SC=0x01 en el lado 0 (H1) */
    causality_case(t, 0, 92, false, 0x01);
    causality_case(t, 1, 42, true, 0x01);
    ownership(t);
    no_slave(t);                                    /* (c) */
    exchange(t);                                    /* (d) */
    framebuffers(t);
    robustness(t);
}
