/*
 * rtc.c — ROM homebrew propia (MIT): lee el RTC S-3511A por GPIO como hacen
 * los juegos (GBATEK §GBA Cart Real-Time Clock): reinicio, modo 24 h, fecha y
 * hora; espera ~2 s y comprueba que avanza. El runner arranca con la hora
 * local 2026-10-05 12:34:56 (lunes). Resultado en 0x03007E00.
 */
#include "gba.h"

#define GPIO_DATA REG16(0x080000C4)
#define GPIO_DIR  REG16(0x080000C6)
#define GPIO_CTRL REG16(0x080000C8)
#define RESULT REG32(0x03007E00)

static void send_byte(unsigned v, int msb_first)
{
    for (int i = 0; i < 8; i++) {
        unsigned b = msb_first ? (v >> (7 - i)) & 1u : (v >> i) & 1u;
        GPIO_DATA = (uint16_t)(4 | (b << 1));        /* CS, SCK bajo */
        GPIO_DATA = (uint16_t)(4 | (b << 1) | 1);    /* SCK sube */
    }
}

static unsigned recv_byte(void)
{
    unsigned v = 0;
    for (int i = 0; i < 8; i++) {
        GPIO_DATA = 4;
        GPIO_DATA = 5;
        v |= ((GPIO_DATA >> 1) & 1u) << i;
    }
    return v;
}

static void cmd(unsigned c, unsigned *out, int n)
{
    GPIO_DIR = 7;
    GPIO_DATA = 1;            /* CS bajo, SCK alto */
    GPIO_DATA = 5;            /* CS sube */
    send_byte(c, 1);
    if (n < 0) {              /* escritura de un byte de datos (LSB primero) */
        send_byte(out[0], 0);
        GPIO_DATA = 1;
        return;
    }
    if (out) {
        GPIO_DIR = 5;         /* SIO pasa a entrada */
        for (int i = 0; i < n; i++) out[i] = recv_byte();
    }
    GPIO_DATA = 1;            /* CS baja */
}

int main(void)
{
    unsigned st[1], dt[7], dt2[7];
    RESULT = 0;
    GPIO_CTRL = 1;
    cmd(0x60, 0, 0);                      /* reinicio: queda en 12 horas */
    st[0] = 0x40;
    cmd(0x62, st, -1);                    /* estado: 24 horas */
    cmd(0x63, st, 1);                     /* leer estado */
    if (st[0] & 0x80 || !(st[0] & 0x40)) { RESULT = 1; for (;;) {} }
    cmd(0x65, dt, 7);                     /* fecha y hora */
    if (dt[0] != 0x26 || dt[1] != 0x10 || dt[2] != 0x05 || dt[3] != 1) { RESULT = 2; for (;;) {} }
    if ((dt[4] & 0x3F) != 0x12 || dt[5] != 0x34 || dt[6] > 0x59) { RESULT = 3; for (;;) {} }
    for (int f = 0; f < 130; f++) vsync();
    cmd(0x65, dt2, 7);
    unsigned s1 = (dt[6] >> 4) * 10 + (dt[6] & 15), s2 = (dt2[6] >> 4) * 10 + (dt2[6] & 15);
    if (s2 < s1 + 2) { RESULT = 4; for (;;) {} }
    RESULT = 0x600D;
    for (;;) {}
}
