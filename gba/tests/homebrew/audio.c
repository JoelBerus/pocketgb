/*
 * audio.c — ROMs homebrew propias (MIT) para la APU (GBATEK §GBA Sound
 * Controller). -DPSG: canal 2 (pulso, 50 %) con freq 1750 → 131072/298 =
 * 439,8 Hz. Sin -DPSG: DirectSound A con un seno de 32 muestras a 32768 Hz
 * (timer 0, DMA1 en modo FIFO) → 1024 Hz. -DWAVE: canal 3 con 64 muestras
 * (los dos bancos), volumen 75 % forzado, freq 1792 → 2097152/(64·256) =
 * 128 Hz, solo a la izquierda. -DNOISE: ruido, solo a la derecha. -DDSB:
 * DirectSound B al 50 % con timer 1 a 16384 Hz → 512 Hz, solo a la derecha.
 * El runner (modo audio) mide la frecuencia y comprueba el lado silencioso.
 */
#include "gba.h"

#define SOUNDCNT_L REG16(0x04000080)
#define SOUNDCNT_H REG16(0x04000082)
#define SOUNDCNT_X REG16(0x04000084)
#define SOUND2CNT_L REG16(0x04000068)
#define SOUND2CNT_H REG16(0x0400006C)
#define TM0CNT_L REG16(0x04000100)
#define TM0CNT_H REG16(0x04000102)
#define SOUND3CNT_L REG16(0x04000070)
#define SOUND3CNT_H REG16(0x04000072)
#define SOUND3CNT_X REG16(0x04000074)
#define SOUND4CNT_L REG16(0x04000078)
#define SOUND4CNT_H REG16(0x0400007C)
#define WAVE_RAM ((volatile uint16_t *)0x04000090)
#define TM1CNT_L REG16(0x04000104)
#define TM1CNT_H REG16(0x04000106)
#define DMA2SAD REG32(0x040000C8)
#define DMA2DAD REG32(0x040000CC)
#define DMA2CNT_H REG16(0x040000D2)
#define DMA1SAD REG32(0x040000BC)
#define DMA1DAD REG32(0x040000C0)
#define DMA1CNT_H REG16(0x040000C6)

__attribute__((unused)) static const int8_t sine[32] = {0, 24, 48, 70, 89, 105, 117, 124, 127, 124, 117, 105, 89, 70, 48, 24,
                                0, -24, -48, -70, -89, -105, -117, -124, -127, -124, -117, -105, -89, -70, -48, -24};

#if !defined(PSG) && !defined(WAVE) && !defined(NOISE)
static void arm_dma(void)
{
#ifdef DSB
    DMA2CNT_H = 0;
    DMA2SAD = 0x02000000;
    DMA2DAD = 0x040000A4;
    DMA2CNT_H = (uint16_t)(0x8000 | 0x3000 | 0x0200 | 0x0400 | 0x0040);
#else
    DMA1CNT_H = 0;
    DMA1SAD = 0x02000000;
    DMA1DAD = 0x040000A0;
    DMA1CNT_H = (uint16_t)(0x8000 | 0x3000 | 0x0200 | 0x0400 | 0x0040);
#endif
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
#elif defined(WAVE)
    SOUNDCNT_L = 0x4077;            /* canal 3 solo a la izquierda */
    SOUNDCNT_H = 2;
    /* Banco 0 todo alto y banco 1 todo bajo: solo con 64 muestras hay onda. */
    SOUND3CNT_L = 0x0060;           /* banco 1 suena: se escribe el 0 */
    for (int i = 0; i < 8; i++) WAVE_RAM[i] = 0xFFFF;
    SOUND3CNT_L = 0x0020;           /* ahora suena el 0: se escribe el 1 */
    for (int i = 0; i < 8; i++) WAVE_RAM[i] = 0x0000;
    SOUND3CNT_L = 0x00A0;           /* encendido, 64 muestras */
    SOUND3CNT_H = 0x8000;           /* volumen 75 % forzado */
    SOUND3CNT_X = 0x8000 | 1792;
    for (;;) vsync();
#elif defined(NOISE)
    SOUNDCNT_L = 0x0877;            /* ruido solo a la derecha */
    SOUNDCNT_H = 2;
    SOUND4CNT_L = 0xF000;
    SOUND4CNT_H = 0x8000 | 0x0021;
    for (;;) vsync();
#else
    volatile int8_t *buf = (volatile int8_t *)0x02000000;
    for (int i = 0; i < 0x8000; i++) buf[i] = sine[i & 31];
#ifdef DSB
    SOUNDCNT_H = (uint16_t)(0x0100 << 4 | 0x4000 | 0x8000 | 2);   /* B al 50 %, solo derecha, timer 1, vaciar */
    arm_dma();
    TM1CNT_L = (uint16_t)(65536 - 1024);  /* 16384 Hz */
    TM1CNT_H = 0x80;
#else
    SOUNDCNT_H = 0x0B06;            /* DMA A al 100 %, izquierda y derecha, timer 0, vaciar FIFO */
    arm_dma();
    TM0CNT_L = (uint16_t)(65536 - 512);   /* 16 777 216 / 512 = 32768 Hz */
    TM0CNT_H = 0x80;
#endif
    for (;;) {
        for (int f = 0; f < 30; f++) vsync();   /* 0,5 s < 1 s del búfer */
        arm_dma();
    }
#endif
}
