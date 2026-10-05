/*
 * shot.c — oráculo de desarrollo para la PPU del GBA (G3): ejecuta un ROM en
 * libmgba (MPL-2.0, se compila aparte en tools/oracle/mgba y NUNCA se enlaza
 * en la app) y vuelca el framebuffer de cada frame pedido en RGBA crudo
 * (240x160x4, R en el byte bajo, A = 0xFF), el mismo formato que
 * `gbatest --dump`. Código propio; solo usa la API pública de mGBA.
 * mGBA se compila con COLOR_16_BIT (BGR555): así mezcla y aclara con 5 bits
 * por canal, como el hardware, y la expansión a 8 bits es la nuestra.
 *   shot ROM SALIDA.rgba FRAME [FRAME...]
 */
#ifndef COLOR_16_BIT
#error "compilar con -DCOLOR_16_BIT (igual que libmgba)"
#endif
#include <mgba/core/core.h>
#include <mgba/core/config.h>
#include <stdio.h>
#include <stdlib.h>

int main(int argc, char **argv)
{
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
