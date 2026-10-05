/*
 * pocketgba.h — API pública del núcleo Game Boy Advance de PocketGB.
 *
 * Spec: docs/10-gba-spec.md (comportamiento) y docs/hitos/G-README.md (plan).
 * Mismas reglas que el núcleo GB (AGENTS.md, regla dura 4): C11, sin I/O, sin
 * estado global, sin malloc dentro de gba_run_frame, determinista.
 * Una instancia NO es thread-safe: llamar todo desde un único hilo de emulación.
 * Todos los símbolos públicos e internos llevan el prefijo gba_ (o arm_ dentro
 * de la CPU) para convivir con el núcleo GB en el mismo binario.
 */
#ifndef POCKETGBA_H
#define POCKETGBA_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define GBA_SCREEN_W 240
#define GBA_SCREEN_H 160
#define GBA_CYCLES_PER_FRAME 280896u   /* 228 líneas × 1232 ciclos */
#define GBA_CLOCK_HZ 16777216u         /* 59.7275 frames/s */
#define GBA_ROM_MAX_BYTES (32u * 1024u * 1024u)
#define GBA_ROM_MIN_BYTES 0xC0u        /* cabecera completa */
#define GBA_BIOS_BYTES 16384u
#define GBA_RTC_BYTES 16u

typedef struct gba gba;

typedef enum gba_result {
    GBA_OK = 0,
    GBA_ERR_NULL_ARG,
    GBA_ERR_OUT_OF_MEMORY,      /* la instancia queda válida y sin ROM */
    GBA_ERR_ROM_TOO_SMALL,      /* < GBA_ROM_MIN_BYTES */
    GBA_ERR_ROM_TOO_LARGE,      /* > GBA_ROM_MAX_BYTES */
    GBA_ERR_BAD_HEADER,         /* byte fijo 0xB2 != 0x96 */
    GBA_ERR_NO_ROM,
    GBA_ERR_BIOS_SIZE,          /* BIOS != GBA_BIOS_BYTES */
    GBA_ERR_SAVE_SIZE,          /* .sav de tamaño distinto al medio detectado */
    GBA_ERR_STATE_MAGIC,
    GBA_ERR_STATE_VERSION,
    GBA_ERR_STATE_ROM_MISMATCH,
    GBA_ERR_STATE_CORRUPT,
    GBA_ERR_BUFFER_TOO_SMALL
} gba_result;

/* Medio de guardado del cartucho. AUTO = se detecta por las cadenas de la
 * biblioteca de Nintendo dentro del ROM (EEPROM_V, SRAM_V, FLASH_V,
 * FLASH512_V, FLASH1M_V); sin cadena = NONE. */
typedef enum gba_save_type {
    GBA_SAVE_AUTO = 0,
    GBA_SAVE_NONE,
    GBA_SAVE_SRAM,        /* 32 KiB */
    GBA_SAVE_FLASH64,     /* 64 KiB */
    GBA_SAVE_FLASH128,    /* 128 KiB, dos bancos */
    GBA_SAVE_EEPROM512,   /* 512 B */
    GBA_SAVE_EEPROM8K     /* 8 KiB */
} gba_save_type;

/* Máscara de botones: 1 = pulsado (el núcleo la invierte para KEYINPUT). */
enum {
    GBA_BTN_A      = 1u << 0,
    GBA_BTN_B      = 1u << 1,
    GBA_BTN_SELECT = 1u << 2,
    GBA_BTN_START  = 1u << 3,
    GBA_BTN_RIGHT  = 1u << 4,
    GBA_BTN_LEFT   = 1u << 5,
    GBA_BTN_UP     = 1u << 6,
    GBA_BTN_DOWN   = 1u << 7,
    GBA_BTN_R      = 1u << 8,
    GBA_BTN_L      = 1u << 9
};

typedef struct gba_options {
    uint32_t sample_rate;       /* p. ej. 48000; 0 = sin audio */
    gba_save_type save_type;    /* GBA_SAVE_AUTO salvo ajuste por juego */
    int64_t unix_time;          /* hora LOCAL inicial del RTC (segundos desde 1970 en la zona del usuario) */
    uint8_t rtc;                /* GBA_RTC_AUTO (por código de juego), GBA_RTC_ON u GBA_RTC_OFF */
} gba_options;

enum { GBA_RTC_AUTO = 0, GBA_RTC_ON = 1, GBA_RTC_OFF = 2 };

typedef struct gba_rom_info {
    char title[13];             /* 0xA0, ASCII imprimible, terminado en NUL */
    char game_code[5];          /* 0xAC */
    char maker_code[3];         /* 0xB0 */
    uint8_t version;            /* 0xBC */
    uint32_t rom_bytes;         /* tamaño real del archivo */
    gba_save_type save_type;    /* el efectivo (nunca AUTO) */
    uint32_t save_bytes;        /* tamaño del .sav (0 sin medio). EEPROM sin ajuste: 512
                                 * hasta que el .sav o la primera DMA digan 8 KiB; pedir
                                 * gba_save_size() al guardar. */
    bool has_rtc;               /* GPIO con RTC (Pokémon R/S/E y otros) */
    bool header_checksum_ok;    /* 0xBD */
    bool bios_loaded;           /* BIOS real del usuario; si no, HLE */
    uint8_t fingerprint[32];    /* SHA-256 del ROM completo (saves y estados) */
} gba_rom_info;

/* Ciclo de vida */
gba *gba_create(void);
void gba_destroy(gba *g);
void gba_options_default(gba_options *opts);

/* BIOS opcional: el volcado propio del usuario (nunca en el repo, regla dura 1).
 * Sin BIOS, las SWI se emulan en alto nivel. Llamar antes de gba_load_rom. */
gba_result gba_load_bios(gba *g, const uint8_t *data, size_t len);

/* Copia el ROM, valida la cabecera, detecta el medio de guardado y deja la CPU
 * en el estado posterior al arranque. Todo malloc ocurre aquí. Ante cualquier
 * error la instancia queda sin ROM. */
gba_result gba_load_rom(gba *g, const uint8_t *data, size_t len, const gba_options *opts);
gba_result gba_rom_info_get(const gba *g, gba_rom_info *out);
void gba_reset(gba *g);

/* Ejecución */
void gba_set_buttons(gba *g, uint16_t mask);
void gba_run_frame(gba *g);                 /* hasta el siguiente VBlank */
uint32_t gba_run_cycles(gba *g, uint32_t cycles);
uint64_t gba_cycle_count(const gba *g);
const uint32_t *gba_framebuffer(const gba *g); /* GBA_SCREEN_W*GBA_SCREEN_H RGBA8888, R en el byte bajo */

/* Audio: frames estéreo intercalados int16 (L,R). Devuelve frames copiados. */
size_t gba_audio_read(gba *g, int16_t *out, size_t max_frames);
size_t gba_audio_available(const gba *g);

/* Partida del cartucho: bytes crudos del medio (compatible con mGBA/VBA);
 * el RTC va aparte (GBA_RTC_BYTES). */
/* len == save_bytes (EEPROM sin ajuste y sin tamaño confirmado: 512 u 8192, que
 * lo fija; ya confirmado, solo el mismo tamaño).
 * Cualquier otro tamaño: GBA_ERR_SAVE_SIZE y la partida en memoria no cambia. */
gba_result gba_save_load(gba *g, const uint8_t *data, size_t len);
size_t gba_save_size(const gba *g);
gba_result gba_save_write(const gba *g, uint8_t *out, size_t cap);
bool gba_save_dirty(const gba *g);
void gba_save_clear_dirty(gba *g);
gba_result gba_rtc_load(gba *g, const uint8_t *data, size_t len); /* 16 B; desplazamiento ≤ ±200 años */
gba_result gba_rtc_save(const gba *g, uint8_t *out, size_t cap);
void gba_rtc_set_time(gba *g, int64_t unix_time);

/* Save states: formato propio versionado con CRC-32 y huella del ROM. */
size_t gba_state_size(const gba *g);
gba_result gba_state_save(const gba *g, uint8_t *out, size_t cap);
gba_result gba_state_load(gba *g, const uint8_t *data, size_t len);

const char *gba_result_str(gba_result r);

#ifdef __cplusplus
}
#endif
#endif /* POCKETGBA_H */
