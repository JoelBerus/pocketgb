/*
 * audio.c — ROMs homebrew propias (MIT) para la APU (GBATEK §GBA Sound
 * Controller). -DPSG: canal 2 (pulso, 50 %) con freq 1750 → 131072/298 =
 * 439,8 Hz. Sin -DPSG: DirectSound A con un seno de 32 muestras a 32768 Hz
 * (timer 0, DMA1 en modo FIFO) → 1024 Hz. El runner (modo audio) mide la
 * frecuencia de la salida.
 */
#include "gba.h"

#define SOUNDCNT_L REG16(0x04000080)
#define SOUNDCNT_H REG16(0x04000082)
#define SOUNDCNT_X REG16(0x04000084)
#define SOUND2CNT_L REG16(0x04000068)
#define SOUND2CNT_H REG16(0x0400006C)
#define TM0CNT_L REG16(0x04000100)
#define TM0CNT_H REG16(0x04000102)
#define DMA1SAD REG32(0x040000BC)
#define DMA1DAD REG32(0x040000C0)
#define DMA1CNT_H REG16(0x040000C6)

__attribute__((unused)) static const int8_t sine[32] = {0, 24, 48, 70, 89, 105, 117, 124, 127, 124, 117, 105, 89, 70, 48, 24,
                                0, -24, -48, -70, -89, -105, -117, -124, -127, -124, -117, -105, -89, -70, -48, -24};

#ifndef PSG
static void arm_dma(void)
{
    DMA1CNT_H = 0;
    DMA1SAD = 0x02000000;
    DMA1DAD = 0x040000A0;
    DMA1CNT_H = (uint16_t)(0x8000 | 0x3000 | 0x0200 | 0x0400 | 0x0040);
}
#endif

int main(void)
{
    SOUNDCNT_X = 0x80;
#ifdef PSG
    SOUNDCNT_L = 0x2277;            /* volumen 7/7, canal 2 a izquierda y derecha */
    SOUNDCNT_H = 2;                 /* PSG al 100 % */
    SOUND2CNT_L = 0xF080;           /* duty 50 %, volumen 15 fijo */
    SOUND2CNT_H = 0x8000 | 1750;    /* disparo */
    for (;;) vsync();
#else
    volatile int8_t *buf = (volatile int8_t *)0x02000000;
    for (int i = 0; i < 0x8000; i++) buf[i] = sine[i & 31];
    SOUNDCNT_H = 0x0B06;            /* DMA A al 100 %, izquierda y derecha, timer 0, vaciar FIFO */
    arm_dma();
    TM0CNT_L = (uint16_t)(65536 - 512);   /* 16 777 216 / 512 = 32768 Hz */
    TM0CNT_H = 0x80;
    for (;;) {
        for (int f = 0; f < 30; f++) vsync();   /* 0,5 s < 1 s del búfer */
        arm_dma();
    }
#endif
}
