/*
 * shot.c — oráculo de desarrollo para la PPU del GBA (G3): ejecuta un ROM en
 * libmgba (MPL-2.0, se compila aparte en tools/oracle/mgba y NUNCA se enlaza
 * en la app) y vuelca el framebuffer de cada frame pedido en RGBA crudo
 * (240x160x4, R en el byte bajo, A = 0xFF), el mismo formato que
 * `gbatest --dump`. Código propio; solo usa la API pública de mGBA.
 * mGBA se compila con COLOR_16_BIT (BGR555): así mezcla y aclara con 5 bits
 * por canal, como el hardware, y la expansión a 8 bits es la nuestra.
 *   shot ROM SALIDA.rgba FRAME [FRAME...]
 *   shot --audio ROM SALIDA.wav FRAMES     (estéreo int16 a 48 kHz)
 */
#ifndef COLOR_16_BIT
#error "compilar con -DCOLOR_16_BIT (igual que libmgba)"
#endif
#include <mgba/core/blip_buf.h>
#include <mgba/core/core.h>
#include <mgba/core/config.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int audio_main(int argc, char **argv)
{
    if (argc < 5) return 2;
    struct mCore *core = mCoreFind(argv[2]);
    if (!core || !core->init(core)) return 2;
    mCoreInitConfig(core, NULL);
    unsigned w, h;
    core->desiredVideoDimensions(core, &w, &h);
    color_t *buf = calloc((size_t)w * h, sizeof *buf);
    core->setVideoBuffer(core, buf, w);
    if (!mCoreLoadFile(core, argv[2])) return 2;
    core->reset(core);
    core->setAudioBufferSize(core, 4096);
    blip_set_rates(core->getAudioChannel(core, 0), core->frequency(core), 48000);
    blip_set_rates(core->getAudioChannel(core, 1), core->frequency(core), 48000);
    int frames = atoi(argv[4]);
    short *pcm = malloc((size_t)(frames + 2) * 2048 * 2 * sizeof *pcm);
    size_t n = 0;
    for (int f = 0; f < frames; f++) {
        core->runFrame(core);
        int avail = blip_samples_avail(core->getAudioChannel(core, 0));
        blip_read_samples(core->getAudioChannel(core, 0), pcm + 2 * n, avail, 1);
        blip_read_samples(core->getAudioChannel(core, 1), pcm + 2 * n + 1, avail, 1);
        n += (size_t)avail;
    }
    FILE *o = fopen(argv[3], "wb");
    if (!o) return 2;
    uint32_t rate = 48000, bytes = (uint32_t)(n * 4), riff = 36 + bytes, br = rate * 4;
    uint8_t hd[44] = {'R','I','F','F',0,0,0,0,'W','A','V','E','f','m','t',' ',16,0,0,0,1,0,2,0,
                      0,0,0,0,0,0,0,0,4,0,16,0,'d','a','t','a',0,0,0,0};
    memcpy(hd + 4, &riff, 4); memcpy(hd + 24, &rate, 4); memcpy(hd + 28, &br, 4); memcpy(hd + 40, &bytes, 4);
    fwrite(hd, 1, 44, o);
    fwrite(pcm, 4, n, o);
    fclose(o);
    core->deinit(core);
    return 0;
}

int main(int argc, char **argv)
{
    if (argc > 1 && !strcmp(argv[1], "--audio")) return audio_main(argc, argv);
    if (argc < 4) {
        fprintf(stderr, "uso: shot ROM SALIDA FRAME...\n");
        return 2;
    }
    struct mCore *core = mCoreFind(argv[1]);
    if (!core || !core->init(core)) { fprintf(stderr, "mGBA no reconoce %s\n", argv[1]); return 2; }
    mCoreInitConfig(core, NULL);
    unsigned w, h;
    core->desiredVideoDimensions(core, &w, &h);
    color_t *buf = calloc((size_t)w * h, sizeof *buf);
    core->setVideoBuffer(core, buf, w);
    if (!mCoreLoadFile(core, argv[1])) { fprintf(stderr, "no se pudo cargar %s\n", argv[1]); return 2; }
    core->reset(core);
    FILE *out = fopen(argv[2], "wb");
    if (!out) return 2;
    int frame = 0;
    for (int a = 3; a < argc; a++) {
        int want = atoi(argv[a]);
        while (frame < want) { core->runFrame(core); frame++; }
        for (unsigned i = 0; i < w * h; i++) {
            uint32_t v = (uint32_t)buf[i], r = v & 31u, g = (v >> 5) & 31u, b = (v >> 10) & 31u;
            r = (r << 3) | (r >> 2); g = (g << 3) | (g >> 2); b = (b << 3) | (b >> 2);
            uint32_t c = r | (g << 8) | (b << 16) | 0xFF000000u;
            fwrite(&c, 4, 1, out);
        }
    }
    fclose(out);
    core->deinit(core);
    free(buf);
    return 0;
}
