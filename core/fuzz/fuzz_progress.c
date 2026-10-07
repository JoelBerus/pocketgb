/*
 * fuzz_progress.c — libFuzzer: pgb_progress_read / pgb_progress_identify (N6-C).
 *
 * Entrada: [juego][indicadores][bytes...]
 *   juego: 0..5 = cabecera sintética de Rojo, Azul, Amarillo, Oro, Plata o Cristal; 6..9 = Amarillo
 *          europeo («POKEMON YELAPS» + D, F, I, S); ≥ 10 = los primeros 0x200 bytes siguientes son la
 *          cabecera tal cual (casi siempre se rechaza).
 *   indicadores: bit 0 = recalcular el checksum; bit 1 = forzar, en la 2.ª generación, los bytes
 *                de validación (99 y 127); bit 6 = forzar BCD, horas ≤ 999, dinero ≤ 999999 y
 *                minutos y segundos < 60; bit 7 = forzar el nombre terminado (según el juego que
 *                identifique la cabecera: combinando los bits se llega a cada rechazo y a la
 *                rama de éxito);
 *                bits 2-3 = longitud de la partida: 0 = la que traigan los bytes, 1 = 0x8000,
 *                2 = 0x8000 + 48, 3 = 0x8000 + 44;
 *                bits 4-5 = longitud de la cabecera: 0 = 0x150, 1 = 0x14F, 2 = 0x200, 3 = 0.
 *   resto: la partida (se rellena con ceros hasta la longitud elegida).
 * La cabecera y la partida se copian a bloques del montón de tamaño EXACTO, para que ASan
 * detecte cualquier lectura fuera de rango. Se comprueban invariantes de la salida y que el
 * resultado sea determinista; cualquier violación llama a abort().
 *
 * Con -DFUZZ_STANDALONE se compila además un conductor sin libFuzzer (make fuzz-progress-smoke):
 * parte de las semillas del corpus y las muta al azar durante N segundos. No guía por cobertura.
 * Los desplazamientos de abajo repiten a propósito los de docs/03-core-spec.md (son del fuzzer,
 * no del núcleo; las variables estáticas también).
 */
#include <stdlib.h>
#include <string.h>

#include "pocketgb_progress.h"

enum {
    HDR_BUF = 0x200,
    SAVE = PGB_PROG_SAVE_BYTES,
    G1_START = 0x2598, G1_CKSUM = 0x3523, G1_MONEY = 0x25F3, G1_TIME = 0x2CED,
    G2_CV1 = 0x2008, G2_START = 0x2009, G2_NAME = 0x200B,
    GS_END = 0x2D69, GS_CV2 = 0x2D6B, GS_TIME = 0x2053, GS_MONEY = 0x23DB,
    C_END = 0x2B83, C_CKSUM = 0x2D0D, C_CV2 = 0x2D0F, C_TIME = 0x2052, C_MONEY = 0x23DC
};

static void make_header(uint8_t *h, unsigned game)
{
    static const char *const title[6] = { "POKEMON RED", "POKEMON BLUE", "POKEMON YELLOW",
                                          "POKEMON_GLD", "POKEMON_SLV", "PM_CRYSTAL" };
    memset(h, 0, HDR_BUF);
    if (game >= 6) {                       /* Amarillo europeo: «POKEMON YELAPS» + idioma en 0x142 */
        memcpy(h + 0x134, "POKEMON YELAPS", 14);
        h[0x142] = (uint8_t)"DFIS"[game - 6];
    } else {
        memcpy(h + 0x134, title[game], strlen(title[game]));
        if (game >= 3)
            memcpy(h + 0x13F, "AAUE", 4);
    }
    h[0x14A] = 0x01;
}

enum { FIX_CHECKSUM = 0x01, FIX_VALID = 0x02, FIX_RANGES = 0x40, FIX_NAME = 0x80 };

/* Deja la partida coherente (por partes, según `flags`) para el juego que identifica la cabecera. */
static void fixup(uint8_t *s, pgb_prog_game game, unsigned flags)
{
    if (game == PGB_PROG_GEN1) {
        if (flags & FIX_RANGES) {
            for (int i = 0; i < 3; i++)
                s[G1_MONEY + i] = (uint8_t)(((s[G1_MONEY + i] >> 4) % 10) << 4 | ((s[G1_MONEY + i] & 15) % 10));
            s[G1_TIME + 2] = (uint8_t)(s[G1_TIME + 2] % 60);
            s[G1_TIME + 3] = (uint8_t)(s[G1_TIME + 3] % 60);
        }
        if (flags & FIX_NAME)
            s[G1_START + 10] = 0x50;
        if (flags & FIX_CHECKSUM) {
            uint8_t sum = 0;
            for (size_t i = G1_START; i < G1_CKSUM; i++)
                sum = (uint8_t)(sum + s[i]);
            s[G1_CKSUM] = (uint8_t)~sum;
        }
    } else if (game == PGB_PROG_GEN2_GS || game == PGB_PROG_GEN2_C) {
        bool c = game == PGB_PROG_GEN2_C;
        size_t end = c ? C_END : GS_END, pos = c ? C_CKSUM : GS_END, time = c ? C_TIME : GS_TIME;
        if (flags & FIX_VALID) {
            s[G2_CV1] = 99;
            s[c ? C_CV2 : GS_CV2] = 127;
        }
        if (flags & FIX_NAME)
            s[G2_NAME + 10] = 0x50;
        if (flags & FIX_RANGES) {
            size_t money = c ? C_MONEY : GS_MONEY;
            unsigned hours = (((unsigned)s[time] << 8) | s[time + 1]) % 1000u;
            unsigned cash = (((unsigned)s[money] << 16) | ((unsigned)s[money + 1] << 8) | s[money + 2]) % 1000000u;
            s[time] = (uint8_t)(hours >> 8);
            s[time + 1] = (uint8_t)hours;
            s[time + 2] = (uint8_t)(s[time + 2] % 60);
            s[time + 3] = (uint8_t)(s[time + 3] % 60);
            s[money] = (uint8_t)(cash >> 16);
            s[money + 1] = (uint8_t)(cash >> 8);
            s[money + 2] = (uint8_t)cash;
        }
        if (flags & FIX_CHECKSUM) {
            unsigned sum = 0;
            for (size_t i = G2_START; i < end; i++)
                sum = (sum + s[i]) & 0xFFFFu;
            s[pos] = (uint8_t)sum;
            s[pos + 1] = (uint8_t)(sum >> 8);
        }
    }
}

static bool valid_utf8(const char *str)
{
    const uint8_t *p = (const uint8_t *)str;
    while (*p) {
        size_t extra = *p < 0x80 ? 0 : (*p & 0xE0) == 0xC0 ? 1 : (*p & 0xF0) == 0xE0 ? 2 : 99;
        if (extra == 99)
            return false;
        p++;
        for (size_t i = 0; i < extra; i++, p++)
            if ((*p & 0xC0) != 0x80)
                return false;
    }
    return true;
}

static void check_output(bool ok, const pgb_progress *p, pgb_prog_game id)
{
    pgb_progress zero;
    memset(&zero, 0, sizeof zero);
    if (!ok) {
        if (memcmp(p, &zero, sizeof zero) != 0)
            abort();                       /* un fallo debe dejar la salida a cero */
        return;
    }
    if (p->game != id || id == PGB_PROG_NONE)
        abort();
    size_t n = 0;
    while (n < PGB_PROG_NAME_MAX && p->player_name[n])
        n++;
    if (n == PGB_PROG_NAME_MAX || !valid_utf8(p->player_name))
        abort();
    for (size_t i = n; i < PGB_PROG_NAME_MAX; i++)
        if (p->player_name[i] != '\0')
            abort();                       /* determinismo: nada sin inicializar tras el NUL */
    unsigned bits = 0;
    for (unsigned m = p->badges_mask; m; m &= m - 1u)
        bits++;
    if (bits != p->badges_count)
        abort();
    unsigned max_dex = id == PGB_PROG_GEN1 ? 151u : 251u;
    if (p->pokedex_owned > max_dex || p->pokedex_seen > max_dex)
        abort();
    if (p->money > 999999u)                /* BCD de 6 dígitos (1.ª gen) y MAX_MONEY (2.ª gen) */
        abort();
    if (id == PGB_PROG_GEN1 && (p->badges_mask > 0xFF || p->play_hours > 255))
        abort();
    if (id != PGB_PROG_GEN1 && p->play_hours > 999)    /* GameTimer se detiene en 999:59:59 */
        abort();
    if (p->play_minutes > 59 || p->play_seconds > 59)
        abort();
}

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size);

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size)
{
    if (size < 2)
        return 0;
    unsigned game = data[0], flags = data[1];
    const uint8_t *body = data + 2;
    size_t blen = size - 2;

    uint8_t hbuf[HDR_BUF];
    if (game < 10) {
        make_header(hbuf, game);
    } else {
        size_t n = blen < HDR_BUF ? blen : HDR_BUF;
        memset(hbuf, 0, sizeof hbuf);
        memcpy(hbuf, body, n);
        body += n;
        blen -= n;
    }
    static const size_t hlen_of[4] = { 0x150, 0x14F, HDR_BUF, 0 };
    size_t hlen = hlen_of[(flags >> 4) & 3];

    size_t slen;
    switch ((flags >> 2) & 3) {
    case 1: slen = SAVE; break;
    case 2: slen = SAVE + 48; break;
    case 3: slen = SAVE + 44; break;
    default: slen = blen; break;
    }

    uint8_t *hdr = malloc(hlen ? hlen : 1);
    uint8_t *sram = malloc(slen ? slen : 1);
    if (!hdr || !sram) {
        free(hdr);
        free(sram);
        return 0;
    }
    memcpy(hdr, hbuf, hlen);
    memset(sram, 0, slen);
    memcpy(sram, body, blen < slen ? blen : slen);

    pgb_prog_game id = pgb_progress_identify(hdr, hlen);
    if (slen >= SAVE)
        fixup(sram, id, flags);

    pgb_progress a, b;
    memset(&a, 0xA5, sizeof a);
    memset(&b, 0x5A, sizeof b);
    bool oka = pgb_progress_read(hdr, hlen, sram, slen, &a);
    bool okb = pgb_progress_read(hdr, hlen, sram, slen, &b);
    if (oka != okb || memcmp(&a, &b, sizeof a) != 0)
        abort();                           /* determinista, y sin bytes sin inicializar */
    check_output(oka, &a, id);
    if (oka && slen < SAVE)
        abort();                           /* nunca debe aceptar una partida corta */

    free(hdr);
    free(sram);
    return 0;
}

#ifdef FUZZ_STANDALONE
/* Conductor sin libFuzzer: uso `fuzz_progress_smoke SEGUNDOS CARPETA_DE_SEMILLAS`. */
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

enum { MAX_SEEDS = 1024, MAX_LEN = 33000 };

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
        if (n >= 2) {
            uint8_t *fit = realloc(buf, n);     /* una semilla = un bloque de su tamaño */
            seed[ns] = fit ? fit : buf;
            seed_len[ns++] = n;
        } else {
            free(buf);
        }
    }
    if (d)
        closedir(d);
    if (!ns) {
        fprintf(stderr, "sin semillas en %s (make fuzz-progress-smoke las genera)\n", argv[2]);
        return 2;
    }
    /* Posiciones de la entrada (2 + desplazamiento en la partida) donde cambian los campos que lee el núcleo. */
    static const size_t hot[] = { 0x2008, 0x2009, 0x200B, 0x2015, 0x2052, 0x2053, 0x2056, 0x23DB, 0x23DC, 0x23E4, 0x23E5,
                                  0x2598, 0x25A3, 0x25B6, 0x25F3, 0x2602, 0x2A27, 0x2A47, 0x2A4C, 0x2A6C, 0x2B82, 0x2B83,
                                  0x2CED, 0x2CEF, 0x2CF0, 0x2D0D, 0x2D0F, 0x2D68, 0x2D69, 0x2D6B, 0x3522, 0x3523 };
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
            switch (rnd() % 8) {
            case 0: work[rnd() % n] ^= (uint8_t)(1u << (rnd() % 8)); break;
            case 1: work[rnd() % n] = (uint8_t)rnd(); break;
            case 2:
            case 3:
                if (n > 2) {                                                 /* campos que lee el núcleo */
                    size_t at = 2 + hot[rnd() % (sizeof hot / sizeof hot[0])] % (n - 2);
                    work[at] = (uint8_t)(rnd() & 1 ? rnd() : work[at] ^ (1u << (rnd() % 8)));
                }
                break;
            case 4: work[0] = (uint8_t)(rnd() % 12); break;                 /* juego (0..9 preajustes, 10..11 cabecera libre) */
            case 5: work[1] = (uint8_t)rnd(); break;                         /* indicadores */
            case 6: n = 2 + rnd() % (n - 1); break;                          /* recorta */
            default:
                if (n + 64 < (size_t)MAX_LEN) {                              /* alarga */
                    memset(work + n, (int)(rnd() & 1 ? 0xFF : 0x00), 64);
                    n += 64;
                }
                break;
            }
            if (n < 2)
                n = 2;
        }
        LLVMFuzzerTestOneInput(work, n);
        runs++;
    }
    printf("fuzz_progress_smoke: %lu ejecuciones en %.0f s, sin fallos\n", runs, secs);
    return 0;
}
#endif
