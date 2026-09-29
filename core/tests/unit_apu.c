/* unit_apu.c — registros, encendido/apagado, longitud, sweep y salida de audio (M5). */
#include <stdlib.h>
#include <string.h>

#include "unit.h"

static gb *make_gb(uint32_t rate)
{
    static const uint8_t loop[] = { 0x18, 0xFE };   /* JR -2 */
    uint8_t *rom = ut_make_rom(0x8000, 0x00, 0x00, 0x00, loop, sizeof loop);
    gb_options o;
    gb_options_default(&o);
    o.sample_rate = rate;
    gb *g = gb_create();
    if (!rom || !g || gb_load_rom(g, rom, 0x8000, &o) != GB_OK) {
        free(rom);
        gb_destroy(g);
        return NULL;
    }
    free(rom);
    return g;
}

void unit_apu(struct ut *t)
{
    gb *g = make_gb(48000);
    CHECK(t, g != NULL);
    if (!g)
        return;

    /* Post-boot: NR52 = F1 (APU encendido, canal 1 activo), máscaras de lectura. */
    CHECK(t, mmu_read(g, 0xFF26) == 0xF1);
    CHECK(t, mmu_read(g, 0xFF11) == 0xBF && mmu_read(g, 0xFF14) == 0xBF);
    CHECK(t, mmu_read(g, 0xFF15) == 0xFF && mmu_read(g, 0xFF30 + 3) == g->apu.regs[0x23]);

    /* Apagar: registros a 0 (leen su máscara), canales parados, escrituras ignoradas. */
    mmu_write(g, 0xFF30, 0xAB);
    mmu_write(g, 0xFF26, 0x00);
    CHECK(t, mmu_read(g, 0xFF26) == 0x70);
    CHECK(t, mmu_read(g, 0xFF12) == 0x00 && mmu_read(g, 0xFF10) == 0x80);
    mmu_write(g, 0xFF12, 0xF0);
    CHECK(t, mmu_read(g, 0xFF12) == 0x00);
    CHECK(t, mmu_read(g, 0xFF30) == 0xAB);          /* la wave RAM se conserva */
    mmu_write(g, 0xFF26, 0x80);
    CHECK(t, mmu_read(g, 0xFF26) == 0xF0);

    /* Canal 2: disparo con DAC encendido y longitud 1 → se para en el siguiente paso de longitud. */
    mmu_write(g, 0xFF17, 0xF0);                     /* volumen 15, DAC on */
    mmu_write(g, 0xFF16, 0x3F);                     /* longitud 64 - 63 = 1 */
    g->apu.fs_step = 0;                             /* el siguiente paso cuenta longitud */
    mmu_write(g, 0xFF19, 0xC0);                     /* disparo + longitud */
    CHECK(t, mmu_read(g, 0xFF26) & 0x02);
    apu_frame_step(g);                              /* paso 0: longitud 1 → 0 */
    CHECK(t, !(mmu_read(g, 0xFF26) & 0x02));
    /* En la primera mitad (siguiente paso impar), habilitar la longitud la cuenta una
     * vez extra: con longitud 1 y sin disparo, el canal se para en el acto. */
    mmu_write(g, 0xFF19, 0x80);                     /* disparo sin longitud: 64 */
    mmu_write(g, 0xFF16, 0x3F);                     /* longitud 1 */
    CHECK(t, g->apu.fs_step == 1 && (mmu_read(g, 0xFF26) & 0x02));
    mmu_write(g, 0xFF19, 0x40);                     /* habilitar longitud, sin disparo */
    CHECK(t, !(mmu_read(g, 0xFF26) & 0x02));
    /* Apagar el DAC para el canal inmediatamente. */
    mmu_write(g, 0xFF19, 0x80);
    CHECK(t, mmu_read(g, 0xFF26) & 0x02);
    mmu_write(g, 0xFF17, 0x00);
    CHECK(t, !(mmu_read(g, 0xFF26) & 0x02));

    /* Sweep: desborde al disparar con shift ≠ 0 apaga el canal 1. */
    mmu_write(g, 0xFF12, 0xF0);
    mmu_write(g, 0xFF10, 0x11);                     /* periodo 1, suma, shift 1 */
    mmu_write(g, 0xFF13, 0xFF);
    mmu_write(g, 0xFF14, 0x87);                     /* freq 0x7FF + 0x3FF > 2047 */
    CHECK(t, !(mmu_read(g, 0xFF26) & 0x01));
    /* Quitar el modo negativo después de usarlo apaga el canal 1. */
    mmu_write(g, 0xFF10, 0x19);                     /* negativo, shift 1 */
    mmu_write(g, 0xFF14, 0x84);
    CHECK(t, mmu_read(g, 0xFF26) & 0x01);
    mmu_write(g, 0xFF10, 0x11);
    CHECK(t, !(mmu_read(g, 0xFF26) & 0x01));

    /* Salida: ~48000 frames por segundo emulado; silencio → 0 tras el pasa-altos. */
    int16_t *buf = malloc(sizeof(int16_t) * 2 * APU_BUF_FRAMES);
    CHECK(t, buf != NULL);
    if (buf) {
        mmu_write(g, 0xFF26, 0x00);                 /* sin sonido */
        gb_audio_read(g, buf, APU_BUF_FRAMES);
        size_t total = 0;
        for (int f = 0; f < 60; f++) {
            gb_run_frame(g);
            total += gb_audio_read(g, buf, APU_BUF_FRAMES);
        }
        CHECK(t, total >= 48150 && total <= 48290);  /* 60 × 70 224 T-ciclos ≈ 1,0046 s → ~48 219 */
        CHECK(t, buf[0] == 0 && buf[1] == 0);

        /* Onda cuadrada del canal 2 a 1 kHz aprox.: hay señal con signo alterno. */
        mmu_write(g, 0xFF26, 0x80);
        mmu_write(g, 0xFF24, 0x77);
        mmu_write(g, 0xFF25, 0x22);                 /* canal 2 a ambos lados */
        mmu_write(g, 0xFF17, 0xF0);
        mmu_write(g, 0xFF16, 0x80);                 /* duty 50 % */
        mmu_write(g, 0xFF18, 0x83);                 /* freq 1923 → 131072/125 ≈ 1049 Hz */
        mmu_write(g, 0xFF19, 0x87);
        gb_run_frame(g);
        size_t n = gb_audio_read(g, buf, APU_BUF_FRAMES);
        size_t pos = 0, neg = 0;
        for (size_t i = 0; i < n; i++) {
            pos += buf[2 * i] > 1000;
            neg += buf[2 * i] < -1000;
            CHECK(t, buf[2 * i] == buf[2 * i + 1]);
        }
        CHECK(t, n > 700 && pos > n / 4 && neg > n / 4);
        free(buf);
    }
    gb_destroy(g);

    /* sample_rate = 0: sin audio. */
    g = make_gb(0);
    CHECK(t, g != NULL);
    if (g) {
        gb_run_frame(g);
        CHECK(t, gb_audio_available(g) == 0);
        gb_destroy(g);
    }
}
