/*
 * runner.c — ejecutor headless de pruebas (docs/06-testing.md §Runner headless).
 *
 *   gbtest <rom> --mode {serial|mooneye|acid} [--model dmg] [--max-frames N]
 *          [--expect PATH.rgba] [--dump PATH.rgba]
 *   gbtest <rom> --bench N        velocidad frente a tiempo real (N frames)
 *   gbtest --unit                 unit tests (core/tests/unit_*.c)
 *
 * acid: al ejecutarse LD B,B se compara el framebuffer con --expect (RGBA crudo
 * de 160×144, ver tools/png2rgba.py). --dump escribe el último framebuffer.
 * Salida: 0 = PASS, 1 = FAIL, 2 = error de uso o de carga.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "pocketgb.h"
#include "internal.h"
#include "unit.h"

struct serial_log {
    char text[16384];
    size_t len;
};

static void on_serial_byte(void *user, uint8_t byte)
{
    struct serial_log *log = user;
    if (log->len + 1 < sizeof log->text) {
        log->text[log->len++] = (char)byte;
        log->text[log->len] = '\0';
    }
}

static uint8_t *read_file(const char *path, size_t *len)
{
    FILE *f = fopen(path, "rb");
    if (!f)
        return NULL;
    size_t cap = GB_ROM_MAX_BYTES + 1, n = 0;
    uint8_t *buf = malloc(cap);
    if (buf)
        n = fread(buf, 1, cap, f);
    fclose(f);
    *len = n;
    return buf;
}

/* Framebuffer como bytes R,G,B,A (independiente del endianness). */
static void framebuffer_bytes(const gb *g, uint8_t *out)
{
    const uint32_t *fb = gb_framebuffer(g);
    for (size_t i = 0; i < GB_SCREEN_W * GB_SCREEN_H; i++) {
        out[4 * i] = (uint8_t)fb[i];
        out[4 * i + 1] = (uint8_t)(fb[i] >> 8);
        out[4 * i + 2] = (uint8_t)(fb[i] >> 16);
        out[4 * i + 3] = (uint8_t)(fb[i] >> 24);
    }
}

enum { FB_BYTES = GB_SCREEN_W * GB_SCREEN_H * 4 };

static double now_seconds(void)
{
    struct timespec ts;
    timespec_get(&ts, TIME_UTC);
    return (double)ts.tv_sec + (double)ts.tv_nsec / 1e9;
}

static int run_unit(void)
{
    struct ut t = { 0, 0 };
    struct { const char *name; void (*fn)(struct ut *); } suites[] = {
        { "sha256", unit_sha256 },
        { "cart", unit_cart },
        { "timer", unit_timer },
        { "cpu", unit_cpu },
        { "ppu", unit_ppu },
    };
    for (size_t i = 0; i < sizeof suites / sizeof suites[0]; i++) {
        int before = t.failed;
        suites[i].fn(&t);
        printf("  unit %-8s %s\n", suites[i].name, t.failed == before ? "ok" : "FALLO");
    }
    printf("%s: %d comprobaciones, %d fallos\n", t.failed ? "FAIL" : "PASS", t.checks, t.failed);
    return t.failed ? 1 : 0;
}

static int usage(void)
{
    fprintf(stderr,
            "uso: gbtest <rom> --mode {serial|mooneye|acid} [--model dmg] [--max-frames N]\n"
            "            [--expect PATH.rgba] [--dump PATH.rgba]\n"
            "     gbtest <rom> --bench N\n"
            "     gbtest --unit\n");
    return 2;
}

int main(int argc, char **argv)
{
    if (argc >= 2 && strcmp(argv[1], "--unit") == 0)
        return run_unit();
    if (argc < 3)
        return usage();

    const char *rom_path = argv[1], *mode = NULL, *expect_path = NULL, *dump_path = NULL;
    long max_frames = 3600, bench = 0;
    gb_options opts;
    gb_options_default(&opts);
    opts.model = GB_MODEL_DMG;

    for (int i = 2; i < argc; i += 2) {
        const char *a = argv[i], *v = i + 1 < argc ? argv[i + 1] : NULL;
        if (!v)
            return usage();
        if (strcmp(a, "--mode") == 0) {
            mode = v;
        } else if (strcmp(a, "--model") == 0) {
            if (strcmp(v, "dmg") != 0) {
                fprintf(stderr, "modelo no soportado todavía: %s (CGB llega en M8)\n", v);
                return 2;
            }
        } else if (strcmp(a, "--max-frames") == 0) {
            max_frames = strtol(v, NULL, 10);
        } else if (strcmp(a, "--expect") == 0) {
            expect_path = v;
        } else if (strcmp(a, "--dump") == 0) {
            dump_path = v;
        } else if (strcmp(a, "--bench") == 0) {
            bench = strtol(v, NULL, 10);
        } else {
            return usage();
        }
    }
    if (!bench && (!mode || (strcmp(mode, "serial") != 0 && strcmp(mode, "mooneye") != 0 &&
                             strcmp(mode, "acid") != 0)))
        return usage();
    bool acid = mode && strcmp(mode, "acid") == 0;
    if (acid && !expect_path) {
        fprintf(stderr, "el modo acid necesita --expect\n");
        return 2;
    }
    static uint8_t expect[FB_BYTES], frame[FB_BYTES];
    if (expect_path) {
        size_t n = 0;
        uint8_t *e = read_file(expect_path, &n);
        if (!e || n != FB_BYTES) {
            fprintf(stderr, "%s: se esperaban %d bytes RGBA\n", expect_path, FB_BYTES);
            free(e);
            return 2;
        }
        memcpy(expect, e, FB_BYTES);
        free(e);
    }

    size_t len = 0;
    uint8_t *data = read_file(rom_path, &len);
    if (!data) {
        fprintf(stderr, "no se pudo leer %s\n", rom_path);
        return 2;
    }
    struct serial_log *log = calloc(1, sizeof *log);
    gb *g = gb_create();
    if (!log || !g) {
        fprintf(stderr, "sin memoria\n");
        return 2;
    }
    opts.serial_byte_cb = on_serial_byte;
    opts.serial_user = log;
    gb_result r = gb_load_rom(g, data, len, &opts);
    free(data);
    if (r != GB_OK) {
        fprintf(stderr, "gb_load_rom: %s\n", gb_result_str(r));
        return 2;
    }

    if (bench) {
        double t0 = now_seconds();
        for (long f = 0; f < bench; f++)
            gb_run_frame(g);
        double dt = now_seconds() - t0;
        double emulated = (double)gb_cycle_count(g) / GB_CLOCK_HZ;
        printf("bench: %ld frames, %.3f s emulados en %.3f s reales -> %.1fx tiempo real\n",
               bench, emulated, dt, dt > 0 ? emulated / dt : 0.0);
        gb_destroy(g);
        free(log);
        return 0;
    }

    int result = 1;
    const char *reason = "se alcanzó --max-frames";
    long frame_no = 0;
    for (; frame_no < max_frames; frame_no++) {
        gb_run_frame(g);
        if (strcmp(mode, "serial") == 0) {
            if (strstr(log->text, "Passed")) { result = 0; reason = "serie: Passed"; break; }
            if (strstr(log->text, "Failed")) { reason = "serie: Failed"; break; }
        } else if (acid && g->dbg.ld_b_b) {
            framebuffer_bytes(g, frame);
            size_t diff = 0, first = 0;
            for (size_t i = 0; i < FB_BYTES; i += 4)
                if (memcmp(frame + i, expect + i, 4) != 0 && diff++ == 0)
                    first = i / 4;
            static char msg[96];
            if (diff == 0) {
                result = 0;
                reason = "acid: framebuffer idéntico a la referencia";
            } else {
                snprintf(msg, sizeof msg, "acid: %zu píxeles distintos (primero en x=%zu y=%zu)",
                         diff, first % GB_SCREEN_W, first / GB_SCREEN_W);
                reason = msg;
            }
            break;
        } else if (!acid && g->dbg.ld_b_b) {
            static const uint8_t fib[6] = { 3, 5, 8, 13, 21, 34 };
            result = memcmp(g->dbg.regs, fib, sizeof fib) == 0 ? 0 : 1;
            reason = result == 0 ? "mooneye: registros Fibonacci" : "mooneye: registros de fallo";
            break;
        }
        if (gb_cpu_locked(g)) { reason = "CPU bloqueada (opcode ilegal)"; break; }
    }

    if (dump_path) {
        framebuffer_bytes(g, frame);
        FILE *f = fopen(dump_path, "wb");
        if (!f || fwrite(frame, 1, FB_BYTES, f) != FB_BYTES)
            fprintf(stderr, "no se pudo escribir %s\n", dump_path);
        if (f)
            fclose(f);
    }
    printf("%s: %s (frame %ld)\n", result == 0 ? "PASS" : "FAIL", reason, frame_no);
    if (strcmp(mode, "mooneye") == 0 && g->dbg.ld_b_b)
        printf("  B=%02X C=%02X D=%02X E=%02X H=%02X L=%02X\n", g->dbg.regs[0], g->dbg.regs[1],
               g->dbg.regs[2], g->dbg.regs[3], g->dbg.regs[4], g->dbg.regs[5]);
    if (log->len)
        printf("  serie: %s\n", log->text);
    gb_destroy(g);
    free(log);
    return result;
}
