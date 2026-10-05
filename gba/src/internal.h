/*
 * internal.h — estado interno del núcleo GBA (no es API pública).
 * Referencia de hardware: GBATEK (Martin Korth). Código propio; ver
 * docs/10-gba-spec.md para lo que se emula y lo que no.
 */
#ifndef POCKETGBA_INTERNAL_H
#define POCKETGBA_INTERNAL_H

#include "pocketgba.h"
#include <string.h>

/* ---- Modos y bits del CPSR ---- */
enum {
    ARM_MODE_USR = 0x10, ARM_MODE_FIQ = 0x11, ARM_MODE_IRQ = 0x12,
    ARM_MODE_SVC = 0x13, ARM_MODE_ABT = 0x17, ARM_MODE_UND = 0x1B,
    ARM_MODE_SYS = 0x1F
};
#define ARM_N (1u << 31)
#define ARM_Z (1u << 30)
#define ARM_C (1u << 29)
#define ARM_V (1u << 28)
#define ARM_I (1u << 7)
#define ARM_F (1u << 6)
#define ARM_T (1u << 5)

/* Bancos: 0 usuario/sistema, 1 FIQ, 2 IRQ, 3 SVC, 4 ABT, 5 UND. */
enum { ARM_BANK_USR, ARM_BANK_FIQ, ARM_BANK_IRQ, ARM_BANK_SVC, ARM_BANK_ABT, ARM_BANK_UND, ARM_BANKS };

/* Clases de instrucción (tablas por instancia: sin estado global). */
enum {
    ARM_OP_UNDEF = 0, ARM_OP_DP, ARM_OP_MRS, ARM_OP_MSR, ARM_OP_BX, ARM_OP_MUL,
    ARM_OP_MULL, ARM_OP_SWP, ARM_OP_HALF, ARM_OP_SDT, ARM_OP_LDM, ARM_OP_B,
    ARM_OP_SWI, ARM_OP_COUNT
};
enum {
    THUMB_OP_UNDEF = 0, THUMB_OP_SHIFT, THUMB_OP_ADDSUB, THUMB_OP_IMM, THUMB_OP_ALU,
    THUMB_OP_HIREG, THUMB_OP_LDRPC, THUMB_OP_LDSTREG, THUMB_OP_LDSTSX, THUMB_OP_LDSTIMM,
    THUMB_OP_LDSTH, THUMB_OP_LDSTSP, THUMB_OP_ADDR, THUMB_OP_ADDSP, THUMB_OP_PUSHPOP,
    THUMB_OP_LDMSTM, THUMB_OP_BCC, THUMB_OP_SWI, THUMB_OP_B, THUMB_OP_BL1, THUMB_OP_BL2,
    THUMB_OP_COUNT
};

typedef struct gba_arm {
    uint32_t r[16];
    uint32_t cpsr;
    uint32_t spsr;                 /* SPSR del modo actual (copia de trabajo) */
    uint32_t bank_fiq_r8[5];       /* r8..r12 de FIQ */
    uint32_t bank_usr_r8[5];       /* r8..r12 del resto de modos */
    uint32_t bank_r13[ARM_BANKS];
    uint32_t bank_r14[ARM_BANKS];
    uint32_t bank_spsr[ARM_BANKS];
    uint32_t pipe[2];              /* [0] se ejecuta ahora, [1] ya leída */
    uint8_t bank;                  /* banco cargado en r8..r14/spsr */
    bool flushed;                  /* la instrucción actual cambió el PC */
    bool halted;
    bool seq;                      /* el próximo acceso de datos es secuencial (LDM/STM) */
} gba_arm;

/* Bus de prueba (solo con GBA_TEST_HOOKS): SingleStepTests ARM7TDMI. */
typedef struct gba_test_txn {
    uint32_t kind, size, addr, data;
} gba_test_txn;

#define GBA_TEST_MAX_TXN 64
typedef struct gba_test_bus {
    bool active;
    uint32_t base_addr, opcode;
    uint32_t ntxn;
    gba_test_txn txn[GBA_TEST_MAX_TXN];
    uint32_t nwrites;
    gba_test_txn writes[GBA_TEST_MAX_TXN];
    bool missing_read;            /* una lectura de datos sin transacción */
    uint32_t nreads;              /* lecturas de datos hechas */
} gba_test_bus;

/* DMA (GBATEK §DMA Transfers): registros visibles y contadores internos. */
typedef struct gba_dma {
    uint32_t sad, dad;             /* registros escritos */
    uint16_t cnt_l, cnt_h;
    uint32_t src, dst, count;      /* internos, copiados al activar */
    uint32_t latch;                /* último dato transferido (bus abierto de la DMA) */
} gba_dma;

/* Timers (GBATEK §Timers). */
typedef struct gba_timer {
    uint16_t reload, counter, cnt;
    uint32_t sub;                  /* ciclos acumulados bajo el prescaler */
} gba_timer;

/* Cartucho: medio de guardado y RTC por GPIO (cart.c, GBATEK §GBA Cart). */
typedef struct gba_flash {
    uint8_t state;                 /* 0 listo, 1 tras AA, 2 tras 55, 3 escribir byte, 4 elegir banco */
    bool erase_armed;              /* tras 0x80: el siguiente AA-55 completa el borrado */
    bool id_mode;
    uint8_t bank;
} gba_flash;

typedef struct gba_eeprom {
    uint8_t addr_bits;             /* 6 (512 B) o 14 (8 KiB); 0 = aún sin detectar */
    uint8_t phase;                 /* 0 comando, 1 dirección, 2 datos, 3 bit final */
    uint8_t cmd;                   /* 2 = escribir (10), 3 = leer (11) */
    uint8_t nbits;
    uint32_t addr;
    uint64_t data;
    uint32_t read_addr;
    int8_t read_pos;               /* -1 = sin lectura; 0..67 bit siguiente */
    uint32_t dma_len;              /* longitud de la última DMA a la EEPROM */
} gba_eeprom;

typedef struct gba_rtc {
    uint8_t data, dir, ctrl;       /* registros GPIO (0x80000C4/C6/C8) */
    uint8_t state;                 /* 0 inactivo, 1 comando, 2 datos */
    uint8_t cmd, bitpos, bytepos, nbytes;
    uint8_t shift;
    uint8_t buf[8];
    uint8_t status;                /* registro de control: bit 6 = 24 h */
    bool reading;
    int64_t offset;                /* segundos que el juego ajustó sobre la hora del anfitrión */
} gba_rtc;

/* PPU: referencias internas de los fondos afines y búferes de una línea. */
typedef struct gba_ppu {
    int32_t ref_x[2], ref_y[2];    /* BG2/BG3, 20.8 con signo (se recargan en VBlank) */
    uint16_t bg[4][GBA_SCREEN_W];  /* color BGR555; bit 15 = transparente */
    uint16_t obj[GBA_SCREEN_W];    /* color del objeto delantero */
    uint8_t obj_prio[GBA_SCREEN_W];/* 4 = sin objeto */
    uint8_t obj_semi[GBA_SCREEN_W];
    uint8_t obj_win[GBA_SCREEN_W];
} gba_ppu;

struct gba {
    gba_arm cpu;
    gba_ppu ppu;
    uint8_t arm_lut[4096];         /* bits 27-20 y 7-4 */
    uint8_t thumb_lut[1024];       /* bits 15-6 */

    /* Memoria (G1: mapa plano; tiempos y E/S completas en G2) */
    uint8_t bios[GBA_BIOS_BYTES];
    uint8_t ewram[256 * 1024];
    uint8_t iwram[32 * 1024];
    uint8_t io[0x400];
    uint8_t pal[1024];
    uint8_t vram[96 * 1024];
    uint8_t oam[1024];
    uint8_t *rom;                  /* malloc en gba_load_rom */
    uint32_t rom_size;             /* tamaño real del archivo */
    uint32_t rom_mask;             /* potencia de 2 - 1 que cubre rom_size */
    uint8_t save[128 * 1024];      /* SRAM, Flash o EEPROM (cart.c) */
    bool bios_loaded;
    uint32_t bios_last;            /* última instrucción leída desde la BIOS */

    uint64_t cycles;               /* ciclos desde gba_load_rom */
    uint32_t line_cycles;          /* posición dentro de la línea */
    uint16_t vcount;
    uint16_t dispstat;             /* bits escribibles de DISPSTAT (3-5, 8-15) */
    uint16_t keys;                 /* máscara GBA_BTN_* pulsados */
    uint16_t keycnt;
    bool frame_done;
    bool hblank;                   /* bandera de HBlank de la línea actual */

    /* Interrupciones */
    uint16_t ie, if_, ime;
    uint8_t postflg;

    /* Bus: waitstates por región (addr >> 24 & 15) y tipo de acceso. */
    uint16_t waitcnt;
    uint8_t ws_n16[16], ws_s16[16], ws_n32[16], ws_s32[16];
    uint32_t last_fetch_addr;      /* para decidir acceso secuencial */
    bool last_was_fetch;
    bool dma_active;               /* el bus lo usa la DMA (bus abierto de la DMA) */
    uint8_t dma_cur;               /* canal que transfiere */
    uint8_t dma_pending;           /* DMA inmediatas por ejecutar (sin recursión) */
    bool hle_waiting;              /* IntrWait/VBlankIntrWait esperando su IRQ */

    gba_dma dma[4];
    gba_timer timer[4];

    gba_options opts;
    gba_save_type save_type;
    uint32_t save_bytes;
    bool save_dirty;
    bool has_rtc;
    gba_flash flash;
    gba_eeprom eeprom;
    gba_rtc rtc;
    int64_t rtc_base;              /* hora local del anfitrión en el ciclo rtc_base_cycles */
    uint64_t rtc_base_cycles;
    uint8_t fingerprint[32];
    uint32_t framebuffer[GBA_SCREEN_W * GBA_SCREEN_H];

#ifdef GBA_TEST_HOOKS
    gba_test_bus test;
#endif
};

/* arm7.c */
void gba_arm_init_tables(gba *g);
void gba_arm_reset(gba *g, bool skip_bios);
void gba_arm_step(gba *g);
void gba_arm_set_cpsr(gba *g, uint32_t value);   /* cambia de banco si cambia el modo */
void gba_arm_flush(gba *g);                      /* rellena el pipeline desde r15 */
void gba_arm_irq(gba *g);
void gba_arm_branch(gba *g, uint32_t addr);      /* salto desde la HLE */

/* arm_mulcarry.c (zlib, zaydlang): bandera C de las multiplicaciones. */
enum { GBA_MUL_SHORT, GBA_MUL_LONG_SIGNED, GBA_MUL_LONG_UNSIGNED };
bool gba_arm_mul_carry(int flavor, uint32_t rm, uint32_t rs, uint64_t acc);

/* io.c: registros de E/S, interrupciones, DMA, timers y tiempos de vídeo */
uint16_t gba_io_read16(gba *g, uint32_t off);
void gba_io_write16(gba *g, uint32_t off, uint16_t v);
void gba_io_write8(gba *g, uint32_t off, uint8_t v);
void gba_io_reset(gba *g);
void gba_tick(gba *g, uint32_t cycles);          /* avanza vídeo y timers */
uint32_t gba_cycles_to_event(const gba *g);      /* para saltar mientras la CPU está parada */
void gba_irq_raise(gba *g, uint16_t bits);
bool gba_irq_pending(const gba *g);              /* IE & IF */
void gba_dma_trigger(gba *g, int timing);        /* 1 VBlank, 2 HBlank, 3 especial */
void gba_dma_service(gba *g);                    /* ejecuta las DMA inmediatas pendientes */
void gba_bus_update_waitstates(gba *g);

enum {
    GBA_IRQ_VBLANK = 1u << 0, GBA_IRQ_HBLANK = 1u << 1, GBA_IRQ_VCOUNT = 1u << 2,
    GBA_IRQ_TIMER0 = 1u << 3, GBA_IRQ_SERIAL = 1u << 7, GBA_IRQ_DMA0 = 1u << 8,
    GBA_IRQ_KEYPAD = 1u << 12, GBA_IRQ_GAMEPAK = 1u << 13
};

/* cart.c */
void gba_cart_init(gba *g);                      /* tras cargar el ROM: medio y RTC */
uint8_t gba_cart_read8(gba *g, uint32_t addr);   /* 0x0E000000-0x0FFFFFFF */
void gba_cart_write8(gba *g, uint32_t addr, uint8_t v);
bool gba_cart_is_eeprom(const gba *g, uint32_t addr);
uint16_t gba_eeprom_read(gba *g);
void gba_eeprom_write(gba *g, uint16_t v);
void gba_eeprom_dma(gba *g, uint32_t count);     /* una DMA va a escribir/leer la EEPROM */
bool gba_gpio_readable(const gba *g, uint32_t addr);
uint16_t gba_gpio_read(gba *g, uint32_t addr);
void gba_gpio_write(gba *g, uint32_t addr, uint16_t v);

/* ppu.c */
void gba_ppu_render_line(gba *g, unsigned line);
void gba_ppu_vblank(gba *g);                     /* recarga las referencias afines */
void gba_ppu_reload_ref(gba *g, unsigned bg);    /* tras escribir BGxX/BGxY */

/* hle.c: BIOS en alto nivel (sin la BIOS de Nintendo) */
void gba_hle_install(gba *g);                    /* manejador de IRQ propio en la zona de la BIOS */
bool gba_hle_swi(gba *g, uint32_t number);       /* false = SWI no emulada (se ignora) */

/* bus.c */
uint32_t gba_bus_read32(gba *g, uint32_t addr);
uint16_t gba_bus_read16(gba *g, uint32_t addr);
uint8_t gba_bus_read8(gba *g, uint32_t addr);
void gba_bus_write32(gba *g, uint32_t addr, uint32_t v);
void gba_bus_write16(gba *g, uint32_t addr, uint16_t v);
void gba_bus_write8(gba *g, uint32_t addr, uint8_t v);
uint32_t gba_bus_fetch32(gba *g, uint32_t addr);
uint16_t gba_bus_fetch16(gba *g, uint32_t addr);
void gba_bus_idle(gba *g, uint32_t n);
uint32_t gba_bus_fetch32_n(gba *g, uint32_t addr);   /* primer acceso tras un salto */
uint16_t gba_bus_fetch16_n(gba *g, uint32_t addr);

/* SHA-256 compartido con el núcleo GB (core/src/sha256.c, propio). */
void sha256(const uint8_t *data, size_t len, uint8_t out[32]);

static inline uint32_t gba_ror32(uint32_t v, unsigned s)
{
    s &= 31u;
    return s ? (v >> s) | (v << (32u - s)) : v;
}

#endif
