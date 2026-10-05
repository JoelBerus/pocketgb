/*
 * eeprom.c — ROM homebrew propia (MIT): escribe y lee la EEPROM por DMA3 como
 * la biblioteca de Nintendo (GBATEK §EEPROM). Con -DEE8K usa direcciones de
 * 14 bits (8 KiB); sin él, de 6 bits (512 B). Resultado en 0x03007E00:
 * 0x600D = bien; otro valor = número de la comprobación que falló.
 */
#include "gba.h"

__attribute__((aligned(4))) const char save_id[] = "EEPROM_V124";
#ifdef EE8K
#define ABITS 14
#else
#define ABITS 6
#endif
#define EEPROM ((volatile uint16_t *)0x0D000000)
#define RESULT REG32(0x03007E00)

static uint16_t bits[96];

static void dma3(const volatile void *src, volatile void *dst, unsigned count)
{
    __asm__ volatile("" ::: "memory");      /* que el búfer esté escrito antes de la DMA */
    DMA3SAD = (uint32_t)src;
    DMA3DAD = (uint32_t)dst;
    DMA3CNT = count | (0x8000u << 16);       /* 16 bits, inmediata */
}

static void put(int *n, unsigned v, int w)
{
    for (int i = w - 1; i >= 0; i--) bits[(*n)++] = (uint16_t)((v >> i) & 1u);
}

static void ee_write(unsigned block, const uint8_t *data)
{
    int n = 0;
    put(&n, 2, 2);
    put(&n, block, ABITS);
    for (int b = 0; b < 8; b++) put(&n, data[b], 8);
    put(&n, 0, 1);
    dma3(bits, EEPROM, (unsigned)n);
    for (int t = 0; t < 100000 && !(EEPROM[0] & 1); t++) {}   /* listo */
}

static void ee_read(unsigned block, uint8_t *out)
{
    int n = 0;
    put(&n, 3, 2);
    put(&n, block, ABITS);
    put(&n, 0, 1);
    dma3(bits, EEPROM, (unsigned)n);
    dma3(EEPROM, bits, 68);
    __asm__ volatile("" ::: "memory");
    for (int b = 0; b < 8; b++) {
        unsigned v = 0;
        for (int i = 0; i < 8; i++) v = (v << 1) | (bits[4 + b * 8 + i] & 1u);
        out[b] = (uint8_t)v;
    }
}

int main(void)
{
    static const uint8_t a[8] = {0x12, 0x34, 0x56, 0x78, 0x9A, 0xBC, 0xDE, 0xF0};
    static const uint8_t b[8] = {1, 2, 3, 4, 5, 6, 7, 8};
    uint8_t got[8];
    RESULT = 0;
    (void)save_id[0];
    ee_write(5, a);
    ee_write(6, b);
    ee_read(5, got);
    for (int i = 0; i < 8; i++) if (got[i] != a[i]) { RESULT = 1; for (;;) {} }
    ee_read(6, got);
    for (int i = 0; i < 8; i++) if (got[i] != b[i]) { RESULT = 2; for (;;) {} }
    ee_read(7, got);                          /* sin escribir: 0xFF */
    for (int i = 0; i < 8; i++) if (got[i] != 0xFF) { RESULT = 3; for (;;) {} }
#ifdef EE8K
    /* Bloque 1000: solo existe con 8 KiB (con 512 B se confundiría con otro). */
    ee_write(1000, b);
    ee_read(1000, got);
    for (int i = 0; i < 8; i++) if (got[i] != b[i]) { RESULT = 4; for (;;) {} }
    ee_read(1000 & 63, got);
    for (int i = 0; i < 8; i++) if (got[i] != 0xFF) { RESULT = 5; for (;;) {} }
#endif
    RESULT = 0x600D;
    for (;;) {}
}
