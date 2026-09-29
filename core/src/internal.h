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
    uint8_t vram[0x4000];  /* CGB: 2 bancos de 8 KiB (VBK); DMG usa solo el 0 */
    uint8_t wram[0x8000];  /* CGB: 8 bancos de 4 KiB (SVBK); DMG usa solo el 0 y el 1 */
    uint8_t oam[0xA0];
    uint8_t hram[0x7F];
    uint8_t ie;
    uint8_t if_;
    uint8_t vbk;           /* banco de VRAM visible para la CPU (0/1) */
    uint8_t svbk;          /* SVBK tal como se escribió (bits 2–0) */
    uint8_t wram_bank;     /* banco en D000–DFFF (1–7), derivado de svbk */
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

/* APU (docs/03-core-spec.md §APU). */
enum { APU_BUF_FRAMES = 8192 };        /* anillo de salida: frames estéreo int16 */

struct gb_apu_env {
    uint8_t vol, init, period, timer;
    bool up;
};

struct gb_apu_ch {
    bool enabled, dac, len_en;
    uint16_t length;       /* 0..64 (0..256 en el canal 3) */
    uint16_t freq;         /* 11 bits (canales 1–3) */
    int32_t timer;         /* T-ciclos hasta el siguiente paso del generador */
    uint8_t duty, pos;     /* cuadrado: duty y paso 0..7 · onda: muestra 0..31 */
    struct gb_apu_env env; /* canales 1, 2 y 4 */
};

struct gb_apu {
    bool power;
    uint32_t pending;      /* T-ciclos aún no procesados (catch-up, ver apu_sync) */
    uint8_t regs[0x30];    /* FF10–FF3F tal como se escribieron (FF30–FF3F = wave RAM) */
    uint8_t fs_step;       /* próximo paso del frame sequencer (0..7) */
    struct gb_apu_ch ch[4];
    /* canal 1: sweep */
    uint8_t sweep_timer;
    uint16_t sweep_shadow;
    bool sweep_enabled, sweep_neg_used;
    /* canal 3: última muestra leída (4 bits) */
    uint8_t wave_sample;
    /* canal 4: LFSR de 15 bits */
    uint16_t lfsr;
    /* Salida (no se guarda en los save states): caja integradora + pasa-altos */
    int32_t mix_l, mix_r;  /* mezcla actual; se recalcula solo si mix_dirty */
    bool mix_dirty;
    int32_t acc_l, acc_r;
    uint32_t acc_n, phase;
    double cap_l, cap_r, charge;
    int16_t buf[APU_BUF_FRAMES * 2];
    uint32_t head, count;  /* anillo: primer frame y frames disponibles */
    uint32_t dropped;      /* frames descartados por anillo lleno */
};

/* Game Boy Color (docs/03-core-spec.md §PPU CGB, §Arranque). `on` = hardware CGB;
 * `compat` = ROM DMG en CGB (KEY0=4): los registros exclusivos de CGB no se ven
 * y BGP/OBP0/OBP1 indexan las paletas de color que dejó el arranque. */
enum { CGB_PAL_BYTES = 64 };

struct gb_cgb {
    bool on, compat;
    bool double_speed;     /* KEY1 bit 7 */
    bool speed_prepare;    /* KEY1 bit 0 */
    uint8_t bcps, ocps;    /* índice (bits 5–0) + autoincremento (bit 7) */
    uint8_t bg_pal[CGB_PAL_BYTES], obj_pal[CGB_PAL_BYTES];   /* RGB555 little-endian */
    uint8_t opri;          /* bit 0: 1 = prioridad de objetos DMG (por X) */
    uint16_t hdma_src, hdma_dst;   /* dst: offset en VRAM (0000–1FF0) */
    uint8_t hdma_len;      /* bloques de 16 bytes restantes − 1 (bits 6–0) */
    bool hdma_active;      /* HDMA de HBlank en curso */
    bool hdma_req;         /* la PPU entró en HBlank: copiar un bloque */
    uint16_t stall;        /* M-ciclos en que la CPU queda parada por HDMA */
    uint8_t rp;            /* FF56 (infrarrojo, sin emisor ni receptor) */
    uint8_t ff72, ff73, ff74, ff75;
    /* Caché RGBA de las paletas (no se guarda: se deriva de bg_pal/obj_pal) */
    uint32_t bg_rgba[32], obj_rgba[32];
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

enum gb_mbc { MBC_NONE = 0, MBC_MBC1, MBC_MBC3, MBC_MBC5 };

/* RTC del MBC3 (docs/03-core-spec.md §MBC). Registros: 0 S, 1 M, 2 H, 3 DL, 4 DH. */
enum { RTC_S = 0, RTC_M, RTC_H, RTC_DL, RTC_DH, RTC_REGS };
#define RTC_CYCLES_PER_SECOND GB_CLOCK_HZ
enum { RTC_SAVE_BYTES = 48 };

struct gb_rtc {
    uint8_t reg[RTC_REGS];     /* contadores vivos */
    uint8_t latched[RTC_REGS]; /* copia visible tras el latch 0→1 */
    uint32_t sub;              /* T-ciclos dentro del segundo actual del RTC */
    uint32_t wall_sub;         /* T-ciclos dentro del segundo de "reloj de pared" */
    uint8_t latch_last;        /* último valor escrito en 6000–7FFF */
    int64_t unix;              /* hora Unix a la que corresponden los registros vivos */
};

struct gb_cart {
    uint8_t *rom;
    uint32_t rom_size;     /* bytes según la cabecera (≤ tamaño del archivo) */
    uint32_t rom_banks;    /* bancos de 16 KiB, potencia de 2, ≥ 2 */
    uint8_t *ram;
    uint32_t ram_size;
    uint32_t ram_banks;    /* bancos de 8 KiB (0 si no hay RAM) */
    enum gb_mbc mbc;
    bool has_ram, has_battery, has_rtc, has_rumble;
    /* Registros del MBC tal como los escribió el juego */
    bool ram_enabled;
    uint8_t bank_lo;       /* MBC1: 5 bits · MBC3: 7 bits · MBC5: 8 bits bajos */
    uint8_t bank_hi;       /* MBC1: 2 bits · MBC5: bit 8 del banco ROM */
    uint8_t ram_sel;       /* MBC3: banco RAM (0–7) o registro RTC (08–0C) · MBC5: banco RAM */
    uint8_t mode;          /* MBC1: 0 o 1 */
    bool rumble_on;        /* MBC5 con motor: bit 3 de 4000–5FFF */
    /* Mapeo derivado (cart_update_banks): offsets ya acotados */
    uint32_t rom0_off, romx_off, ram_off;
    int8_t rtc_reg;        /* registro RTC mapeado en A000–BFFF, o -1 */
    bool ram_mapped;       /* A000–BFFF apunta a RAM */
    bool ram_written;      /* escrituras en RAM externa desde el último disable */
    bool sram_dirty;       /* "el juego acaba de guardar" (flanco de disable) */
    struct gb_rtc rtc;
};

/* Ganchos para el runner de pruebas (no forman parte de la API pública). */
struct gb_debug {
    bool ld_b_b;           /* se ejecutó LD B,B (fin de las pruebas Mooneye) */
    uint8_t regs[6];       /* B C D E H L en ese momento */
#ifdef GB_TEST_HOOKS
    int fail_alloc_at;     /* >0: la reserva número n de gb_load_rom falla (1 = la primera) */
    int alloc_count;
#endif
};

struct gb {
    struct gb_cpu cpu;
    struct gb_mem mem;
    struct gb_timer timer;
    struct gb_ppu ppu;
    struct gb_dma dma;
    struct gb_apu apu;
    struct gb_serial serial;
    struct gb_joypad joy;
    struct gb_cart cart;
    struct gb_cgb cgb;
    struct gb_debug dbg;
    gb_options opts;
    gb_rom_info info;
    bool rom_loaded;
    uint64_t cycles;       /* T-ciclos desde gb_load_rom */
    uint32_t framebuffer[GB_SCREEN_W * GB_SCREEN_H];
};

static inline bool cgb_native(const gb *g)
{
    return g->cgb.on && !g->cgb.compat;
}

/* gb.c */
void gb_tick(gb *g, unsigned tcycles);

/* cgb.c */
void cgb_reset(gb *g);                  /* registros y paletas post-arranque */
uint8_t cgb_title_checksum(const gb *g, bool *nintendo);
uint8_t cgb_io_read(gb *g, uint16_t addr);    /* FF4C–FF7F */
void cgb_io_write(gb *g, uint16_t addr, uint8_t v);
void cgb_update_rgba(gb *g);            /* recalcula la caché RGBA de las paletas */
void cgb_hdma_hblank(gb *g);            /* copia un bloque del HDMA de HBlank */
void cgb_speed_switch(gb *g);           /* STOP con KEY1 bit 0 */

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
void ppu_resync(gb *g);                 /* recalcula modo y próximo evento (save states) */
/* Con el LCD encendido la CPU no ve VRAM en modo 3 ni OAM en modos 2 y 3. */
static inline bool ppu_vram_blocked(const gb *g)
{
    return (g->ppu.lcdc & 0x80) && g->ppu.mode == 3;
}
static inline bool ppu_oam_blocked(const gb *g)
{
    return (g->ppu.lcdc & 0x80) && (g->ppu.mode == 2 || g->ppu.mode == 3);
}

/* apu.c */
void apu_reset(gb *g);
void apu_sync(gb *g);                   /* procesa los T-ciclos pendientes */
void apu_frame_step(gb *g);             /* flanco de bajada del bit 12 del contador DIV */
uint8_t apu_read(gb *g, uint16_t addr);
void apu_write(gb *g, uint16_t addr, uint8_t v);

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
void cart_update_banks(gb *g);          /* recalcula offsets tras escribir registros */
void *cart_alloc(gb *g, size_t size);   /* malloc con el gancho de fallo de los tests */

/* rtc.c */
void rtc_reset(gb *g, int64_t unix_time);
void rtc_tick(gb *g, unsigned tcycles);
uint8_t rtc_read(const gb *g);
void rtc_write(gb *g, uint8_t v);
void rtc_latch_write(gb *g, uint8_t v);
void rtc_add_seconds(struct gb_rtc *r, uint64_t seconds);
/* Hora Unix aceptada: [0, 2^40) (hasta el año ~36812). Fuera de rango = "sin hora". */
static inline bool rtc_unix_valid(int64_t t)
{
    return t >= 0 && t < ((int64_t)1 << 40);
}
void rtc_serialize(const struct gb_rtc *r, uint8_t out[RTC_SAVE_BYTES]);
void rtc_deserialize(struct gb_rtc *r, const uint8_t in[RTC_SAVE_BYTES]);

/* state.c */
uint32_t crc32_update(uint32_t crc, const uint8_t *data, size_t len);

/* sha256.c */
void sha256(const uint8_t *data, size_t len, uint8_t out[32]);

#endif /* POCKETGB_INTERNAL_H */
