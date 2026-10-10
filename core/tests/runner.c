/*
 * runner.c — ejecutor headless de pruebas (docs/06-testing.md §Runner headless).
 *
 *   gbtest <rom> --mode {serial|mooneye|acid|frames|blargg} [--model dmg|cgb|auto] [--max-frames N]
 *          [--expect PATH.rgba] [--dump PATH.rgba] [--input GUION] [--wav PATH.wav]
 *   gbtest <rom> --bench N        velocidad frente a tiempo real (N frames)
 *   gbtest <rom> --bench-link N   igual, con dos instancias del ROM en el cable virtual (M9)
 *   gbtest --unit                 unit tests (core/tests/unit_*.c)
 *   gbtest --fuzz-seeds DIR       escribe semillas para los fuzzers (make fuzz)
 *
 * acid: al ejecutarse LD B,B se compara el framebuffer con --expect (RGBA crudo
 * de 160×144, ver tools/png2rgba.py). frames: se ejecutan exactamente
 * --max-frames frames y se compara con --expect (pruebas sin condición de
 * salida, como MBC3-Tester o rtc3test). --dump escribe el último framebuffer.
 * --input: "frame:botones,..." con botones A B S(elect) T(start) R L U D o "-"
 * (ninguno); p. ej. "30:A,40:-" pulsa A en el frame 30 y suelta en el 40.
 * blargg: protocolo de salida por RAM de las pruebas Blargg más nuevas
 * (dmg_sound…): firma DE B0 61 en A001–A003, A000 = 0x80 mientras corre y
 * después el código de resultado (0 = PASS); el texto empieza en A004.
 * --wav: guarda el audio (48 kHz, estéreo, 16 bits) e informa del pico.
 * Salida: 0 = PASS, 1 = FAIL, 2 = error de uso o de carga.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <time.h>

#include "pocketgb.h"
#include "pocketgb_progress.h"
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

enum { MAX_INPUTS = 32 };
struct input_event {
    long frame;
    uint8_t mask;
};

/* Devuelve el número de eventos o -1 si el guion no es válido. */
static int parse_input(const char *s, struct input_event *ev)
{
    int n = 0;
    while (*s) {
        if (n == MAX_INPUTS)
            return -1;
        char *end;
        long f = strtol(s, &end, 10);
        if (end == s || *end != ':' || f < 0)
            return -1;
        s = end + 1;
        uint8_t mask = 0;
        for (; *s && *s != ','; s++) {
            switch (*s) {
            case 'A': mask |= GB_BTN_A; break;
            case 'B': mask |= GB_BTN_B; break;
            case 'S': mask |= GB_BTN_SELECT; break;
            case 'T': mask |= GB_BTN_START; break;
            case 'R': mask |= GB_BTN_RIGHT; break;
            case 'L': mask |= GB_BTN_LEFT; break;
            case 'U': mask |= GB_BTN_UP; break;
            case 'D': mask |= GB_BTN_DOWN; break;
            case '-': break;
            default: return -1;
            }
        }
        ev[n].frame = f;
        ev[n].mask = mask;
        n++;
        if (*s == ',')
            s++;
    }
    return n;
}

/* ---- WAV (PCM 16 bits estéreo) ---- */
struct wav {
    FILE *f;
    uint32_t frames;
    int32_t peak;
    uint32_t clipped;       /* muestras en ±32767/−32768 */
};

static void le16(FILE *f, uint16_t v) { fputc(v & 0xFF, f); fputc(v >> 8, f); }
static void le32(FILE *f, uint32_t v) { le16(f, (uint16_t)v); le16(f, (uint16_t)(v >> 16)); }

static void wav_header(FILE *f, uint32_t rate, uint32_t frames)
{
    fwrite("RIFF", 1, 4, f); le32(f, 36 + frames * 4); fwrite("WAVEfmt ", 1, 8, f);
    le32(f, 16); le16(f, 1); le16(f, 2); le32(f, rate); le32(f, rate * 4); le16(f, 4); le16(f, 16);
    fwrite("data", 1, 4, f); le32(f, frames * 4);
}

static void wav_drain(gb *g, struct wav *w)
{
    int16_t buf[2 * 1024];
    size_t n;
    while ((n = gb_audio_read(g, buf, 1024)) > 0) {
        for (size_t i = 0; i < 2 * n; i++) {
            int32_t s = buf[i] < 0 ? -(int32_t)buf[i] : buf[i];
            if (s > w->peak)
                w->peak = s;
            if (buf[i] >= 32767 || buf[i] <= -32768)
                w->clipped++;
            if (w->f)
                le16(w->f, (uint16_t)buf[i]);
        }
        w->frames += (uint32_t)n;
    }
}

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
        { "state", unit_state },
        { "apu", unit_apu },
        { "cgb", unit_cgb },
        { "link", unit_link },
        { "progress", unit_progress },
        { "pgbm", unit_pgbm },
        { "pgbmx", unit_pgbm_cross },
    };
    for (size_t i = 0; i < sizeof suites / sizeof suites[0]; i++) {
        int before = t.failed;
        suites[i].fn(&t);
        printf("  unit %-8s %s\n", suites[i].name, t.failed == before ? "ok" : "FALLO");
    }
    printf("%s: %d comprobaciones, %d fallos\n", t.failed ? "FAIL" : "PASS", t.checks, t.failed);
    return t.failed ? 1 : 0;
}

static int write_file(const char *path, const uint8_t *d, size_t n)
{
    FILE *f = fopen(path, "wb");
    bool ok = f && fwrite(d, 1, n, f) == n;
    if (f && fclose(f) != 0)
        ok = false;
    if (!ok)
        fprintf(stderr, "no se pudo escribir %s\n", path);
    return ok ? 0 : 2;
}

/* Semillas: un ROM sintético que ejecuta un bucle y un estado válido del ROM
 * fijo de fuzz_state_load (mismo contenido que fixed_instance()). */
static int fuzz_seeds(const char *dir)
{
    static const uint8_t prog[] = { 0x3C, 0xEA, 0x00, 0xC0, 0x18, 0xFA };
    char path[1024];
    int rc = 0;
    uint8_t *rom = ut_make_rom(0x8000, 0x10, 0x00, 0x03, prog, sizeof prog);
    if (!rom)
        return 2;
    rom[0x134] = 0;   /* el ROM de fuzz_state_load no tiene título */
    rom[0x135] = rom[0x136] = rom[0x137] = 0;
    rom[0x14D] = 0;
    snprintf(path, sizeof path, "%s/fuzz_load_rom/seed_rom.bin", dir);
    rc |= write_file(path, rom, 0x8000);
    gb *g = gb_create();
    if (g && gb_load_rom(g, rom, 0x8000, NULL) == GB_OK) {
        for (int f = 0; f < 5; f++)
            gb_run_frame(g);
        size_t n = gb_state_size(g);
        uint8_t *s = malloc(n);
        if (s && gb_state_save(g, s, n) == GB_OK) {
            snprintf(path, sizeof path, "%s/fuzz_state_load/seed_state.bin", dir);
            rc |= write_file(path, s, n);
        }
        free(s);
    }
    gb_destroy(g);
    free(rom);
    /* fuzz_link: lado 0 maestro (SB 0x55, SC 0x81), lado 1 esclavo, los dos en JR -2. */
    static const uint8_t link_seed[] = { 0x00, 0x80, 0x55, 0x81, 0x18, 0xFE, 0x18, 0xFE };
    snprintf(path, sizeof path, "%s/fuzz_link/seed_link.bin", dir);
    rc |= write_file(path, link_seed, sizeof link_seed);
    /* fuzz_progress: una partida sintética válida por juego (Rojo, Azul, Amarillo, Oro, Plata, Cristal). */
    {
        static uint8_t pseed[2 + PGB_PROG_SAVE_BYTES];
        for (unsigned i = 0; i < UT_PROGRESS_SEEDS; i++) {
            size_t n = ut_progress_seed(i, pseed, sizeof pseed);
            if (!n)
                return 2;
            snprintf(path, sizeof path, "%s/fuzz_progress/seed_game%u.bin", dir, i);
            rc |= write_file(path, pseed, n);
        }
    }
    /* fuzz_pgbm: paquetes .pgbm sintéticos (mínimo, con tipo desconocido, completo, con CRC roto y modo codificador). */
    {
        uint8_t qseed[4096];
        for (unsigned i = 0; i < UT_PGBM_SEEDS; i++) {
            size_t n = ut_pgbm_seed(i, qseed, sizeof qseed);
            if (!n)
                return 2;
            snprintf(path, sizeof path, "%s/fuzz_pgbm/seed_%u.bin", dir, i);
            rc |= write_file(path, qseed, n);
        }
    }
    /* Semillas largas (auditoría M9, H5): los programas de intercambio de
     * unit_link.c, uno por lado, con el reparto a la mitad (d[1] = 0x80; los dos
     * miden lo mismo). d[0]: modelos por lado y operaciones a mitad. */
    static const struct {
        uint8_t flags;
        bool ds;
        uint8_t sc;
        const char *name;
    } xs[] = {
        { 0x00, false, 0x81, "xchg_dmg" },
        { 0x05, false, 0x83, "xchg_cgb_fast" },
        { 0x05, true, 0x83, "xchg_cgb_2x_fast" },
        { 0x02, false, 0x81, "xchg_compat_dmg" },
        { 0x10, false, 0x81, "xchg_state" },
        { 0x60, false, 0x81, "xchg_reload_detach" },
    };
    for (size_t i = 0; i < sizeof xs / sizeof xs[0]; i++) {
        uint8_t seed[4 + 2 * 256];
        seed[0] = xs[i].flags;
        seed[1] = 0x80;
        seed[2] = 0x00;
        seed[3] = 0x00;
        size_t na = ut_link_exchange_prog(seed + 4, 256, xs[i].ds, xs[i].sc, 0x5A);
        size_t nb = ut_link_exchange_prog(seed + 4 + na, 256, xs[i].ds, 0x80, 0xA5);
        if (!na || na != nb)
            return 2;
        snprintf(path, sizeof path, "%s/fuzz_link/seed_%s.bin", dir, xs[i].name);
        rc |= write_file(path, seed, 4 + na + nb);
    }
    return rc;
}

static int usage(void)
{
    fprintf(stderr,
            "uso: gbtest <rom> --mode {serial|mooneye|acid|frames|blargg} [--model dmg|cgb|auto] [--max-frames N]\n"
            "            [--expect PATH.rgba] [--dump PATH.rgba]\n"
            "     gbtest <rom> --bench N | --bench-link N\n"
            "     gbtest --unit\n");
    return 2;
}

int main(int argc, char **argv)
{
    if (argc >= 2 && strcmp(argv[1], "--unit") == 0)
        return run_unit();
    if (argc >= 3 && strcmp(argv[1], "--fuzz-seeds") == 0)
        return fuzz_seeds(argv[2]);
    if (argc < 3)
        return usage();

    const char *rom_path = argv[1], *mode = NULL, *expect_path = NULL, *dump_path = NULL;
    const char *input_script = NULL, *wav_path = NULL;
    long max_frames = 3600, bench = 0;
    bool bench_link = false;
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
            if (strcmp(v, "dmg") == 0) {
                opts.model = GB_MODEL_DMG;
            } else if (strcmp(v, "cgb") == 0) {
                opts.model = GB_MODEL_CGB;
            } else if (strcmp(v, "auto") == 0) {
                opts.model = GB_MODEL_AUTO;
            } else {
                fprintf(stderr, "modelo no válido: %s (dmg | cgb | auto)\n", v);
                return 2;
            }
        } else if (strcmp(a, "--max-frames") == 0) {
            max_frames = strtol(v, NULL, 10);
        } else if (strcmp(a, "--expect") == 0) {
            expect_path = v;
        } else if (strcmp(a, "--dump") == 0) {
            dump_path = v;
        } else if (strcmp(a, "--wav") == 0) {
            wav_path = v;
        } else if (strcmp(a, "--input") == 0) {
            input_script = v;
        } else if (strcmp(a, "--bench") == 0) {
            bench = strtol(v, NULL, 10);
        } else if (strcmp(a, "--bench-link") == 0) {
            bench = strtol(v, NULL, 10);
            bench_link = true;
        } else {
            return usage();
        }
    }
    if (!bench && (!mode || (strcmp(mode, "serial") != 0 && strcmp(mode, "mooneye") != 0 &&
                             strcmp(mode, "acid") != 0 && strcmp(mode, "frames") != 0 &&
                             strcmp(mode, "blargg") != 0)))
        return usage();
    bool acid = mode && strcmp(mode, "acid") == 0;
    bool frames_mode = mode && strcmp(mode, "frames") == 0;
    if ((acid || frames_mode) && !expect_path) {
        fprintf(stderr, "los modos acid y frames necesitan --expect\n");
        return 2;
    }
    struct input_event inputs[MAX_INPUTS];
    int n_inputs = 0;
    if (input_script && (n_inputs = parse_input(input_script, inputs)) < 0) {
        fprintf(stderr, "--input no válido: %s\n", input_script);
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
    gb *peer = NULL;
    gb_link *link = NULL;
    if (r == GB_OK && bench_link) {
        peer = gb_create();
        link = gb_link_create();
        if (!peer || !link) {
            fprintf(stderr, "sin memoria\n");
            return 2;
        }
        r = gb_load_rom(peer, data, len, &opts);
        gb_link_attach(link, g, peer);
    }
    free(data);
    if (r != GB_OK) {
        fprintf(stderr, "gb_load_rom: %s\n", gb_result_str(r));
        return 2;
    }

    if (bench_link) {
        double t0 = now_seconds();
        for (long f = 0; f < bench; f++)
            gb_link_run_frame(link);
        double dt = now_seconds() - t0;
        double emulated = (double)gb_cycle_count(g) / GB_CLOCK_HZ;
        printf("bench-link: %ld frames x 2 instancias, %.3f s emulados en %.3f s reales -> %.1fx tiempo real\n",
               bench, emulated, dt, dt > 0 ? emulated / dt : 0.0);
        gb_link_destroy(link);
        gb_destroy(peer);
        gb_destroy(g);
        free(log);
        return 0;
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

    struct wav wav = { NULL, 0, 0, 0 };
    if (wav_path) {
        wav.f = fopen(wav_path, "wb");
        if (!wav.f) {
            fprintf(stderr, "no se pudo abrir %s\n", wav_path);
            return 2;
        }
        wav_header(wav.f, opts.sample_rate, 0);   /* se reescribe al final */
    }

    int result = 1;
    const char *reason = "se alcanzó --max-frames";
    long frame_no = 0;
    for (; frame_no < max_frames; frame_no++) {
        for (int i = 0; i < n_inputs; i++)
            if (inputs[i].frame == frame_no)
                gb_set_buttons(g, inputs[i].mask);
        gb_run_frame(g);
        wav_drain(g, &wav);
        if (strcmp(mode, "blargg") == 0) {
            const uint8_t *ram = g->cart.ram;
            if (ram && g->cart.ram_size >= 5 && ram[1] == 0xDE && ram[2] == 0xB0 &&
                ram[3] == 0x61 && ram[0] != 0x80) {
                static char bmsg[64];
                snprintf(bmsg, sizeof bmsg, "blargg: código %u", ram[0]);
                result = ram[0] == 0 ? 0 : 1;
                reason = bmsg;
                size_t k = 4;
                while (k < g->cart.ram_size && ram[k] && log->len + 1 < sizeof log->text)
                    on_serial_byte(log, ram[k++]);   /* el texto se muestra como la serie */
                break;
            }
        } else if (strcmp(mode, "serial") == 0) {
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
        } else if (strcmp(mode, "mooneye") == 0 && g->dbg.ld_b_b) {
            static const uint8_t fib[6] = { 3, 5, 8, 13, 21, 34 };
            result = memcmp(g->dbg.regs, fib, sizeof fib) == 0 ? 0 : 1;
            reason = result == 0 ? "mooneye: registros Fibonacci" : "mooneye: registros de fallo";
            break;
        }
        if (gb_cpu_locked(g)) { reason = "CPU bloqueada (opcode ilegal)"; break; }
    }

    if (wav.f) {
        bool ok = fseek(wav.f, 0, SEEK_SET) == 0;
        if (ok)
            wav_header(wav.f, opts.sample_rate, wav.frames);
        if (fclose(wav.f) != 0 || !ok) {
            fprintf(stderr, "no se pudo escribir %s\n", wav_path);
            result = 2;
        }
        printf("  wav: %u frames a %u Hz, pico %d (%.1f dBFS), %u muestras saturadas\n",
               wav.frames, opts.sample_rate, wav.peak,
               wav.peak ? 20.0 * log10(wav.peak / 32768.0) : -999.0, wav.clipped);
    }
    if (frames_mode) {
        framebuffer_bytes(g, frame);
        size_t diff = 0;
        for (size_t i = 0; i < FB_BYTES; i += 4)
            diff += memcmp(frame + i, expect + i, 4) != 0;
        static char fmsg[80];
        if (diff == 0) {
            result = 0;
            reason = "frames: framebuffer idéntico a la referencia";
        } else {
            snprintf(fmsg, sizeof fmsg, "frames: %zu píxeles distintos", diff);
            reason = fmsg;
        }
    }
    if (dump_path) {
        framebuffer_bytes(g, frame);
        FILE *f = fopen(dump_path, "wb");
        bool ok = f && fwrite(frame, 1, FB_BYTES, f) == FB_BYTES;
        if (f && fclose(f) != 0)
            ok = false;
        if (!ok) {
            fprintf(stderr, "no se pudo escribir %s\n", dump_path);
            result = 2;
        }
    }
    printf("%s: %s (frame %ld)\n", result == 0 ? "PASS" : result == 1 ? "FAIL" : "ERROR", reason, frame_no);
    if (strcmp(mode, "mooneye") == 0 && g->dbg.ld_b_b)
        printf("  B=%02X C=%02X D=%02X E=%02X H=%02X L=%02X\n", g->dbg.regs[0], g->dbg.regs[1],
               g->dbg.regs[2], g->dbg.regs[3], g->dbg.regs[4], g->dbg.regs[5]);
    if (log->len)
        printf("  serie: %s\n", log->text);
    gb_destroy(g);
    free(log);
    return result;
}
