/*
 * internal.h — estado interno de una instancia PocketGB.
 *
 * Todo el estado vive en `struct gb` (sin globals: debe poder haber 2 instancias
 * para el cable link). Spec: docs/03-core-spec.md.
 */
#ifndef POCKETGB_INTERNAL_H
#define POCKETGB_INTERNAL_H

#include "pocketgb.h"

/* Bits de IE/IF */
enum {
    IRQ_VBLANK = 1u << 0,
    IRQ_STAT   = 1u << 1,
    IRQ_TIMER  = 1u << 2,
    IRQ_SERIAL = 1u << 3,
    IRQ_JOYPAD = 1u << 4
};

/* Flags de F */
enum { FLAG_Z = 0x80, FLAG_N = 0x40, FLAG_H = 0x20, FLAG_C = 0x10 };

struct gb_cpu {
    uint8_t a, f, b, c, d, e, h, l;
    uint16_t sp, pc;
    bool ime;
    bool ei_pending;   /* EI: IME se activa tras la instrucción siguiente */
    bool halted;
    bool halt_bug;     /* el siguiente fetch no incrementa PC */
    bool stopped;      /* STOP en DMG: espera a que se pulse un botón */
    bool locked;       /* opcode ilegal ejecutado */
};

struct gb_mem {
    uint8_t vram[0x2000];
    uint8_t wram[0x2000];
    uint8_t oam[0xA0];
    uint8_t hram[0x7F];
    uint8_t apu_regs[0x30]; /* FF10–FF3F sin emular hasta M5: solo se almacenan */
    uint8_t ie;
    uint8_t if_;
};

/* Recarga retrasada de TIMA (docs/03-core-spec.md §Timer) */
enum { TIMA_RELOAD_NONE = 0, TIMA_RELOAD_A, TIMA_RELOAD_B };

struct gb_timer {
    uint16_t counter;  /* contador interno de 16 bits; DIV = byte alto */
    uint8_t tima, tma, tac;
    uint8_t reload;    /* TIMA_RELOAD_* */
};

/* PPU DMG por scanline (docs/03-core-spec.md §PPU). */
enum { PPU_MAX_OBJS = 10 };

struct gb_ppu {
    uint16_t dot;        /* 0..455 dentro de la línea */
    uint16_t mode3_end;  /* dot en que termina el modo 3 de la línea actual */
    uint16_t next_event; /* próximo dot con cambio de estado (camino rápido de ppu_tick) */
    uint8_t ly;          /* 0..153 */
    uint8_t mode;        /* 0..3 */
    uint8_t lcdc, stat, scy, scx, lyc, dma, bgp, obp0, obp1, wy, wx;
    uint8_t window_line; /* contador interno: solo avanza si la ventana se dibujó */
    bool wy_triggered;   /* LY == WY se cumplió en este frame */
    uint8_t obj_count;   /* objetos de la línea (búsqueda OAM, máx. 10) */
    uint8_t obj_height;  /* altura de objeto (8/16) usada en la búsqueda */
    uint8_t objs[PPU_MAX_OBJS];
    bool stat_line;      /* OR de las fuentes STAT, para detectar el flanco de subida */
    bool frame_done;     /* se activa al entrar en VBlank */
};

struct gb_dma {
    bool active;          /* copia en curso */
    bool bus_busy;        /* en este M-ciclo se copió un byte: la CPU solo ve HRAM */
    uint8_t start_delay;  /* M-ciclos hasta que arranca la copia pedida */
    uint8_t index;        /* 0..159 */
    uint16_t src, next_src;
};

struct gb_serial {
    uint8_t sb, sc;
    uint8_t bits;      /* bits desplazados en la transferencia actual */
    uint8_t out;       /* bits salientes acumulados (para serial_byte_cb) */
};

struct gb_joypad {
    uint8_t select;    /* bits 5–4 de P1 tal como los escribió el juego */
    uint8_t buttons;   /* máscara GB_BTN_*, 1 = pulsado */
};

enum gb_mbc { MBC_NONE = 0, MBC_MBC1 };

struct gb_cart {
    uint8_t *rom;
    uint32_t rom_size;     /* bytes según la cabecera (≤ tamaño del archivo) */
    uint32_t rom_banks;    /* bancos de 16 KiB, potencia de 2, ≥ 2 */
    uint8_t *ram;
    uint32_t ram_size;
    uint32_t ram_banks;    /* bancos de 8 KiB (0 si no hay RAM) */
    enum gb_mbc mbc;
    bool has_ram, has_battery;
    bool ram_enabled;
    uint8_t bank_lo;       /* MBC1: 5 bits (0 → 1 al mapear) */
    uint8_t bank_hi;       /* MBC1: 2 bits */
    uint8_t mode;          /* MBC1: 0 o 1 */
    bool ram_written;      /* escrituras en RAM externa desde el último disable */
    bool sram_dirty;       /* "el juego acaba de guardar" (flanco de disable) */
};

/* Ganchos para el runner de pruebas (no forman parte de la API pública). */
struct gb_debug {
    bool ld_b_b;           /* se ejecutó LD B,B (fin de las pruebas Mooneye) */
    uint8_t regs[6];       /* B C D E H L en ese momento */
};

struct gb {
    struct gb_cpu cpu;
    struct gb_mem mem;
    struct gb_timer timer;
    struct gb_ppu ppu;
    struct gb_dma dma;
    struct gb_serial serial;
    struct gb_joypad joy;
    struct gb_cart cart;
    struct gb_debug dbg;
    gb_options opts;
    gb_rom_info info;
    bool rom_loaded;
    uint64_t cycles;       /* T-ciclos desde gb_load_rom */
    uint32_t framebuffer[GB_SCREEN_W * GB_SCREEN_H];
};

/* gb.c */
void gb_tick(gb *g, unsigned tcycles);

/* cpu.c */
void cpu_reset(gb *g);
void cpu_step(gb *g);

/* mmu.c: acceso al bus sin consumir ciclos (la CPU añade el tick) */
uint8_t mmu_read(gb *g, uint16_t addr);
void mmu_write(gb *g, uint16_t addr, uint8_t v);
void mmu_reset(gb *g);

/* timer.c */
void timer_reset(gb *g);
void timer_tick(gb *g);                 /* un M-ciclo (4 T-ciclos) */
uint8_t timer_read(const gb *g, uint16_t addr);
void timer_write(gb *g, uint16_t addr, uint8_t v);

/* ppu.c */
void ppu_reset(gb *g);
void ppu_tick(gb *g, unsigned dots);
uint8_t ppu_read(const gb *g, uint16_t addr);
void ppu_write(gb *g, uint16_t addr, uint8_t v);
/* Con el LCD encendido la CPU no ve VRAM en modo 3 ni OAM en modos 2 y 3. */
static inline bool ppu_vram_blocked(const gb *g)
{
    return (g->ppu.lcdc & 0x80) && g->ppu.mode == 3;
}
static inline bool ppu_oam_blocked(const gb *g)
{
    return (g->ppu.lcdc & 0x80) && (g->ppu.mode == 2 || g->ppu.mode == 3);
}

/* dma.c */
void dma_reset(gb *g);
void dma_start(gb *g, uint8_t page);
void dma_tick(gb *g);                   /* un M-ciclo */

/* serial.c */
void serial_reset(gb *g);
void serial_clock_internal(gb *g);      /* flanco del reloj interno de 8192 Hz */
uint8_t serial_read(const gb *g, uint16_t addr);
void serial_write(gb *g, uint16_t addr, uint8_t v);

/* joypad.c */
void joypad_reset(gb *g);
uint8_t joypad_read(const gb *g);
void joypad_write(gb *g, uint8_t v);
void joypad_set(gb *g, uint8_t mask);

/* cart.c */
gb_result cart_load(gb *g, const uint8_t *data, size_t len);
void cart_free(gb *g);
void cart_reset(gb *g);
uint8_t cart_rom_read(const gb *g, uint16_t addr);
void cart_rom_write(gb *g, uint16_t addr, uint8_t v);
uint8_t cart_ram_read(const gb *g, uint16_t addr);
void cart_ram_write(gb *g, uint16_t addr, uint8_t v);

/* sha256.c */
void sha256(const uint8_t *data, size_t len, uint8_t out[32]);

#endif /* POCKETGB_INTERNAL_H */
