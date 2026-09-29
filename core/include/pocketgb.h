/*
 * pocketgb.h — API pública del núcleo PocketGB (contrato estable).
 *
 * Spec: docs/02-arquitectura.md (hilos) y docs/03-core-spec.md (comportamiento).
 * Reglas: C11, sin I/O, sin estado global, sin malloc después de gb_load_rom.
 * Una instancia NO es thread-safe: llamar todo desde un único hilo de emulación.
 */
#ifndef POCKETGB_H
#define POCKETGB_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define GB_SCREEN_W 160
#define GB_SCREEN_H 144
#define GB_CYCLES_PER_FRAME 70224u   /* T-ciclos a velocidad normal (dots del LCD) */
#define GB_CLOCK_HZ 4194304u          /* 59.7275 frames/s */
#define GB_ROM_MAX_BYTES (8u * 1024u * 1024u)

typedef struct gb gb;

typedef enum gb_result {
    GB_OK = 0,
    GB_ERR_NULL_ARG,
    GB_ERR_OUT_OF_MEMORY,      /* la instancia queda válida y sin ROM */
    GB_ERR_ROM_TOO_SMALL,      /* < 0x150 bytes */
    GB_ERR_ROM_TOO_LARGE,      /* > GB_ROM_MAX_BYTES */
    GB_ERR_ROM_TRUNCATED,      /* cabecera declara más bytes que el archivo */
    GB_ERR_BAD_ROM_SIZE_CODE,  /* 0x148 fuera de rango */
    GB_ERR_BAD_RAM_SIZE_CODE,  /* 0x149 fuera de rango */
    GB_ERR_UNSUPPORTED_MBC,    /* 0x147 no soportado */
    GB_ERR_CGB_ONLY,           /* 0x143 == 0xC0 y se pidió modelo DMG */
    GB_ERR_NO_ROM,             /* operación que requiere ROM cargado */
    GB_ERR_SRAM_SIZE,          /* tamaño de .sav distinto al esperado */
    GB_ERR_STATE_MAGIC,
    GB_ERR_STATE_VERSION,
    GB_ERR_STATE_ROM_MISMATCH, /* otro ROM, u otro modelo (DMG / CGB / compatibilidad) */
    GB_ERR_STATE_CORRUPT,      /* CRC o longitudes inválidas */
    GB_ERR_BUFFER_TOO_SMALL
} gb_result;

typedef enum gb_model {
    GB_MODEL_AUTO = 0,  /* CGB si rom[0x143] & 0x80, si no DMG */
    GB_MODEL_DMG,
    GB_MODEL_CGB        /* con un ROM DMG: modo compatibilidad (paleta de color por título) */
} gb_model;

/* Paletas de compatibilidad (ROM DMG en CGB). 0 = automática, la que elige el
 * arranque de la CGB por el checksum del título. 1..12 = las combinaciones de
 * botones del arranque: 1 →, 2 ←, 3 ↑, 4 ↓, 5 →+A, 6 ←+A, 7 ↑+A, 8 ↓+A,
 * 9 →+B, 10 ←+B, 11 ↑+B, 12 ↓+B. */
#define GB_COMPAT_PALETTE_AUTO 0u
#define GB_COMPAT_PALETTES 12u

/* Máscara de botones: 1 = pulsado. El núcleo no filtra direcciones opuestas. */
enum {
    GB_BTN_A      = 1u << 0,
    GB_BTN_B      = 1u << 1,
    GB_BTN_SELECT = 1u << 2,
    GB_BTN_START  = 1u << 3,
    GB_BTN_RIGHT  = 1u << 4,
    GB_BTN_LEFT   = 1u << 5,
    GB_BTN_UP     = 1u << 6,
    GB_BTN_DOWN   = 1u << 7
};

/* Serie (docs/03-core-spec.md §Serial).
 * bit_cb: con reloj interno, por cada bit desplazado; recibe el bit saliente y
 *         devuelve el entrante (sin callback entra 1 = cable desconectado).
 * byte_cb: notificación al completar 8 bits (salida de las pruebas Blargg). */
typedef uint8_t (*gb_serial_bit_cb)(void *user, uint8_t bit_out);
typedef void (*gb_serial_byte_cb)(void *user, uint8_t byte_out);

typedef struct gb_options {
    gb_model model;
    uint32_t sample_rate;       /* p. ej. 48000; 0 = sin audio */
    uint32_t dmg_palette[4];    /* RGBA8888 (R en el byte bajo); todo 0 = gris por defecto */
    gb_serial_bit_cb serial_bit_cb;   /* opcional */
    gb_serial_byte_cb serial_byte_cb; /* opcional */
    void *serial_user;
    int64_t unix_time;          /* hora inicial para el RTC de MBC3 */
    uint8_t compat_palette;     /* GB_COMPAT_PALETTE_AUTO o 1..GB_COMPAT_PALETTES (fuera de rango = auto) */
} gb_options;

typedef struct gb_rom_info {
    char title[17];             /* ASCII imprimible, terminado en NUL */
    uint8_t cgb_flag;           /* rom[0x143] */
    uint8_t cart_type;          /* rom[0x147] */
    uint32_t rom_bytes;         /* según la cabecera */
    uint32_t sram_bytes;        /* tamaño exacto del .sav (sin bloque RTC) */
    bool has_battery;
    bool has_rtc;
    bool header_checksum_ok;
    bool global_checksum_ok;
    bool cgb_mode;              /* se ejecuta en una CGB (nativo o compatibilidad) */
    bool cgb_compat;            /* ROM DMG en CGB: paleta de compatibilidad */
    uint8_t fingerprint[32];    /* SHA-256 del ROM completo (saves y estados) */
} gb_rom_info;

/* Ciclo de vida */
gb *gb_create(void);
void gb_destroy(gb *g);
void gb_options_default(gb_options *opts);

/* Copia el ROM; valida; aplica estado post-boot. Todo malloc ocurre aquí.
 * Si ya había un ROM, se libera antes. Ante cualquier error la instancia queda sin ROM. */
gb_result gb_load_rom(gb *g, const uint8_t *data, size_t len, const gb_options *opts);
gb_result gb_rom_info_get(const gb *g, gb_rom_info *out);
void gb_reset(gb *g);

/* Ejecución */
void gb_set_buttons(gb *g, uint8_t mask);
void gb_run_frame(gb *g);
/* Avance acotado: ejecuta al menos `cycles` T-ciclos (termina en frontera de
 * instrucción) y devuelve los ejecutados. Para el lockstep del cable virtual. */
uint32_t gb_run_cycles(gb *g, uint32_t cycles);
/* T-ciclos emulados desde gb_load_rom (monótono, en tiempo real: en doble velocidad
 * un M-ciclo de CPU cuenta 2). Para ordenar eventos entre instancias. */
uint64_t gb_cycle_count(const gb *g);
bool gb_cpu_locked(const gb *g);           /* opcode ilegal ejecutado */
const uint32_t *gb_framebuffer(const gb *g); /* GB_SCREEN_W*GB_SCREEN_H RGBA8888 */
/* Cambia la paleta de compatibilidad (ver GB_COMPAT_PALETTES) sin reiniciar.
 * Solo tiene efecto con un ROM DMG en CGB; se aplica desde la siguiente línea. */
void gb_set_compat_palette(gb *g, uint8_t id);

/* Audio: frames estéreo intercalados int16 (L,R). Devuelve frames copiados. */
size_t gb_audio_read(gb *g, int16_t *out, size_t max_frames);
size_t gb_audio_available(const gb *g);

/* SRAM (partida del cartucho) */
gb_result gb_sram_load(gb *g, const uint8_t *data, size_t len); /* len == sram_bytes [+48 o +44 si RTC; también sin ellos] */
size_t gb_sram_save_size(const gb *g);                          /* sram_bytes [+48 si RTC] */
gb_result gb_sram_save(const gb *g, uint8_t *out, size_t cap);
bool gb_sram_dirty(const gb *g);   /* verdadero tras "juego guardó" (flanco RAM disable con datos sucios) */
void gb_sram_clear_dirty(gb *g);
void gb_rtc_set_time(gb *g, int64_t unix_time); /* al volver de background */

/* Save states */
size_t gb_state_size(const gb *g);
gb_result gb_state_save(const gb *g, uint8_t *out, size_t cap);
gb_result gb_state_load(gb *g, const uint8_t *data, size_t len); /* nunca confía en len internas */

/* Serie, lado esclavo (reloj externo): un pulso de reloj desde el otro extremo.
 * Desplaza bit_in hacia SB y devuelve el bit saliente. Sin transferencia activa
 * con reloj externo, no cambia nada y devuelve 1. */
uint8_t gb_serial_clock_external(gb *g, uint8_t bit_in);

const char *gb_result_str(gb_result r);

#ifdef __cplusplus
}
#endif
#endif /* POCKETGB_H */
