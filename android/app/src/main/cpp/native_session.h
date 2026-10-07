#ifndef POCKETGB_NATIVE_SESSION_H
#define POCKETGB_NATIVE_SESSION_H

#include <stddef.h>
#include <stdint.h>

#include "pocketgb.h"

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

native_session *native_session_create(void);
void native_session_destroy(native_session *session);
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
void native_session_set_touch_buttons(native_session *session, uint8_t mask);
void native_session_set_physical_buttons(native_session *session, uint8_t mask);
uint8_t native_session_requested_buttons(native_session *session);
uint8_t native_session_applied_buttons(native_session *session);
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

/* Solo con la sesión READY (cargada y sin arrancar). */
int native_session_rom_info(native_session *session, gb_rom_info *out);
/* Tamaño del .sav que produce la sesión (RAM [+48 si RTC]); 0 sin ROM. */
size_t native_session_sram_size(native_session *session);
/* Solo con el hilo sin arrancar (READY) o PAUSED. */
int native_session_sram_load(native_session *session, const uint8_t *data, size_t length);
/* Crece cada vez que el juego guarda (flanco "RAM disable con datos sucios"). Válida en cualquier estado. */
uint64_t native_session_sram_dirty_seq(native_session *session);
/* Copia la SRAM [+RTC] en `out` (capacidad `capacity`, al menos sram_size). Con la sesión RUNNING espera
 * hasta 1 s a que el hilo nativo publique una instantánea (NS_TIMEOUT si no); aparcada, copia directo. */
int native_session_sram_copy(native_session *session, uint8_t *out, size_t capacity);
int native_session_state_size(native_session *session, size_t *out);
int native_session_state_save(native_session *session, uint8_t *out, size_t capacity);
int native_session_state_load(native_session *session, const uint8_t *data, size_t length);
/* GB_SCREEN_W * GB_SCREEN_H píxeles RGBA8888. */
int native_session_copy_framebuffer(native_session *session, uint32_t *out, size_t pixel_capacity);
/* A9: adelanta el reloj del MBC3 a `unix_time` (nunca lo retrasa; sin RTC no hace nada). Solo aparcada
 * (READY sin arrancar, PAUSED o STOPPED): tras retomar el estado automático, que trae la hora en que se guardó. */
int native_session_set_rtc_time(native_session *session, int64_t unix_time);

#endif
