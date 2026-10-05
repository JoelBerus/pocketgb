/*
 * runner.c — gbatest: runner headless del núcleo GBA (docs/06-testing.md §GBA).
 *   gbatest --sst FILE.json.bin [--limit N] [--show K]   SingleStepTests ARM7TDMI
 *   gbatest ROM --mode jsmolka [--max-frames N]          jsmolka/gba-tests (r12 = 0)
 *   gbatest ROM --bench FRAMES                           velocidad (frames/s)
 *   gbatest --unit                                       tests unitarios
 * Se compila con GBA_TEST_HOOKS (acceso a internal.h y al bus de prueba).
 */
#include "internal.h"
#include <stdio.h>
#include <stdlib.h>
#include <time.h>

int gba_unit_run(void);

static uint8_t *read_file(const char *path, size_t *len)
{
    FILE *f = fopen(path, "rb");
    if (!f) return NULL;
    if (fseek(f, 0, SEEK_END) != 0) { fclose(f); return NULL; }
    long n = ftell(f);
    if (n < 0) { fclose(f); return NULL; }
    rewind(f);
    uint8_t *buf = malloc((size_t)n + 1);
    if (!buf) { fclose(f); return NULL; }
    if (fread(buf, 1, (size_t)n, f) != (size_t)n) { free(buf); fclose(f); return NULL; }
    fclose(f);
    *len = (size_t)n;
    return buf;
}

/* ------------------------------------------------------------ SingleStepTests */

typedef struct sst_state {
    uint32_t r[16], fiq[7], svc[2], abt[2], irq[2], und[2], cpsr, spsr[5], pipe[2];
} sst_state;

typedef struct sst_case {
    sst_state init, fin;
    uint32_t ntxn;
    gba_test_txn txn[GBA_TEST_MAX_TXN];
    uint32_t opcode, base_addr;
} sst_case;

static uint32_t rd32(const uint8_t *p) { return p[0] | (p[1] << 8) | (p[2] << 16) | ((uint32_t)p[3] << 24); }

static bool sst_parse_state(const uint8_t *p, size_t avail, sst_state *s, uint32_t *size)
{
    if (avail < 8 + 40 * 4) return false;
    *size = rd32(p);
    if (*size > avail || *size < 8 + 40 * 4) return false;
    const uint8_t *v = p + 8;
    for (int i = 0; i < 16; i++) s->r[i] = rd32(v + 4 * i);
    for (int i = 0; i < 7; i++) s->fiq[i] = rd32(v + 4 * (16 + i));
    for (int i = 0; i < 2; i++) {
        s->svc[i] = rd32(v + 4 * (23 + i));
        s->abt[i] = rd32(v + 4 * (25 + i));
        s->irq[i] = rd32(v + 4 * (27 + i));
        s->und[i] = rd32(v + 4 * (29 + i));
    }
    s->cpsr = rd32(v + 4 * 31);
    for (int i = 0; i < 5; i++) s->spsr[i] = rd32(v + 4 * (32 + i));
    s->pipe[0] = rd32(v + 4 * 37);
    s->pipe[1] = rd32(v + 4 * 38);
    return true;
}

static bool sst_parse(const uint8_t *p, size_t avail, sst_case *t, uint32_t *size)
{
    if (avail < 4) return false;
    *size = rd32(p);
    if (*size > avail) return false;
    size_t off = 4;
    uint32_t sz;
    if (!sst_parse_state(p + off, avail - off, &t->init, &sz)) return false;
    off += sz;
    if (!sst_parse_state(p + off, avail - off, &t->fin, &sz)) return false;
    off += sz;
    if (avail - off < 12) return false;
    sz = rd32(p + off);
    uint32_t n = rd32(p + off + 8);
    if (n > GBA_TEST_MAX_TXN || 12 + (size_t)n * 24 > avail - off) return false;
    t->ntxn = n;
    for (uint32_t i = 0; i < n; i++) {
        const uint8_t *x = p + off + 12 + i * 24;
        t->txn[i].kind = rd32(x);
        t->txn[i].size = rd32(x + 4);
        t->txn[i].addr = rd32(x + 8);
        t->txn[i].data = rd32(x + 12);
    }
    off += sz;
    if (avail - off < 16) return false;
    t->opcode = rd32(p + off + 8);
    t->base_addr = rd32(p + off + 12);
    return true;
}

/* Orden del SPSR en las pruebas: fiq, svc, abt, irq, und. */
static const int spsr_bank[5] = {ARM_BANK_FIQ, ARM_BANK_SVC, ARM_BANK_ABT, ARM_BANK_IRQ, ARM_BANK_UND};

/* En las pruebas, R son los registros del banco de usuario (r8..r14 incluidos)
 * y los del modo activo están en su banco (R_fiq, R_svc...), como en NBA. */
static void sst_load(gba *g, const sst_case *t)
{
    gba_arm *c = &g->cpu;
    const sst_state *s = &t->init;
    memset(c, 0, sizeof *c);
    memcpy(c->r, s->r, sizeof c->r);
    memcpy(c->bank_fiq_r8, s->fiq, sizeof c->bank_fiq_r8);
    memcpy(c->bank_usr_r8, &s->r[8], sizeof c->bank_usr_r8);
    c->bank_r13[ARM_BANK_FIQ] = s->fiq[5]; c->bank_r14[ARM_BANK_FIQ] = s->fiq[6];
    c->bank_r13[ARM_BANK_SVC] = s->svc[0]; c->bank_r14[ARM_BANK_SVC] = s->svc[1];
    c->bank_r13[ARM_BANK_ABT] = s->abt[0]; c->bank_r14[ARM_BANK_ABT] = s->abt[1];
    c->bank_r13[ARM_BANK_IRQ] = s->irq[0]; c->bank_r14[ARM_BANK_IRQ] = s->irq[1];
    c->bank_r13[ARM_BANK_UND] = s->und[0]; c->bank_r14[ARM_BANK_UND] = s->und[1];
    c->bank_r13[ARM_BANK_USR] = s->r[13]; c->bank_r14[ARM_BANK_USR] = s->r[14];
    for (int i = 0; i < 5; i++) c->bank_spsr[spsr_bank[i]] = s->spsr[i];
    c->bank = ARM_BANK_USR;
    c->cpsr = ARM_MODE_USR;
    c->spsr = c->bank_spsr[ARM_BANK_USR];
    gba_arm_set_cpsr(g, s->cpsr);
    c->pipe[0] = s->pipe[0];
    c->pipe[1] = s->pipe[1];
    gba_test_bus *b = &g->test;
    memset(b, 0, sizeof *b);
    b->active = true;
    b->base_addr = t->base_addr;
    b->opcode = t->opcode;
    b->ntxn = t->ntxn;
    memcpy(b->txn, t->txn, sizeof b->txn);
}

/* Estado observable tras el paso, en el formato de la prueba. */
static void sst_snapshot(const gba *g, sst_state *o)
{
    gba_arm tmp = g->cpu;
    /* Vuelca el banco activo a su almacén para leer todos por igual. */
    if (tmp.bank == ARM_BANK_FIQ) memcpy(tmp.bank_fiq_r8, &tmp.r[8], sizeof tmp.bank_fiq_r8);
    else memcpy(tmp.bank_usr_r8, &tmp.r[8], sizeof tmp.bank_usr_r8);
    tmp.bank_r13[tmp.bank] = tmp.r[13];
    tmp.bank_r14[tmp.bank] = tmp.r[14];
    tmp.bank_spsr[tmp.bank] = tmp.spsr;
    memcpy(o->r, tmp.r, sizeof o->r);
    memcpy(&o->r[8], tmp.bank_usr_r8, sizeof tmp.bank_usr_r8);
    o->r[13] = tmp.bank_r13[ARM_BANK_USR];
    o->r[14] = tmp.bank_r14[ARM_BANK_USR];
    memcpy(o->fiq, tmp.bank_fiq_r8, sizeof tmp.bank_fiq_r8);
    o->fiq[5] = tmp.bank_r13[ARM_BANK_FIQ]; o->fiq[6] = tmp.bank_r14[ARM_BANK_FIQ];
    o->svc[0] = tmp.bank_r13[ARM_BANK_SVC]; o->svc[1] = tmp.bank_r14[ARM_BANK_SVC];
    o->abt[0] = tmp.bank_r13[ARM_BANK_ABT]; o->abt[1] = tmp.bank_r14[ARM_BANK_ABT];
    o->irq[0] = tmp.bank_r13[ARM_BANK_IRQ]; o->irq[1] = tmp.bank_r14[ARM_BANK_IRQ];
    o->und[0] = tmp.bank_r13[ARM_BANK_UND]; o->und[1] = tmp.bank_r14[ARM_BANK_UND];
    o->cpsr = tmp.cpsr;
    for (int i = 0; i < 5; i++) o->spsr[i] = tmp.bank_spsr[spsr_bank[i]];
    o->pipe[0] = tmp.pipe[0];
    o->pipe[1] = tmp.pipe[1];
}

static int sst_compare(const gba *g, const sst_case *t, char *why, size_t cap)
{
    sst_state o;
    sst_snapshot(g, &o);
    const sst_state *e = &t->fin;
    for (int i = 0; i < 16; i++)
        if (o.r[i] != e->r[i]) { snprintf(why, cap, "r%d=%08x esperado %08x", i, o.r[i], e->r[i]); return 1; }
    if (o.cpsr != e->cpsr) { snprintf(why, cap, "cpsr=%08x esperado %08x", o.cpsr, e->cpsr); return 1; }
    for (int i = 0; i < 2; i++)
        if (o.pipe[i] != e->pipe[i]) { snprintf(why, cap, "pipe[%d]=%08x esperado %08x", i, o.pipe[i], e->pipe[i]); return 1; }
    for (int i = 0; i < 7; i++)
        if (o.fiq[i] != e->fiq[i]) { snprintf(why, cap, "r%d_fiq=%08x esperado %08x", 8 + i, o.fiq[i], e->fiq[i]); return 1; }
    struct { const uint32_t *got, *exp; const char *name; } b2[4] = {
        {o.svc, e->svc, "svc"}, {o.abt, e->abt, "abt"}, {o.irq, e->irq, "irq"}, {o.und, e->und, "und"}};
    for (int k = 0; k < 4; k++)
        for (int i = 0; i < 2; i++)
            if (b2[k].got[i] != b2[k].exp[i]) {
                snprintf(why, cap, "r%d_%s=%08x esperado %08x", 13 + i, b2[k].name, b2[k].got[i], b2[k].exp[i]);
                return 1;
            }
    for (int i = 0; i < 5; i++)
        if (o.spsr[i] != e->spsr[i]) { snprintf(why, cap, "spsr[%d]=%08x esperado %08x", i, o.spsr[i], e->spsr[i]); return 1; }
    /* Escrituras: mismas (tamaño, dirección, dato), en el mismo orden. */
    uint32_t ew = 0;
    for (uint32_t i = 0; i < t->ntxn; i++) {
        if (t->txn[i].kind != 2) continue;
        if (ew >= g->test.nwrites) { snprintf(why, cap, "falta la escritura %u", ew); return 1; }
        const gba_test_txn *w = &g->test.writes[ew];
        uint32_t ea = t->txn[i].addr & ~(t->txn[i].size - 1u);
        uint32_t mask = t->txn[i].size == 4 ? 0xFFFFFFFFu : t->txn[i].size == 2 ? 0xFFFFu : 0xFFu;
        if (w->size != t->txn[i].size || w->addr != ea || (w->data & mask) != (t->txn[i].data & mask)) {
            snprintf(why, cap, "escritura %u: %u@%08x=%08x esperado %u@%08x=%08x", ew, w->size, w->addr, w->data,
                     t->txn[i].size, ea, t->txn[i].data);
            return 1;
        }
        ew++;
    }
    if (ew != g->test.nwrites) { snprintf(why, cap, "%u escrituras de más", g->test.nwrites - ew); return 1; }
    /* Lecturas de datos: todas deben existir en la prueba y ser las mismas en número. */
    if (g->test.missing_read) { snprintf(why, cap, "lectura de datos que la prueba no tiene"); return 1; }
    uint32_t er = 0;
    for (uint32_t i = 0; i < t->ntxn; i++) er += t->txn[i].kind == 1;
    if (er != g->test.nreads) { snprintf(why, cap, "%u lecturas de datos, esperadas %u", g->test.nreads, er); return 1; }
    return 0;
}

static int run_sst(const char *path, long limit, int show)
{
    size_t len;
    uint8_t *buf = read_file(path, &len);
    if (!buf) { fprintf(stderr, "no se puede leer %s\n", path); return 2; }
    if (len < 8 || rd32(buf) != 0xD33DBAE0u) { fprintf(stderr, "%s: no es un archivo de SingleStepTests\n", path); free(buf); return 2; }
    uint32_t n = rd32(buf + 4);
    gba *g = gba_create();
    sst_case *t = malloc(sizeof *t);
    if (!g || !t) { free(buf); free(t); gba_destroy(g); return 2; }
    size_t off = 8;
    uint32_t pass = 0, fail = 0, run = 0;
    for (uint32_t i = 0; i < n && (limit <= 0 || run < (uint32_t)limit); i++) {
        uint32_t sz;
        if (!sst_parse(buf + off, len - off, t, &sz)) { fprintf(stderr, "%s: caso %u truncado\n", path, i); fail++; break; }
        off += sz;
        run++;
        sst_load(g, t);
        gba_arm_step(g);
        char why[160];
        if (sst_compare(g, t, why, sizeof why) == 0) {
            pass++;
        } else {
            fail++;
            if (show > 0) {
                show--;
                printf("  caso %u op=%08x cpsr=%08x: %s\n", i, t->opcode, t->init.cpsr, why);
            }
        }
    }
    const char *name = strrchr(path, '/');
    printf("%s %s: %u/%u\n", fail ? "FAIL" : "PASS", name ? name + 1 : path, pass, run);
    free(t);
    free(buf);
    gba_destroy(g);
    return fail ? 1 : 0;
}

/* ------------------------------------------------------------ ROMs */

static bool cpu_idle_loop(const gba *g)
{
    const gba_arm *c = &g->cpu;
    if (c->cpsr & ARM_T) return (c->pipe[0] & 0xFFFFu) == 0xE7FEu;
    return c->pipe[0] == 0xEAFFFFFEu;
}

int main(int argc, char **argv)
{
    const char *rom = NULL, *mode = NULL, *sst = NULL, *dump = NULL, *frames = NULL, *keys = NULL, *ref = NULL, *wav = NULL;
    double want_freq = 0;
    int side = 0, silent = -1;             /* 0 izquierda, 1 derecha; silent = lado que no debe sonar */
    long max_frames = 600, limit = 0, bench = 0;
    bool force_rtc = false;
    int show = 5;
    bool unit = false;
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--sst") && i + 1 < argc) sst = argv[++i];
        else if (!strcmp(argv[i], "--limit") && i + 1 < argc) limit = atol(argv[++i]);
        else if (!strcmp(argv[i], "--show") && i + 1 < argc) show = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--mode") && i + 1 < argc) mode = argv[++i];
        else if (!strcmp(argv[i], "--max-frames") && i + 1 < argc) max_frames = atol(argv[++i]);
        else if (!strcmp(argv[i], "--bench") && i + 1 < argc) bench = atol(argv[++i]);
        else if (!strcmp(argv[i], "--unit")) unit = true;
        else if (!strcmp(argv[i], "--dump") && i + 1 < argc) dump = argv[++i];
        else if (!strcmp(argv[i], "--frames") && i + 1 < argc) frames = argv[++i];
        else if (!strcmp(argv[i], "--keys") && i + 1 < argc) keys = argv[++i];
        else if (!strcmp(argv[i], "--ref") && i + 1 < argc) ref = argv[++i];
        else if (!strcmp(argv[i], "--rtc")) force_rtc = true;
        else if (!strcmp(argv[i], "--wav") && i + 1 < argc) wav = argv[++i];
        else if (!strcmp(argv[i], "--freq") && i + 1 < argc) want_freq = atof(argv[++i]);
        else if (!strcmp(argv[i], "--side") && i + 1 < argc) { i++; side = argv[i][0] == 'R'; silent = argv[i][1] == '!' ? !side : -1; }
        else if (argv[i][0] != '-') rom = argv[i];
        else { fprintf(stderr, "opción desconocida: %s\n", argv[i]); return 2; }
    }
    if (unit) return gba_unit_run();
    if (sst) return run_sst(sst, limit, show);
    if (!rom) {
        fprintf(stderr, "uso: gbatest --sst F | ROM --mode jsmolka | ROM --bench N | --unit\n");
        return 2;
    }
    size_t len;
    uint8_t *data = read_file(rom, &len);
    if (!data) { fprintf(stderr, "no se puede leer %s\n", rom); return 2; }
    gba *g = gba_create();
    gba_options o;
    gba_options_default(&o);
    o.unix_time = 1791203696;          /* 2026-10-05 12:34:56 (hora local de prueba) */
    if (force_rtc) o.rtc = GBA_RTC_ON;
    gba_result r = g ? gba_load_rom(g, data, len, &o) : GBA_ERR_OUT_OF_MEMORY;
    free(data);
    if (r != GBA_OK) { fprintf(stderr, "%s: %s\n", rom, gba_result_str(r)); gba_destroy(g); return 2; }
    int rc = 0;
    if (dump && frames) {
        /* Vuelca los frames pedidos (lista creciente "1,30,60") en RGBA crudo.
         * --keys "frame:máscara,..." pulsa botones desde ese frame. */
        FILE *out = fopen(dump, "wb");
        if (!out) { gba_destroy(g); return 2; }
        long frame = 0;
        const char *p = frames;
        while (*p) {
            long want = strtol(p, (char **)&p, 10);
            while (frame < want) {
                if (keys) {
                    const char *k = keys;
                    while (*k) {
                        long at = strtol(k, (char **)&k, 10);
                        long mask = (*k == ':') ? strtol(k + 1, (char **)&k, 0) : 0;
                        if (at == frame) gba_set_buttons(g, (uint16_t)mask);
                        if (*k == ',') k++;
                        else break;
                    }
                }
                gba_run_frame(g);
                frame++;
            }
            fwrite(gba_framebuffer(g), 4, GBA_SCREEN_W * GBA_SCREEN_H, out);
            if (*p == ',') p++;
            else break;
        }
        fclose(out);
    } else if (bench > 0) {
        clock_t t0 = clock();
        for (long f = 0; f < bench; f++) gba_run_frame(g);
        double s = (double)(clock() - t0) / CLOCKS_PER_SEC;
        double fps = s > 0 ? (double)bench / s : 0;
        printf("bench: %ld frames en %.2f s = %.0f fps (%.1fx tiempo real)\n", bench, s, fps, fps / 59.7275);
    } else if (mode && !strcmp(mode, "ref") && ref) {
        /* Frame max_frames idéntico (RGB) a la referencia RGBA cruda. */
        size_t rlen;
        uint8_t *want = read_file(ref, &rlen);
        if (!want || rlen != (size_t)GBA_SCREEN_W * GBA_SCREEN_H * 4) {
            fprintf(stderr, "referencia no válida: %s\n", ref);
            free(want);
            gba_destroy(g);
            return 2;
        }
        for (long f = 0; f < max_frames; f++) gba_run_frame(g);
        const uint32_t *fb = gba_framebuffer(g);
        long diff = 0;
        for (size_t i = 0; i < (size_t)GBA_SCREEN_W * GBA_SCREEN_H; i++) {
            uint32_t w = (uint32_t)want[i * 4] | ((uint32_t)want[i * 4 + 1] << 8) | ((uint32_t)want[i * 4 + 2] << 16);
            if ((fb[i] & 0xFFFFFFu) != w) diff++;
        }
        free(want);
        if (diff) { printf("FAIL %s: %ld píxeles distintos de la referencia\n", rom, diff); rc = 1; }
        else printf("PASS %s (idéntico a la referencia)\n", rom);
    } else if (mode && !strcmp(mode, "audio")) {
        /* Mide la frecuencia (cruces por cero ascendentes) y el pico del canal
         * izquierdo en el último segundo; opcionalmente vuelca un WAV. */
        size_t cap = (size_t)(max_frames + 2) * 1024u;
        int16_t *pcm = malloc(cap * 2 * sizeof *pcm);
        size_t n = 0;
        if (!pcm) { gba_destroy(g); return 2; }
        for (long f = 0; f < max_frames; f++) {
            gba_run_frame(g);
            n += gba_audio_read(g, pcm + 2 * n, cap - n);
        }
        if (wav) {
            FILE *w = fopen(wav, "wb");
            if (w) {
                uint32_t rate = 48000, bytes = (uint32_t)(n * 4);
                uint8_t h[44] = {'R','I','F','F',0,0,0,0,'W','A','V','E','f','m','t',' ',16,0,0,0,1,0,2,0,
                                 0,0,0,0,0,0,0,0,4,0,16,0,'d','a','t','a',0,0,0,0};
                uint32_t riff = 36 + bytes, br = rate * 4;
                memcpy(h + 4, &riff, 4); memcpy(h + 24, &rate, 4); memcpy(h + 28, &br, 4); memcpy(h + 40, &bytes, 4);
                fwrite(h, 1, 44, w);
                fwrite(pcm, 4, n, w);
                fclose(w);
            }
        }
        size_t win = n > 48000 ? 48000 : n, start = n - win;
        long crossings = 0;
        int peak = 0, other = 0;
        size_t first = 0, last = 0;
        for (size_t i = start + 1; i < n; i++) {
            int a = pcm[2 * (i - 1) + side], b = pcm[2 * i + side];
            if (silent >= 0 && abs(pcm[2 * i + silent]) > other) other = abs(pcm[2 * i + silent]);
            if (abs(b) > peak) peak = abs(b);
            if (a < 0 && b >= 0) {
                if (!crossings) first = i;
                last = i;
                crossings++;
            }
        }
        double freq = (crossings > 1) ? (double)(crossings - 1) * 48000.0 / (double)(last - first) : 0;
        bool ok = peak > 1500 && other < 64 && (want_freq <= 0 || (freq > want_freq * 0.98 && freq < want_freq * 1.02));
        printf("%s %s: lado %c, %.1f Hz (esperado %.1f), pico %d, otro lado %d, %zu muestras\n", ok ? "PASS" : "FAIL", rom,
               side ? 'R' : 'L', freq, want_freq, peak, silent >= 0 ? other : -1, n);
        if (!ok) rc = 1;
        free(pcm);
    } else if (mode && !strcmp(mode, "hb")) {
        /* ROM homebrew que escribe su resultado en 0x03007E00 (0x600D = bien). */
        uint32_t res = 0;
        long f;
        for (f = 0; f < max_frames; f++) {
            gba_run_frame(g);
            res = g->iwram[0x7E00] | (g->iwram[0x7E01] << 8) | ((uint32_t)g->iwram[0x7E02] << 16) | ((uint32_t)g->iwram[0x7E03] << 24);
            if (res) break;
        }
        if (res == 0x600D) printf("PASS %s (%ld frames)\n", rom, f + 1);
        else { printf("FAIL %s: resultado %u\n", rom, res); rc = 1; }
    } else if (mode && !strcmp(mode, "jsmolka")) {
        long f;
        for (f = 0; f < max_frames && !cpu_idle_loop(g); f++) gba_run_frame(g);
        if (!cpu_idle_loop(g)) {
            printf("FAIL %s: sin llegar al bucle final en %ld frames (pc=%08x)\n", rom, max_frames, g->cpu.r[15]);
            rc = 1;
        } else if (g->cpu.r[12] != 0) {
            printf("FAIL %s: falla la prueba %u\n", rom, g->cpu.r[12]);
            rc = 1;
        } else {
            printf("PASS %s (%ld frames)\n", rom, f);
        }
    } else {
        fprintf(stderr, "modo desconocido\n");
        rc = 2;
    }
    gba_destroy(g);
    return rc;
}
