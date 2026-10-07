#ifndef POCKETGB_NATIVE_SESSION_H
#define POCKETGB_NATIVE_SESSION_H

#include <stddef.h>
#include <stdint.h>

#include "pocketgb.h"
#include "pocketgba.h"

typedef struct native_session native_session;
typedef struct ANativeWindow ANativeWindow;

enum native_session_state {
    NATIVE_SESSION_NEW = 0,
    NATIVE_SESSION_READY,
    NATIVE_SESSION_RUNNING,
    NATIVE_SESSION_PAUSED,
    NATIVE_SESSION_STOPPED
};

enum native_audio_state {
    NATIVE_AUDIO_STOPPED = 0,
    NATIVE_AUDIO_PRIMING,
    NATIVE_AUDIO_LIVE,
    NATIVE_AUDIO_CLOCK_FALLBACK
};

/* Códigos propios de las operaciones de partida/estado (los >= 0 son gb_result). */
#define NS_OK 0
/* La sesión no está aparcada (RUNNING) o no está en un estado que admita la operación. */
#define NS_BUSY (-1)
/* El hilo nativo no atendió la petición de instantánea de la SRAM en 1 s. */
#define NS_TIMEOUT (-2)

/* Argumento fuera de rango (paleta). */
#define NS_INVALID (-3)
/* La sesión no está en modo compatibilidad CGB: no hay paleta que cambiar. */
#define NS_NOT_COMPAT (-4)

/* Modo de escalado de la superficie (= ScaleMode de Kotlin). */
enum native_scale_mode {
    NATIVE_SCALE_INTEGER = 0,
    NATIVE_SCALE_FILL = 1
};

/* --- Consolas (N8) ---
 * Una sesión ejecuta un único núcleo, elegido al crearla: `core/` (Game Boy y Color) o `gba/` (Game Boy
 * Advance). Todo lo demás (hilo, pacing, ring SPSC + AAudio, superficie, escalado, aparcado, instantáneas de
 * la partida) es común; el núcleo se usa a través de un `switch` por consola en native_session.c. */
enum native_console {
    NATIVE_CONSOLE_GB = 0,
    NATIVE_CONSOLE_GBA = 1
};

/* Códigos de resultado de las operaciones de la sesión: los `gb_result` (0..16), 17 = argumento inválido
 * (lo añade el puente JNI) y los errores de GBA traducidos (native_result_from_gba): los comunes al código GB
 * equivalente (p. ej. GBA_ERR_SAVE_SIZE → GB_ERR_SRAM_SIZE) y estos tres propios. Así Kotlin traduce un solo
 * espacio de códigos sea cual sea la consola. */
#define NS_ERR_INVALID_ARGUMENT 17
#define NS_ERR_GBA_BAD_HEADER 18
#define NS_ERR_GBA_BIOS_SIZE 19
#define NS_ERR_GBA_STATE_CONFIG 20
/* Un gba_result sin equivalente (nunca debería pasar): NS_ERR_GBA_UNKNOWN_BASE + código. */
#define NS_ERR_GBA_UNKNOWN_BASE 1000
int native_result_from_gba(gba_result result);

/* SHA-256 de la BIOS oficial de GBA (GBA, GBA SP, Micro y Game Boy Player): la misma que valida iOS
 * (`GBACoreBridge.knownBIOSSHA256`). Solo se carga una BIOS de GBA_BIOS_BYTES con este resumen. */
#define NATIVE_GBA_BIOS_SHA256 "fd2547724b505f487e6dcb29ec2ecff3af35a841a77ab2e85fd87350abd36570"
bool native_gba_bios_is_official(const uint8_t *data, size_t length);

/* Hora local del dispositivo (la que cuenta el RTC del GBA) para una hora Unix UTC. */
int64_t native_local_time(int64_t unix_time);

/* Crea una sesión de `console` (enum native_console); NULL si no existe esa consola o falta memoria. */
native_session *native_session_create_console(int console);
/* Game Boy (= native_session_create_console(NATIVE_CONSOLE_GB)). */
native_session *native_session_create(void);
void native_session_destroy(native_session *session);
int native_session_console(native_session *session);
/* Ancho y alto del framebuffer de la consola de la sesión: 160×144 (GB) o 240×160 (GBA). */
int native_session_screen_width(native_session *session);
int native_session_screen_height(native_session *session);
/* Game Boy Advance. Solo antes de arrancar y en una sesión GBA. `bios` (opcional, NULL = HLE) solo se usa si
 * es la BIOS oficial (native_gba_bios_is_official); si no, se ignora y el núcleo emula la BIOS. `options->
 * unix_time` es la hora LOCAL. Si falla, el núcleo se recrea limpio (sin ROM ni BIOS). Devuelve un código del
 * espacio común (NS_OK, gb_result equivalentes o NS_ERR_GBA_*). */
int native_session_load_gba(
    native_session *session,
    const uint8_t *rom,
    size_t length,
    const uint8_t *bios,
    size_t bios_length,
    const gba_options *options
);
/* Solo con la sesión GBA cargada y sin arrancar (READY). `eeprom_size_fixed` = EEPROM con el tamaño fijado
 * por un ajuste (save_type forzado): su `.sav` solo vale con ese tamaño. */
int native_session_gba_rom_info(native_session *session, gba_rom_info *out, bool *eeprom_size_fixed);
/* El mayor `.sav` posible de esta ROM (= sram_size, salvo una EEPROM de GBA sin tamaño confirmado: 8 KiB
 * [+16 de RTC] aunque hoy mida 512 B). Es lo que reserva la instantánea y lo que debe medir el búfer de
 * `native_session_sram_copy`. */
size_t native_session_sram_capacity(native_session *session);
/* `options->unix_time` inicializa el RTC del MBC3. Reserva aquí (nunca en el bucle) la instantánea de la SRAM. */
gb_result native_session_load(
    native_session *session,
    const uint8_t *rom,
    size_t length,
    const gb_options *options
);
int native_session_start(native_session *session);
int native_session_pause(native_session *session);
int native_session_resume(native_session *session);
int native_session_stop(native_session *session);
enum native_session_state native_session_get_state(native_session *session);
uint64_t native_session_frame_count(native_session *session);
/* Máscara común a las dos consolas (= iOS ConsoleCore.setButtons): A, B, Select, Start, derecha, izquierda,
 * arriba, abajo y, solo en GBA, R (bit 8) y L (bit 9). Se recorta a los bits de la consola (0xFF en GB). */
void native_session_set_touch_buttons(native_session *session, uint16_t mask);
void native_session_set_physical_buttons(native_session *session, uint16_t mask);
uint16_t native_session_requested_buttons(native_session *session);
uint16_t native_session_applied_buttons(native_session *session);
void native_session_set_speed(native_session *session, unsigned speed);
unsigned native_session_speed(native_session *session);
/* Paleta de compatibilidad en caliente (0 = automática, 1..GB_COMPAT_PALETTES). La aplica el hilo nativo entre
 * frames; NS_INVALID si está fuera de rango, NS_NOT_COMPAT si la ROM no corre en compatibilidad CGB. */
int native_session_set_compat_palette(native_session *session, int id);
int native_session_compat_palette(native_session *session);
/* Ganancia lineal del audio, recortada a [0,1] (NaN/inf se ignoran). Atómica; no reconstruye el stream. */
void native_session_set_volume(native_session *session, float gain);
float native_session_volume(native_session *session);
/* Modo fuera de {INTEGER, FILL} se ignora. Se aplica en el siguiente frame dibujado. */
void native_session_set_scale_mode(native_session *session, int mode);
int native_session_scale_mode(native_session *session);
enum native_audio_state native_session_audio_state(native_session *session);
uint64_t native_session_audio_frames_produced(native_session *session);
uint64_t native_session_audio_frames_consumed(native_session *session);
void native_session_set_window(native_session *session, ANativeWindow *window);

/* --- Partidas y estados ---
 * Invariante de hilo: mientras la sesión está RUNNING solo el hilo nativo toca el core. Las funciones
 * que lo necesitan quieren la sesión aparcada (hilo sin arrancar, PAUSED o STOPPED) y devuelven NS_BUSY
 * si no lo está; solo `sram_copy` es válida con la sesión corriendo (pide una instantánea al hilo
 * nativo, que la atiende tras `gb_run_frame`) y `sram_dirty_seq`, que solo lee un contador. */

/* Solo con la sesión GB READY (cargada y sin arrancar); en una sesión GBA, NS_INVALID. */
int native_session_rom_info(native_session *session, gb_rom_info *out);
/* Tamaño del .sav que produce la sesión (GB: RAM [+48 si RTC]; GBA: medio [+16 si RTC]); 0 sin ROM. */
size_t native_session_sram_size(native_session *session);
/* Solo con el hilo sin arrancar (READY) o PAUSED. GBA: el medio solo o el medio + 16 B de RTC (con un medio
 * de 0 bytes, solo el RTC); cualquier otro tamaño se rechaza sin cambiar nada, tampoco el RTC. */
int native_session_sram_load(native_session *session, const uint8_t *data, size_t length);
/* Crece cada vez que el juego guarda (GB: flanco "RAM disable con datos sucios"; GBA: una escritura que cambia
 * algún byte del medio). Válida en cualquier estado. */
uint64_t native_session_sram_dirty_seq(native_session *session);
/* Copia la SRAM [+RTC] en `out` (capacidad `capacity`, al menos sram_capacity) y deja en `*length` (si no es
 * NULL) los bytes copiados: el `.sav` de ese instante (en GBA con EEPROM puede crecer de 512 B a 8 KiB). Con la
 * sesión RUNNING espera hasta 1 s a que el hilo nativo publique una instantánea (NS_TIMEOUT si no); aparcada,
 * copia directo. GBA: el medio y, con RTC, sus 16 bytes al final (el mismo `.sav` que iOS). */
int native_session_sram_copy(native_session *session, uint8_t *out, size_t capacity, size_t *length);
int native_session_state_size(native_session *session, size_t *out);
int native_session_state_save(native_session *session, uint8_t *out, size_t capacity);
int native_session_state_load(native_session *session, const uint8_t *data, size_t length);
/* native_session_screen_width × _height píxeles RGBA8888 (160×144 en GB, 240×160 en GBA). */
int native_session_copy_framebuffer(native_session *session, uint32_t *out, size_t pixel_capacity);
/* A9: lleva el reloj del cartucho a la hora Unix UTC `unix_time`. GB: adelanta el del MBC3 (nunca lo retrasa; sin RTC
 * no hace nada). GBA (N8): el RTC vuelve a la hora local del dispositivo más el desplazamiento que fijó el juego. Solo
 * aparcada (READY sin arrancar, PAUSED o STOPPED): tras retomar el estado automático, que trae la hora en que se guardó. */
int native_session_set_rtc_time(native_session *session, int64_t unix_time);

#endif
