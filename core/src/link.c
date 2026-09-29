/*
 * link.c — cable link virtual entre dos instancias en el mismo hilo (M9).
 * API: include/pocketgb.h (gb_link_*). Diseño: docs/hitos/M9-link-virtual.md
 * y docs/03-core-spec.md §Serial.
 *
 * Tiempo del cable: T (T-ciclos de tiempo real desde que se conectó). Cada
 * instancia tiene su propio contador (gb_cycle_count); `offset` lo traduce al
 * tiempo del cable: local_i = cycles_i + offset_i.
 *
 * 1) Deadline absoluto: T sube de GB_LINK_BLOCK_CYCLES en GB_LINK_BLOCK_CYCLES y
 *    cada instancia con local_i < T ejecuta gb_run_cycles(g_i, T - local_i). El
 *    error de cada bloque (la última instrucción se pasa del deadline) no se
 *    acumula, porque el siguiente deadline se mide desde T y no desde local_i.
 * 2) Causalidad: el serial_bit_cb del maestro adelanta al par hasta el instante
 *    del pulso ANTES de darle el bit (gb_serial_clock_external). En cada bloque
 *    corre primero el lado con reloj interno seleccionado (SC bit 0), para que
 *    el par esté siempre detrás del maestro cuando llega un pulso.
 * 3) Reentrada: `busy` marca que se está adelantando al par dentro de un pulso;
 *    un pulso del par en ese intervalo (los dos con reloj interno) recibe 1 sin
 *    tocar al maestro.
 * Sin estado global ni estático mutable, y sin malloc fuera de gb_link_create.
 */
#include <stdlib.h>
#include <string.h>

#include "internal.h"

enum { FB_PIXELS = GB_SCREEN_W * GB_SCREEN_H };

/* Límite de un adelanto dentro de un pulso: en un avance normal el par nunca
 * está más de un bloque y una instrucción por detrás. Si lo está, los relojes
 * se desalinearon (p. ej. el llamador avanzó una instancia por su cuenta) y no
 * se ejecuta una ráfaga larga dentro del callback. */
#define LINK_MAX_CATCHUP (4 * GB_LINK_BLOCK_CYCLES)
/* Ventana en la que se considera que el reloj de una instancia sigue alineado
 * con T; fuera de ella (gb_load_rom, gb_state_load) se vuelve a alinear. */
#define LINK_RESYNC_WINDOW (2 * GB_LINK_BLOCK_CYCLES)

struct gb_link_port {
    gb_link *link;
    unsigned side;
    gb *g;
    int64_t offset;                 /* local = cycles + offset */
    /* Callbacks del llamador, restaurados al desconectar */
    gb_serial_bit_cb saved_bit_cb;
    gb_serial_byte_cb saved_byte_cb;
    void *saved_user;
};

struct gb_link {
    struct gb_link_port port[2];
    int64_t t;                      /* deadline del último bloque */
    bool running;                   /* dentro de gb_link_run_cycles */
    bool busy;                      /* adelantando al par dentro de un pulso */
    uint32_t fb[2][FB_PIXELS];      /* último frame completo de cada lado */
};

static int64_t local_time(const struct gb_link_port *p)
{
    return (int64_t)p->g->cycles + p->offset;
}

static bool port_live(const struct gb_link_port *p)
{
    return p->g && p->g->rom_loaded;
}

static uint8_t link_bit_cb(void *user, uint8_t bit_out)
{
    struct gb_link_port *m = user;
    gb_link *l = m->link;
    struct gb_link_port *peer = &l->port[m->side ^ 1u];
    if (l->busy || !port_live(peer))
        return 1;                   /* reentrada o cable suelto: entra 1 */
    l->busy = true;
    if (l->running) {
        /* Adelantar al par hasta el pulso (gb_run_cycles termina en frontera
         * de instrucción, así que puede quedar unos ciclos por delante). */
        int64_t target = local_time(m);
        int64_t behind = target - local_time(peer);
        if (behind > 0 && behind <= LINK_MAX_CATCHUP) {
            while (local_time(peer) < target)
                if (gb_run_cycles(peer->g, (uint32_t)(target - local_time(peer))) == 0)
                    break;
        }
    }
    uint8_t in = gb_serial_clock_external(peer->g, bit_out);
    l->busy = false;
    return in;
}

static void link_byte_cb(void *user, uint8_t byte_out)
{
    const struct gb_link_port *p = user;
    if (p->saved_byte_cb)
        p->saved_byte_cb(p->saved_user, byte_out);
}

static bool port_installed(const struct gb_link_port *p)
{
    return p->g->opts.serial_bit_cb == link_bit_cb && p->g->opts.serial_user == p;
}

/* Guarda los callbacks del llamador y pone los del cable. */
static void port_install(struct gb_link_port *p)
{
    gb_options *o = &p->g->opts;
    p->saved_bit_cb = o->serial_bit_cb;
    p->saved_byte_cb = o->serial_byte_cb;
    p->saved_user = o->serial_user;
    o->serial_bit_cb = link_bit_cb;
    o->serial_byte_cb = link_byte_cb;
    o->serial_user = p;
}

static void port_copy_fb(gb_link *l, unsigned side)
{
    memcpy(l->fb[side], l->port[side].g->framebuffer, sizeof l->fb[side]);
}

/* Al empezar un avance: callbacks puestos (gb_load_rom los sustituye por los de
 * sus opciones) y reloj dentro de la ventana de T (gb_load_rom lo pone a 0 y
 * gb_state_load al del estado). */
static void port_ensure(gb_link *l, struct gb_link_port *p)
{
    if (!port_live(p))
        return;
    bool fresh = !port_installed(p);
    if (fresh)
        port_install(p);
    int64_t lt = local_time(p);
    if (fresh || lt < l->t || lt > l->t + LINK_RESYNC_WINDOW) {
        p->offset = l->t - (int64_t)p->g->cycles;
        port_copy_fb(l, p->side);
    }
}

gb_link *gb_link_create(void)
{
    gb_link *l = calloc(1, sizeof *l);
    if (!l)
        return NULL;
    for (unsigned s = 0; s < 2; s++) {
        l->port[s].link = l;
        l->port[s].side = s;
        for (size_t i = 0; i < FB_PIXELS; i++)
            l->fb[s][i] = 0xFFFFFFFFu;
    }
    return l;
}

void gb_link_destroy(gb_link *l)
{
    if (!l)
        return;
    gb_link_detach(l);
    free(l);
}

void gb_link_detach(gb_link *l)
{
    if (!l || l->running)
        return;
    for (unsigned s = 0; s < 2; s++) {
        struct gb_link_port *p = &l->port[s];
        if (p->g && port_installed(p)) {
            p->g->opts.serial_bit_cb = p->saved_bit_cb;
            p->g->opts.serial_byte_cb = p->saved_byte_cb;
            p->g->opts.serial_user = p->saved_user;
        }
        p->g = NULL;
        p->saved_bit_cb = NULL;
        p->saved_byte_cb = NULL;
        p->saved_user = NULL;
    }
}

void gb_link_attach(gb_link *l, gb *a, gb *b)
{
    if (!l || l->running)
        return;
    gb_link_detach(l);
    l->port[0].g = a;
    l->port[1].g = (b != a) ? b : NULL;   /* una instancia no se conecta consigo misma */
    l->t = 0;
    l->busy = false;
    for (unsigned s = 0; s < 2; s++) {
        struct gb_link_port *p = &l->port[s];
        p->offset = 0;
        if (!p->g)
            continue;
        port_install(p);
        p->offset = -(int64_t)p->g->cycles;
        port_copy_fb(l, s);
    }
}

/* Avanza un lado hasta el deadline. */
static void port_run_to(gb_link *l, struct gb_link_port *p)
{
    if (!port_live(p))
        return;
    int64_t lt = local_time(p);
    if (lt < l->t)
        (void)gb_run_cycles(p->g, (uint32_t)(l->t - lt));
}

static bool internal_clock(const struct gb_link_port *p)
{
    return port_live(p) && (p->g->serial.sc & 1);
}

uint32_t gb_link_run_cycles(gb_link *l, uint32_t cycles)
{
    if (!l || l->running)
        return 0;
    l->running = true;
    for (unsigned s = 0; s < 2; s++)
        port_ensure(l, &l->port[s]);
    int64_t end = l->t + cycles;
    while (l->t < end) {
        int64_t step = end - l->t;
        l->t += step < GB_LINK_BLOCK_CYCLES ? step : GB_LINK_BLOCK_CYCLES;
        /* Primero el maestro, para que sus pulsos encuentren al par detrás. */
        unsigned first = (internal_clock(&l->port[1]) && !internal_clock(&l->port[0])) ? 1u : 0u;
        port_run_to(l, &l->port[first]);
        port_run_to(l, &l->port[first ^ 1u]);
        for (unsigned s = 0; s < 2; s++) {
            gb *g = l->port[s].g;
            if (port_live(&l->port[s]) && g->ppu.frame_done) {
                /* Entró en VBlank en este bloque: el frame está completo y la
                 * PPU no lo toca hasta la línea 0 (4560 T-ciclos después). */
                g->ppu.frame_done = false;
                port_copy_fb(l, s);
            }
        }
    }
    for (unsigned s = 0; s < 2; s++)
        if (port_live(&l->port[s]) && !(l->port[s].g->ppu.lcdc & 0x80))
            port_copy_fb(l, s);     /* LCD apagado: la pantalla actual */
    l->running = false;
    return cycles;
}

void gb_link_run_frame(gb_link *l)
{
    (void)gb_link_run_cycles(l, GB_CYCLES_PER_FRAME);
}

const uint32_t *gb_link_framebuffer(const gb_link *l, unsigned side)
{
    if (!l || side > 1)
        return NULL;
    return l->fb[side];
}
