#include "native_session.h"

#include <errno.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include <pthread.h>
#include <android/native_window.h>

#include "audio_output.h"
#include "audio_ring.h"

/* SHA-256 propio del núcleo GB (core/src/sha256.c), el mismo que usa gba/ para la huella del ROM. */
void sha256(const uint8_t *data, size_t len, uint8_t out[32]);

#define FRAME_NANOS 16742706L
/* La EEPROM más grande del GBA: una EEPROM sin tamaño confirmado puede pasar de 512 B a 8 KiB. */
#define GBA_EEPROM_MAX_BYTES 8192u

struct native_session {
    gb *core;                     /* Game Boy: el núcleo; NULL en una sesión GBA */
    gba *gba_core;                /* Game Boy Advance: el núcleo; NULL en una sesión GB */
    /* Fijados al crear la sesión (inmutables después: se leen sin mutex). */
    enum native_console console;
    int screen_width;
    int screen_height;
    uint16_t button_mask;         /* bits que entiende la consola: 0xFF (GB) o 0x3FF (GBA, con R y L) */
    /* GBA, fijados al cargar (antes de arrancar el hilo). */
    bool gba_has_rtc;             /* el .sav lleva los 16 B del RTC al final */
    bool gba_eeprom_auto;         /* EEPROM sin ajuste: su tamaño (512 B u 8 KiB) lo confirma el .sav o la 1.ª DMA */
    bool gba_eeprom_fixed;        /* EEPROM con el tamaño fijado por un ajuste por juego */
    pthread_t thread;
    pthread_mutex_t mutex;
    pthread_cond_t condition;
    enum native_session_state state;
    bool thread_started;
    bool pause_requested;
    bool stop_requested;
    uint64_t frames;
    uint16_t touch_buttons;
    uint16_t physical_buttons;
    uint16_t applied_buttons;
    unsigned speed;
    bool timing_reset;
    audio_ring audio_ring_buffer;
    audio_output audio;
    enum native_audio_state audio_state;
    int16_t audio_scratch[2048];
    ANativeWindow *window;
    /* Ajustes en caliente: los escribe cualquier hilo y los lee el hilo nativo entre frames (sin mutex). */
    atomic_int scale_mode;        /* enum native_scale_mode */
    atomic_int pending_palette;   /* -1 = sin petición; 0..GB_COMPAT_PALETTES = aplicar antes del próximo frame */
    atomic_int current_palette;   /* última paleta pedida (0 = automática) */
    bool cgb_compat;              /* la ROM cargada corre en modo compatibilidad CGB */
    /* Geometría del blit, recalculada solo al cambiar el tamaño del buffer o el modo (hilo nativo, sin malloc). */
    int layout_width;
    int layout_height;
    int layout_mode;
    int draw_width;
    int draw_height;
    int draw_left;
    int draw_top;
    uint32_t step_x;              /* 16.16: píxeles de origen por píxel de destino */
    uint32_t step_y;
    /* Partidas. `sram_snapshot` se reserva en load (nunca en el bucle de emulación) y es el buzón por el
     * que el hilo nativo entrega la SRAM a otro hilo mientras corre. Todo bajo `mutex`. */
    uint8_t *sram_snapshot;
    size_t sram_size;
    size_t sram_capacity;         /* bytes reservados en sram_snapshot: el mayor .sav posible de esta ROM */
    size_t snapshot_length;       /* bytes válidos de la última instantánea servida */
    int snapshot_result;          /* resultado de la última instantánea servida */
    uint64_t sram_dirty_seq;
    bool snapshot_requested;
    uint64_t snapshots_served;
};

#define SNAPSHOT_TIMEOUT_NANOS 1000000000L

/* ---- Consolas ---- */

int native_result_from_gba(gba_result result) {
    switch (result) {
    case GBA_OK: return NS_OK;
    case GBA_ERR_NULL_ARG: return GB_ERR_NULL_ARG;
    case GBA_ERR_OUT_OF_MEMORY: return GB_ERR_OUT_OF_MEMORY;
    case GBA_ERR_ROM_TOO_SMALL: return GB_ERR_ROM_TOO_SMALL;
    case GBA_ERR_ROM_TOO_LARGE: return GB_ERR_ROM_TOO_LARGE;
    case GBA_ERR_BAD_HEADER: return NS_ERR_GBA_BAD_HEADER;
    case GBA_ERR_NO_ROM: return GB_ERR_NO_ROM;
    case GBA_ERR_BIOS_SIZE: return NS_ERR_GBA_BIOS_SIZE;
    case GBA_ERR_SAVE_SIZE: return GB_ERR_SRAM_SIZE;
    case GBA_ERR_STATE_MAGIC: return GB_ERR_STATE_MAGIC;
    case GBA_ERR_STATE_VERSION: return GB_ERR_STATE_VERSION;
    case GBA_ERR_STATE_ROM_MISMATCH: return GB_ERR_STATE_ROM_MISMATCH;
    case GBA_ERR_STATE_CORRUPT: return GB_ERR_STATE_CORRUPT;
    case GBA_ERR_BUFFER_TOO_SMALL: return GB_ERR_BUFFER_TOO_SMALL;
    case GBA_ERR_STATE_CONFIG: return NS_ERR_GBA_STATE_CONFIG;
    }
    return NS_ERR_GBA_UNKNOWN_BASE + (int)result;
}

bool native_gba_bios_is_official(const uint8_t *data, size_t length) {
    if (data == NULL || length != GBA_BIOS_BYTES) return false;
    uint8_t digest[32];
    sha256(data, length, digest);
    static const char hex[] = "0123456789abcdef";
    const char *expected = NATIVE_GBA_BIOS_SHA256;
    bool same = true;
    for (size_t i = 0u; i < sizeof digest; ++i) {
        same &= expected[2u * i] == hex[digest[i] >> 4] && expected[2u * i + 1u] == hex[digest[i] & 0x0Fu];
    }
    return same;
}

int64_t native_local_time(int64_t unix_time) {
    const time_t seconds = (time_t)unix_time;
    struct tm local;
    if ((int64_t)seconds != unix_time || localtime_r(&seconds, &local) == NULL) return unix_time;
    return unix_time + (int64_t)local.tm_gmtoff;
}

/* ---- Núcleo de la sesión ----
 * Los únicos puntos que distinguen Game Boy de Game Boy Advance. Mismas reglas de hilo que el resto: con la
 * sesión RUNNING solo los llama el hilo nativo; aparcada, quien tenga el mutex. Los resultados van en el
 * espacio común de códigos (native_session.h). */

static bool is_gba(const native_session *session) {
    return session->console == NATIVE_CONSOLE_GBA;
}

static void core_destroy(native_session *session) {
    gb_destroy(session->core);
    gba_destroy(session->gba_core);
    session->core = NULL;
    session->gba_core = NULL;
}

static void core_set_buttons(native_session *session, uint16_t buttons) {
    if (is_gba(session)) {
        gba_set_buttons(session->gba_core, buttons);
    } else {
        gb_set_buttons(session->core, (uint8_t)buttons);
    }
}

static void core_run_frame(native_session *session) {
    if (is_gba(session)) {
        gba_run_frame(session->gba_core);
    } else {
        gb_run_frame(session->core);
    }
}

static const uint32_t *core_framebuffer(const native_session *session) {
    return is_gba(session) ? gba_framebuffer(session->gba_core) : gb_framebuffer(session->core);
}

static size_t core_audio_available(const native_session *session) {
    return is_gba(session) ? gba_audio_available(session->gba_core) : gb_audio_available(session->core);
}

static size_t core_audio_read(native_session *session, int16_t *out, size_t max_frames) {
    return is_gba(session) ? gba_audio_read(session->gba_core, out, max_frames)
                           : gb_audio_read(session->core, out, max_frames);
}

static bool core_sram_dirty(const native_session *session) {
    return is_gba(session) ? gba_save_dirty(session->gba_core) : gb_sram_dirty(session->core);
}

static void core_sram_clear_dirty(native_session *session) {
    if (is_gba(session)) {
        gba_save_clear_dirty(session->gba_core);
    } else {
        gb_sram_clear_dirty(session->core);
    }
}

/* Tamaño del .sav de este instante. GBA: el medio (EEPROM: el confirmado) [+16 B de RTC]. */
static size_t core_sram_size(const native_session *session) {
    if (!is_gba(session)) return gb_sram_save_size(session->core);
    return gba_save_size(session->gba_core) + (session->gba_has_rtc ? (size_t)GBA_RTC_BYTES : 0u);
}

/* El .sav completo en `out`. GBA: el medio y, con RTC, sus 16 bytes al final (formato de iOS, G7). */
static int core_sram_save(const native_session *session, uint8_t *out, size_t capacity) {
    if (!is_gba(session)) return (int)gb_sram_save(session->core, out, capacity);
    const size_t media = gba_save_size(session->gba_core);
    if (capacity < core_sram_size(session)) return GB_ERR_BUFFER_TOO_SMALL;
    gba_result result = gba_save_write(session->gba_core, out, media);
    if (result == GBA_OK && session->gba_has_rtc) {
        result = gba_rtc_save(session->gba_core, out + media, GBA_RTC_BYTES);
    }
    return native_result_from_gba(result);
}

static bool gba_media_size_ok(const native_session *session, size_t length) {
    if (session->gba_eeprom_auto) return length == 512u || length == GBA_EEPROM_MAX_BYTES;
    return length == gba_save_size(session->gba_core);
}

/* GBA: acepta el medio solo o el medio + RTC (con un medio de 0 bytes, solo el RTC), como iOS
 * (GBACoreBridge.sramLoad). Ante cualquier fallo nada cambia: si el medio se rechaza después de aplicar el RTC,
 * se restaura el RTC anterior. */
static int core_sram_load(native_session *session, const uint8_t *data, size_t length) {
    if (!is_gba(session)) return (int)gb_sram_load(session->core, data, length);
    gba *core = session->gba_core;
    size_t media = length;
    const uint8_t *rtc = NULL;
    if (session->gba_has_rtc && length >= GBA_RTC_BYTES && gba_media_size_ok(session, length - GBA_RTC_BYTES)) {
        media = length - GBA_RTC_BYTES;
        rtc = data + media;
    }
    if (!gba_media_size_ok(session, media) || (media == 0u && rtc == NULL)) return GB_ERR_SRAM_SIZE;
    uint8_t previous_rtc[GBA_RTC_BYTES];
    if (rtc != NULL) {
        gba_result result = gba_rtc_save(core, previous_rtc, sizeof previous_rtc);
        if (result == GBA_OK) result = gba_rtc_load(core, rtc, GBA_RTC_BYTES);
        if (result != GBA_OK) return native_result_from_gba(result);
    }
    if (media == 0u) return NS_OK;
    const gba_result result = gba_save_load(core, data, media);
    if (result != GBA_OK && rtc != NULL) (void)gba_rtc_load(core, previous_rtc, sizeof previous_rtc);
    return native_result_from_gba(result);
}

static size_t core_state_size(const native_session *session) {
    return is_gba(session) ? gba_state_size(session->gba_core) : gb_state_size(session->core);
}

static int core_state_save(const native_session *session, uint8_t *out, size_t capacity) {
    if (!is_gba(session)) return (int)gb_state_save(session->core, out, capacity);
    return native_result_from_gba(gba_state_save(session->gba_core, out, capacity));
}

static int core_state_load(native_session *session, const uint8_t *data, size_t length) {
    if (!is_gba(session)) return (int)gb_state_load(session->core, data, length);
    return native_result_from_gba(gba_state_load(session->gba_core, data, length));
}

/* Hora real al RTC. GB: el MBC3 avanza hasta `unix_time` (nunca retrocede). GBA: el RTC cuenta hora local. */
static void core_rtc_set_time(native_session *session, int64_t unix_time) {
    if (is_gba(session)) {
        gba_rtc_set_time(session->gba_core, native_local_time(unix_time));
    } else {
        gb_rtc_set_time(session->core, unix_time);
    }
}

static void set_audio_state(native_session *session, enum native_audio_state state) {
    (void)pthread_mutex_lock(&session->mutex);
    session->audio_state = state;
    (void)pthread_mutex_unlock(&session->mutex);
}

static void sync_audio_speed(native_session *session, unsigned speed) {
    (void)pthread_mutex_lock(&session->mutex);
    const enum native_audio_state state = session->audio_state;
    (void)pthread_mutex_unlock(&session->mutex);
    if (speed > 1u && state != NATIVE_AUDIO_STOPPED) {
        audio_output_stop(&session->audio);
        audio_ring_clear(&session->audio_ring_buffer);
        set_audio_state(session, NATIVE_AUDIO_STOPPED);
    } else if (speed == 1u && state == NATIVE_AUDIO_STOPPED) {
        audio_ring_clear(&session->audio_ring_buffer);
        set_audio_state(session, NATIVE_AUDIO_PRIMING);
    }
}

static void produce_audio(native_session *session, unsigned speed) {
    size_t available = core_audio_available(session);
    while (available > 0u) {
        const size_t requested = available < 1024u ? available : 1024u;
        const size_t frames = core_audio_read(session, session->audio_scratch, requested);
        if (speed == 1u) {
            (void)audio_ring_write(&session->audio_ring_buffer, session->audio_scratch, frames);
        }
        if (frames == 0u) break;
        available -= frames;
    }

    if (speed != 1u) return;

    (void)pthread_mutex_lock(&session->mutex);
    const bool should_start = session->audio_state == NATIVE_AUDIO_PRIMING &&
        audio_ring_available(&session->audio_ring_buffer) >= 2048u;
    (void)pthread_mutex_unlock(&session->mutex);
    if (should_start) {
        const bool started = audio_output_start(&session->audio);
        (void)pthread_mutex_lock(&session->mutex);
        session->audio_state = started ? NATIVE_AUDIO_LIVE : NATIVE_AUDIO_CLOCK_FALLBACK;
        (void)pthread_mutex_unlock(&session->mutex);
    }
}

static bool audio_pacing_interrupted(native_session *session) {
    (void)pthread_mutex_lock(&session->mutex);
    const bool interrupted = session->pause_requested || session->stop_requested || session->speed > 1u;
    (void)pthread_mutex_unlock(&session->mutex);
    return interrupted;
}

static int64_t elapsed_nanos(const struct timespec *start, const struct timespec *end) {
    return (int64_t)(end->tv_sec - start->tv_sec) * 1000000000LL + end->tv_nsec - start->tv_nsec;
}

static void pace_from_audio(native_session *session) {
    struct timespec window_start;
    (void)clock_gettime(CLOCK_MONOTONIC, &window_start);
    uint64_t consumed = atomic_load_explicit(&session->audio_ring_buffer.total_read, memory_order_relaxed);
    unsigned timeouts = 0u;
    const struct timespec poll = {.tv_sec = 0, .tv_nsec = 1000000L};
    while (audio_ring_available(&session->audio_ring_buffer) > 2048u) {
        if (audio_pacing_interrupted(session)) return;
        if (atomic_load_explicit(&session->audio.failed, memory_order_acquire)) {
            audio_output_stop(&session->audio);
            set_audio_state(session, NATIVE_AUDIO_CLOCK_FALLBACK);
            return;
        }
        (void)nanosleep(&poll, NULL);
        const uint64_t now_consumed = atomic_load_explicit(
            &session->audio_ring_buffer.total_read,
            memory_order_relaxed
        );
        struct timespec now;
        (void)clock_gettime(CLOCK_MONOTONIC, &now);
        if (now_consumed != consumed) {
            consumed = now_consumed;
            timeouts = 0u;
            window_start = now;
        } else if (elapsed_nanos(&window_start, &now) >= 25000000LL) {
            timeouts += 1u;
            window_start = now;
            if (timeouts >= 4u) {
                audio_output_stop(&session->audio);
                set_audio_state(session, NATIVE_AUDIO_CLOCK_FALLBACK);
                return;
            }
        }
    }
}

/* Calcula el rectángulo de dibujo y los pasos 16.16 para un buffer de `width` x `height`.
 * Entero: mayor múltiplo entero que quepa (si no cabe ninguno, escala fraccional que quepa). Llenar: la
 * proporción de la pantalla de la consola (10:9 en GB, 3:2 en GBA) que quepa, centrado. Siempre vecino más
 * cercano. */
static void compute_layout(native_session *session, int width, int height, int mode) {
    const int screen_width = session->screen_width;
    const int screen_height = session->screen_height;
    int draw_width;
    int draw_height;
    const int integer_scale = width / screen_width < height / screen_height ? width / screen_width : height / screen_height;
    if (mode == NATIVE_SCALE_INTEGER && integer_scale >= 1) {
        draw_width = screen_width * integer_scale;
        draw_height = screen_height * integer_scale;
    } else if ((int64_t)width * screen_height <= (int64_t)height * screen_width) {
        draw_width = width;
        draw_height = (int)((int64_t)width * screen_height / screen_width);
    } else {
        draw_height = height;
        draw_width = (int)((int64_t)height * screen_width / screen_height);
    }
    if (draw_width < 1) draw_width = 1;
    if (draw_height < 1) draw_height = 1;
    session->layout_width = width;
    session->layout_height = height;
    session->layout_mode = mode;
    session->draw_width = draw_width;
    session->draw_height = draw_height;
    session->draw_left = (width - draw_width) / 2;
    session->draw_top = (height - draw_height) / 2;
    session->step_x = (uint32_t)(((uint64_t)screen_width << 16) / (uint64_t)draw_width);
    session->step_y = (uint32_t)(((uint64_t)screen_height << 16) / (uint64_t)draw_height);
}

static void render_frame(native_session *session) {
    (void)pthread_mutex_lock(&session->mutex);
    ANativeWindow *window = session->window;
    if (window != NULL) {
        ANativeWindow_acquire(window);
    }
    (void)pthread_mutex_unlock(&session->mutex);
    if (window == NULL) {
        return;
    }

    ANativeWindow_Buffer buffer;
    if (ANativeWindow_lock(window, &buffer, NULL) != 0) {
        ANativeWindow_release(window);
        return;
    }
    /* Solo se dibuja en buffers de 32 bpp RGBA/RGBX con geometría válida; con cualquier otro formato
     * (el sistema puede ignorar setBuffersGeometry) se publica el buffer sin escribirlo. */
    if ((buffer.format != WINDOW_FORMAT_RGBA_8888 && buffer.format != WINDOW_FORMAT_RGBX_8888) ||
        buffer.bits == NULL || buffer.width <= 0 || buffer.height <= 0 || buffer.stride < buffer.width) {
        (void)ANativeWindow_unlockAndPost(window);
        ANativeWindow_release(window);
        return;
    }
    uint32_t *destination = buffer.bits;
    for (int y = 0; y < buffer.height; ++y) {
        for (int x = 0; x < buffer.stride; ++x) {
            destination[(size_t)y * (size_t)buffer.stride + (size_t)x] = 0xFF000000u;
        }
    }

    const uint32_t *source = core_framebuffer(session);
    if (source != NULL) {
        const int mode = atomic_load_explicit(&session->scale_mode, memory_order_relaxed);
        if (session->layout_width != buffer.width || session->layout_height != buffer.height ||
            session->layout_mode != mode) {
            compute_layout(session, buffer.width, buffer.height, mode);
        }
        const int draw_width = session->draw_width;
        const int draw_height = session->draw_height;
        const size_t source_stride = (size_t)session->screen_width;
        for (int y = 0; y < draw_height; ++y) {
            const uint32_t source_y = ((uint32_t)y * session->step_y) >> 16;
            const uint32_t *source_row = source + (size_t)source_y * source_stride;
            uint32_t *row = destination + (size_t)(session->draw_top + y) * (size_t)buffer.stride +
                (size_t)session->draw_left;
            for (int x = 0; x < draw_width; ++x) {
                row[x] = source_row[((uint32_t)x * session->step_x) >> 16];
            }
        }
    }
    (void)ANativeWindow_unlockAndPost(window);
    ANativeWindow_release(window);
}

/* Hilo nativo, con `mutex` tomado: atiende una petición de instantánea de la SRAM. core_sram_save solo copia
 * memoria (sin malloc) hacia el búfer reservado en load, que mide el mayor .sav posible. */
static void serve_snapshot_locked(native_session *session) {
    if (!session->snapshot_requested) return;
    session->snapshot_length = 0u;
    session->snapshot_result = GB_ERR_NO_ROM;
    if (session->sram_snapshot != NULL) {
        session->snapshot_result = core_sram_save(session, session->sram_snapshot, session->sram_capacity);
        if (session->snapshot_result == NS_OK) session->snapshot_length = core_sram_size(session);
    }
    session->snapshot_requested = false;
    session->snapshots_served += 1u;
    (void)pthread_cond_broadcast(&session->condition);
}

static void *run_session(void *context) {
    native_session *session = context;
    struct timespec deadline;
    (void)clock_gettime(CLOCK_MONOTONIC, &deadline);

    for (;;) {
        (void)pthread_mutex_lock(&session->mutex);
        while (session->pause_requested && !session->stop_requested) {
            session->state = NATIVE_SESSION_PAUSED;
            (void)pthread_cond_broadcast(&session->condition);
            (void)pthread_cond_wait(&session->condition, &session->mutex);
            (void)clock_gettime(CLOCK_MONOTONIC, &deadline);
        }
        if (session->stop_requested) {
            session->state = NATIVE_SESSION_STOPPED;
            (void)pthread_cond_broadcast(&session->condition);
            (void)pthread_mutex_unlock(&session->mutex);
            return NULL;
        }
        session->state = NATIVE_SESSION_RUNNING;
        const uint16_t buttons = session->touch_buttons | session->physical_buttons;
        const unsigned speed = session->speed;
        const bool timing_reset = session->timing_reset;
        session->timing_reset = false;
        (void)pthread_mutex_unlock(&session->mutex);

        if (timing_reset) {
            (void)clock_gettime(CLOCK_MONOTONIC, &deadline);
        }
        sync_audio_speed(session, speed);
        core_set_buttons(session, buttons);
        const int palette = atomic_exchange_explicit(&session->pending_palette, -1, memory_order_acq_rel);
        if (palette >= 0 && session->core != NULL) gb_set_compat_palette(session->core, (uint8_t)palette);
        core_run_frame(session);
        /* Solo este hilo toca el core mientras corre: se lee y se limpia el flag aquí, sin bloquear. */
        const bool sram_dirty = core_sram_dirty(session);
        if (sram_dirty) core_sram_clear_dirty(session);
        /* GBA: la primera DMA a una EEPROM sin tamaño confirmado lo fija (512 B u 8 KiB). */
        const size_t sram_size = session->gba_eeprom_auto ? core_sram_size(session) : 0u;
        produce_audio(session, speed);
        render_frame(session);

        (void)pthread_mutex_lock(&session->mutex);
        session->applied_buttons = buttons;
        session->frames += 1u;
        if (sram_dirty) session->sram_dirty_seq += 1u;
        if (session->gba_eeprom_auto) session->sram_size = sram_size;
        serve_snapshot_locked(session);
        const enum native_audio_state audio_state = session->audio_state;
        (void)pthread_mutex_unlock(&session->mutex);

        if (speed == 1u && audio_state == NATIVE_AUDIO_LIVE) {
            pace_from_audio(session);
            (void)clock_gettime(CLOCK_MONOTONIC, &deadline);
        } else if (audio_state != NATIVE_AUDIO_PRIMING) {
            deadline.tv_nsec += FRAME_NANOS / (long)speed;
            if (deadline.tv_nsec >= 1000000000L) {
                deadline.tv_sec += 1;
                deadline.tv_nsec -= 1000000000L;
            }
            (void)clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &deadline, NULL);
        }
    }
}

native_session *native_session_create_console(int console) {
    if (console != NATIVE_CONSOLE_GB && console != NATIVE_CONSOLE_GBA) {
        return NULL;
    }
    native_session *session = calloc(1u, sizeof(*session));
    if (session == NULL) {
        return NULL;
    }
    session->console = (enum native_console)console;
    if (console == NATIVE_CONSOLE_GBA) {
        session->gba_core = gba_create();
        session->screen_width = GBA_SCREEN_W;
        session->screen_height = GBA_SCREEN_H;
        session->button_mask = 0x03FFu;
    } else {
        session->core = gb_create();
        session->screen_width = GB_SCREEN_W;
        session->screen_height = GB_SCREEN_H;
        session->button_mask = 0x00FFu;
    }
    if ((session->core == NULL && session->gba_core == NULL) || pthread_mutex_init(&session->mutex, NULL) != 0) {
        core_destroy(session);
        free(session);
        return NULL;
    }
    /* Reloj monotónico: la espera acotada de la instantánea no debe depender de cambios de hora. */
    pthread_condattr_t attr;
    if (pthread_condattr_init(&attr) != 0) {
        (void)pthread_mutex_destroy(&session->mutex);
        core_destroy(session);
        free(session);
        return NULL;
    }
    (void)pthread_condattr_setclock(&attr, CLOCK_MONOTONIC);
    const int cond_result = pthread_cond_init(&session->condition, &attr);
    (void)pthread_condattr_destroy(&attr);
    if (cond_result != 0) {
        (void)pthread_mutex_destroy(&session->mutex);
        core_destroy(session);
        free(session);
        return NULL;
    }
    session->state = NATIVE_SESSION_NEW;
    session->speed = 1u;
    atomic_init(&session->scale_mode, NATIVE_SCALE_INTEGER);
    atomic_init(&session->pending_palette, -1);
    atomic_init(&session->current_palette, 0);
    audio_ring_init(&session->audio_ring_buffer);
    audio_output_init(&session->audio, &session->audio_ring_buffer);
    session->audio_state = NATIVE_AUDIO_STOPPED;
    return session;
}

native_session *native_session_create(void) {
    return native_session_create_console(NATIVE_CONSOLE_GB);
}

void native_session_destroy(native_session *session) {
    if (session == NULL) {
        return;
    }
    (void)native_session_stop(session);
    audio_output_stop(&session->audio);
    native_session_set_window(session, NULL);
    core_destroy(session);
    free(session->sram_snapshot);
    (void)pthread_cond_destroy(&session->condition);
    (void)pthread_mutex_destroy(&session->mutex);
    free(session);
}

int native_session_console(native_session *session) {
    return session == NULL ? -1 : (int)session->console;
}

int native_session_screen_width(native_session *session) {
    return session == NULL ? 0 : session->screen_width;
}

int native_session_screen_height(native_session *session) {
    return session == NULL ? 0 : session->screen_height;
}

gb_result native_session_load(
    native_session *session,
    const uint8_t *rom,
    size_t length,
    const gb_options *options
) {
    if (session == NULL || rom == NULL || options == NULL || session->console != NATIVE_CONSOLE_GB) {
        return GB_ERR_NULL_ARG;
    }
    (void)pthread_mutex_lock(&session->mutex);
    const bool can_load = !session->thread_started;
    (void)pthread_mutex_unlock(&session->mutex);
    if (!can_load) {
        return GB_ERR_NULL_ARG;
    }
    gb_result result = gb_load_rom(session->core, rom, length, options);
    if (result == GB_OK) {
        const size_t sram_size = gb_sram_save_size(session->core);
        uint8_t *snapshot = malloc(sram_size > 0u ? sram_size : 1u);
        if (snapshot == NULL) {
            return GB_ERR_OUT_OF_MEMORY;
        }
        (void)pthread_mutex_lock(&session->mutex);
        free(session->sram_snapshot);
        session->sram_snapshot = snapshot;
        session->sram_size = sram_size;
        session->sram_capacity = sram_size;
        session->snapshot_length = 0u;
        session->sram_dirty_seq = 0u;
        session->snapshot_requested = false;
        gb_rom_info loaded;
        session->cgb_compat = gb_rom_info_get(session->core, &loaded) == GB_OK && loaded.cgb_compat;
        atomic_store_explicit(&session->current_palette, (int)options->compat_palette, memory_order_relaxed);
        atomic_store_explicit(&session->pending_palette, -1, memory_order_relaxed);
        session->state = NATIVE_SESSION_READY;
        session->frames = 0u;
        (void)pthread_mutex_unlock(&session->mutex);
    }
    return result;
}

/* Deja el núcleo GBA como recién creado (sin ROM ni BIOS) tras una carga fallida. */
static void reset_gba_core(native_session *session) {
    gba *fresh = gba_create();
    if (fresh == NULL) return;
    (void)pthread_mutex_lock(&session->mutex);
    gba *previous = session->gba_core;
    session->gba_core = fresh;
    (void)pthread_mutex_unlock(&session->mutex);
    gba_destroy(previous);
}

int native_session_load_gba(
    native_session *session,
    const uint8_t *rom,
    size_t length,
    const uint8_t *bios,
    size_t bios_length,
    const gba_options *options
) {
    if (session == NULL || rom == NULL || options == NULL || (bios == NULL && bios_length > 0u)) {
        return GB_ERR_NULL_ARG;
    }
    if (session->console != NATIVE_CONSOLE_GBA) {
        return NS_ERR_INVALID_ARGUMENT;
    }
    (void)pthread_mutex_lock(&session->mutex);
    const bool can_load = !session->thread_started;
    (void)pthread_mutex_unlock(&session->mutex);
    if (!can_load) {
        return GB_ERR_NULL_ARG;
    }
    gba *core = session->gba_core;
    gba_result result = GBA_OK;
    /* La BIOS del usuario solo si es la oficial (regla dura 1: nunca viene en la app); si no, HLE. */
    if (native_gba_bios_is_official(bios, bios_length)) {
        result = gba_load_bios(core, bios, bios_length);
    }
    if (result == GBA_OK) result = gba_load_rom(core, rom, length, options);
    gba_rom_info info;
    if (result == GBA_OK) result = gba_rom_info_get(core, &info);
    if (result != GBA_OK) {
        reset_gba_core(session);
        return native_result_from_gba(result);
    }
    const bool eeprom = info.save_type == GBA_SAVE_EEPROM512 || info.save_type == GBA_SAVE_EEPROM8K;
    const bool fixed = eeprom &&
        (options->save_type == GBA_SAVE_EEPROM512 || options->save_type == GBA_SAVE_EEPROM8K);
    const size_t media_capacity = eeprom && !fixed ? (size_t)GBA_EEPROM_MAX_BYTES : gba_save_size(core);
    const size_t capacity = media_capacity + (info.has_rtc ? (size_t)GBA_RTC_BYTES : 0u);
    uint8_t *snapshot = malloc(capacity > 0u ? capacity : 1u);
    if (snapshot == NULL) {
        reset_gba_core(session);
        return GB_ERR_OUT_OF_MEMORY;
    }
    (void)pthread_mutex_lock(&session->mutex);
    free(session->sram_snapshot);
    session->sram_snapshot = snapshot;
    session->gba_has_rtc = info.has_rtc;
    session->gba_eeprom_auto = eeprom && !fixed;
    session->gba_eeprom_fixed = fixed;
    session->sram_capacity = capacity;
    session->sram_size = core_sram_size(session);
    session->snapshot_length = 0u;
    session->sram_dirty_seq = 0u;
    session->snapshot_requested = false;
    session->cgb_compat = false;
    atomic_store_explicit(&session->current_palette, 0, memory_order_relaxed);
    atomic_store_explicit(&session->pending_palette, -1, memory_order_relaxed);
    session->state = NATIVE_SESSION_READY;
    session->frames = 0u;
    (void)pthread_mutex_unlock(&session->mutex);
    return NS_OK;
}

int native_session_start(native_session *session) {
    if (session == NULL) {
        return 1;
    }
    (void)pthread_mutex_lock(&session->mutex);
    if (session->thread_started || session->state != NATIVE_SESSION_READY) {
        (void)pthread_mutex_unlock(&session->mutex);
        return 1;
    }
    session->stop_requested = false;
    session->pause_requested = false;
    if (pthread_create(&session->thread, NULL, run_session, session) != 0) {
        (void)pthread_mutex_unlock(&session->mutex);
        return 2;
    }
    session->thread_started = true;
    session->state = NATIVE_SESSION_RUNNING;
    session->audio_state = NATIVE_AUDIO_PRIMING;
    (void)pthread_mutex_unlock(&session->mutex);
    return 0;
}

int native_session_pause(native_session *session) {
    if (session == NULL) {
        return 1;
    }
    (void)pthread_mutex_lock(&session->mutex);
    if (!session->thread_started || session->state != NATIVE_SESSION_RUNNING) {
        (void)pthread_mutex_unlock(&session->mutex);
        return 1;
    }
    session->pause_requested = true;
    while (session->state != NATIVE_SESSION_PAUSED && session->thread_started) {
        (void)pthread_cond_wait(&session->condition, &session->mutex);
    }
    (void)pthread_mutex_unlock(&session->mutex);
    audio_output_stop(&session->audio);
    audio_ring_clear(&session->audio_ring_buffer);
    (void)pthread_mutex_lock(&session->mutex);
    session->audio_state = NATIVE_AUDIO_STOPPED;
    (void)pthread_mutex_unlock(&session->mutex);
    return 0;
}

int native_session_resume(native_session *session) {
    if (session == NULL) {
        return 1;
    }
    (void)pthread_mutex_lock(&session->mutex);
    if (!session->thread_started || session->state != NATIVE_SESSION_PAUSED) {
        (void)pthread_mutex_unlock(&session->mutex);
        return 1;
    }
    /* El hilo está aparcado en la espera de pausa: este es el único momento seguro para adelantar el RTC
     * lo que pasó con la partida en pausa (GB: gb_rtc_set_time nunca retrocede ni toca un reloj detenido;
     * GBA: el RTC vuelve a la hora local del dispositivo más el desplazamiento que fijó el juego). */
    core_rtc_set_time(session, (int64_t)time(NULL));
    session->pause_requested = false;
    session->state = NATIVE_SESSION_RUNNING;
    session->audio_state = NATIVE_AUDIO_PRIMING;
    (void)pthread_cond_broadcast(&session->condition);
    (void)pthread_mutex_unlock(&session->mutex);
    return 0;
}

int native_session_stop(native_session *session) {
    if (session == NULL) {
        return 1;
    }
    (void)pthread_mutex_lock(&session->mutex);
    if (!session->thread_started) {
        if (session->state == NATIVE_SESSION_NEW || session->state == NATIVE_SESSION_READY) {
            session->state = NATIVE_SESSION_STOPPED;
        }
        (void)pthread_mutex_unlock(&session->mutex);
        return 0;
    }
    session->stop_requested = true;
    session->pause_requested = false;
    (void)pthread_cond_broadcast(&session->condition);
    (void)pthread_mutex_unlock(&session->mutex);
    (void)pthread_join(session->thread, NULL);
    audio_output_stop(&session->audio);
    audio_ring_clear(&session->audio_ring_buffer);
    (void)pthread_mutex_lock(&session->mutex);
    session->thread_started = false;
    session->state = NATIVE_SESSION_STOPPED;
    session->audio_state = NATIVE_AUDIO_STOPPED;
    (void)pthread_mutex_unlock(&session->mutex);
    return 0;
}

enum native_session_state native_session_get_state(native_session *session) {
    if (session == NULL) {
        return NATIVE_SESSION_STOPPED;
    }
    (void)pthread_mutex_lock(&session->mutex);
    const enum native_session_state state = session->state;
    (void)pthread_mutex_unlock(&session->mutex);
    return state;
}

uint64_t native_session_frame_count(native_session *session) {
    if (session == NULL) {
        return 0u;
    }
    (void)pthread_mutex_lock(&session->mutex);
    const uint64_t frames = session->frames;
    (void)pthread_mutex_unlock(&session->mutex);
    return frames;
}

void native_session_set_touch_buttons(native_session *session, uint16_t mask) {
    if (session == NULL) return;
    (void)pthread_mutex_lock(&session->mutex);
    session->touch_buttons = mask & session->button_mask;
    (void)pthread_mutex_unlock(&session->mutex);
}

void native_session_set_physical_buttons(native_session *session, uint16_t mask) {
    if (session == NULL) return;
    (void)pthread_mutex_lock(&session->mutex);
    session->physical_buttons = mask & session->button_mask;
    (void)pthread_mutex_unlock(&session->mutex);
}

uint16_t native_session_requested_buttons(native_session *session) {
    if (session == NULL) return 0u;
    (void)pthread_mutex_lock(&session->mutex);
    const uint16_t buttons = session->touch_buttons | session->physical_buttons;
    (void)pthread_mutex_unlock(&session->mutex);
    return buttons;
}

uint16_t native_session_applied_buttons(native_session *session) {
    if (session == NULL) return 0u;
    (void)pthread_mutex_lock(&session->mutex);
    const uint16_t buttons = session->applied_buttons;
    (void)pthread_mutex_unlock(&session->mutex);
    return buttons;
}

void native_session_set_speed(native_session *session, unsigned speed) {
    if (session == NULL) return;
    const unsigned normalized = speed == 2u || speed == 4u ? speed : 1u;
    (void)pthread_mutex_lock(&session->mutex);
    if (session->speed != normalized) {
        session->speed = normalized;
        session->timing_reset = true;
    }
    (void)pthread_mutex_unlock(&session->mutex);
}

int native_session_set_compat_palette(native_session *session, int id) {
    if (session == NULL) return NS_INVALID;
    if (id < 0 || id > (int)GB_COMPAT_PALETTES) return NS_INVALID;
    (void)pthread_mutex_lock(&session->mutex);
    const bool compat = session->cgb_compat;
    const bool started = session->thread_started;
    if (compat && !started) gb_set_compat_palette(session->core, (uint8_t)id);
    (void)pthread_mutex_unlock(&session->mutex);
    if (!compat) return NS_NOT_COMPAT;
    atomic_store_explicit(&session->current_palette, id, memory_order_relaxed);
    /* Con el hilo en marcha (o en pausa) lo aplica entre frames, sin tocar el core desde aquí. */
    if (started) atomic_store_explicit(&session->pending_palette, id, memory_order_release);
    return NS_OK;
}

int native_session_compat_palette(native_session *session) {
    if (session == NULL) return 0;
    return atomic_load_explicit(&session->current_palette, memory_order_relaxed);
}

void native_session_set_volume(native_session *session, float gain) {
    if (session == NULL) return;
    audio_output_set_volume(&session->audio, gain);
}

float native_session_volume(native_session *session) {
    if (session == NULL) return 1.0f;
    return audio_output_volume(&session->audio);
}

void native_session_set_scale_mode(native_session *session, int mode) {
    if (session == NULL) return;
    if (mode != NATIVE_SCALE_INTEGER && mode != NATIVE_SCALE_FILL) return;
    atomic_store_explicit(&session->scale_mode, mode, memory_order_relaxed);
}

int native_session_scale_mode(native_session *session) {
    if (session == NULL) return NATIVE_SCALE_INTEGER;
    return atomic_load_explicit(&session->scale_mode, memory_order_relaxed);
}

unsigned native_session_speed(native_session *session) {
    if (session == NULL) return 1u;
    (void)pthread_mutex_lock(&session->mutex);
    const unsigned speed = session->speed;
    (void)pthread_mutex_unlock(&session->mutex);
    return speed;
}

enum native_audio_state native_session_audio_state(native_session *session) {
    if (session == NULL) return NATIVE_AUDIO_STOPPED;
    (void)pthread_mutex_lock(&session->mutex);
    const enum native_audio_state state = session->audio_state;
    (void)pthread_mutex_unlock(&session->mutex);
    return state;
}

uint64_t native_session_audio_frames_produced(native_session *session) {
    if (session == NULL) return 0u;
    return atomic_load_explicit(&session->audio_ring_buffer.total_written, memory_order_relaxed);
}

uint64_t native_session_audio_frames_consumed(native_session *session) {
    if (session == NULL) return 0u;
    return atomic_load_explicit(&session->audio_ring_buffer.total_read, memory_order_relaxed);
}

void native_session_set_window(native_session *session, ANativeWindow *window) {
    if (session == NULL) {
        if (window != NULL) {
            ANativeWindow_release(window);
        }
        return;
    }
    if (window != NULL && ANativeWindow_setBuffersGeometry(window, 0, 0, WINDOW_FORMAT_RGBA_8888) != 0) {
        /* Sin formato RGBA garantizado no se dibuja: se descarta la ventana en vez de arriesgar escrituras. */
        ANativeWindow_release(window);
        window = NULL;
    }
    (void)pthread_mutex_lock(&session->mutex);
    ANativeWindow *previous = session->window;
    session->window = window;
    (void)pthread_mutex_unlock(&session->mutex);
    if (previous != NULL) {
        ANativeWindow_release(previous);
    }
}

/* ---- Partidas y estados ---- */

/* Con `mutex` tomado: ¿puede otro hilo tocar el core? (hilo sin arrancar, PAUSED o STOPPED) */
static bool parked_locked(const native_session *session) {
    if (!session->thread_started) {
        return session->state == NATIVE_SESSION_READY || session->state == NATIVE_SESSION_STOPPED;
    }
    return session->state == NATIVE_SESSION_PAUSED;
}

int native_session_rom_info(native_session *session, gb_rom_info *out) {
    if (session == NULL || out == NULL) return GB_ERR_NULL_ARG;
    if (session->console != NATIVE_CONSOLE_GB) return NS_INVALID;
    (void)pthread_mutex_lock(&session->mutex);
    int result = NS_BUSY;
    if (!session->thread_started && session->state == NATIVE_SESSION_READY) {
        result = (int)gb_rom_info_get(session->core, out);
    }
    (void)pthread_mutex_unlock(&session->mutex);
    return result;
}

int native_session_gba_rom_info(native_session *session, gba_rom_info *out, bool *eeprom_size_fixed) {
    if (session == NULL || out == NULL) return GB_ERR_NULL_ARG;
    if (session->console != NATIVE_CONSOLE_GBA) return NS_INVALID;
    (void)pthread_mutex_lock(&session->mutex);
    int result = NS_BUSY;
    if (!session->thread_started && session->state == NATIVE_SESSION_READY) {
        result = native_result_from_gba(gba_rom_info_get(session->gba_core, out));
        if (eeprom_size_fixed != NULL) *eeprom_size_fixed = session->gba_eeprom_fixed;
    }
    (void)pthread_mutex_unlock(&session->mutex);
    return result;
}

size_t native_session_sram_size(native_session *session) {
    if (session == NULL) return 0u;
    (void)pthread_mutex_lock(&session->mutex);
    const size_t size = session->sram_size;
    (void)pthread_mutex_unlock(&session->mutex);
    return size;
}

size_t native_session_sram_capacity(native_session *session) {
    if (session == NULL) return 0u;
    (void)pthread_mutex_lock(&session->mutex);
    const size_t capacity = session->sram_capacity;
    (void)pthread_mutex_unlock(&session->mutex);
    return capacity;
}

int native_session_sram_load(native_session *session, const uint8_t *data, size_t length) {
    if (session == NULL || (data == NULL && length > 0u)) return GB_ERR_NULL_ARG;
    (void)pthread_mutex_lock(&session->mutex);
    int result = NS_BUSY;
    const bool unstarted = !session->thread_started && session->state == NATIVE_SESSION_READY;
    const bool paused = session->thread_started && session->state == NATIVE_SESSION_PAUSED;
    if (unstarted || paused) {
        result = core_sram_load(session, data, length);
        /* GBA: un .sav de 8 KiB confirma una EEPROM sin ajuste. */
        if (is_gba(session)) session->sram_size = core_sram_size(session);
    }
    (void)pthread_mutex_unlock(&session->mutex);
    return result;
}

uint64_t native_session_sram_dirty_seq(native_session *session) {
    if (session == NULL) return 0u;
    (void)pthread_mutex_lock(&session->mutex);
    const uint64_t seq = session->sram_dirty_seq;
    (void)pthread_mutex_unlock(&session->mutex);
    return seq;
}

int native_session_sram_copy(native_session *session, uint8_t *out, size_t capacity, size_t *length) {
    if (length != NULL) *length = 0u;
    if (session == NULL || (out == NULL && capacity > 0u)) return GB_ERR_NULL_ARG;
    (void)pthread_mutex_lock(&session->mutex);
    if (session->sram_snapshot == NULL) {
        (void)pthread_mutex_unlock(&session->mutex);
        return GB_ERR_NO_ROM;
    }
    if (capacity < session->sram_capacity) {
        (void)pthread_mutex_unlock(&session->mutex);
        return GB_ERR_BUFFER_TOO_SMALL;
    }
    int result = NS_OK;
    size_t copied = 0u;
    if (session->thread_started && session->state == NATIVE_SESSION_RUNNING) {
        /* Corriendo: el hilo nativo es el único que puede leer el core. Se le pide una instantánea; si la
         * sesión se aparca o detiene mientras tanto, el core ya no avanza y se copia directo. */
        struct timespec deadline;
        (void)clock_gettime(CLOCK_MONOTONIC, &deadline);
        deadline.tv_nsec += SNAPSHOT_TIMEOUT_NANOS;
        if (deadline.tv_nsec >= 1000000000L) {
            deadline.tv_sec += 1;
            deadline.tv_nsec -= 1000000000L;
        }
        session->snapshot_requested = true;
        const uint64_t wanted = session->snapshots_served + 1u;
        while (session->snapshots_served < wanted &&
               session->thread_started && session->state == NATIVE_SESSION_RUNNING) {
            if (pthread_cond_timedwait(&session->condition, &session->mutex, &deadline) == ETIMEDOUT) {
                break;
            }
        }
        if (session->snapshots_served >= wanted) {
            /* La longitud es la de esa instantánea: el siguiente frame puede haber cambiado sram_size. */
            result = session->snapshot_result;
            if (result == NS_OK) {
                copied = session->snapshot_length;
                if (copied > 0u) memcpy(out, session->sram_snapshot, copied);
            }
        } else if (session->thread_started && session->state == NATIVE_SESSION_RUNNING) {
            result = NS_TIMEOUT;
        } else {
            result = core_sram_save(session, out, capacity);
            if (result == NS_OK) copied = core_sram_size(session);
        }
    } else {
        result = core_sram_save(session, out, capacity);
        if (result == NS_OK) copied = core_sram_size(session);
    }
    (void)pthread_mutex_unlock(&session->mutex);
    if (length != NULL) *length = copied;
    return result;
}

int native_session_state_size(native_session *session, size_t *out) {
    if (session == NULL || out == NULL) return GB_ERR_NULL_ARG;
    (void)pthread_mutex_lock(&session->mutex);
    int result = NS_BUSY;
    if (parked_locked(session)) {
        *out = core_state_size(session);
        result = *out == 0u ? GB_ERR_NO_ROM : NS_OK;
    }
    (void)pthread_mutex_unlock(&session->mutex);
    return result;
}

int native_session_state_save(native_session *session, uint8_t *out, size_t capacity) {
    if (session == NULL || out == NULL) return GB_ERR_NULL_ARG;
    (void)pthread_mutex_lock(&session->mutex);
    int result = NS_BUSY;
    if (parked_locked(session)) {
        result = core_state_save(session, out, capacity);
    }
    (void)pthread_mutex_unlock(&session->mutex);
    return result;
}

int native_session_state_load(native_session *session, const uint8_t *data, size_t length) {
    if (session == NULL || data == NULL) return GB_ERR_NULL_ARG;
    (void)pthread_mutex_lock(&session->mutex);
    int result = NS_BUSY;
    if (parked_locked(session)) {
        result = core_state_load(session, data, length);
        /* GBA: el estado trae el medio de ese momento, con su tamaño de EEPROM confirmado. */
        if (is_gba(session)) session->sram_size = core_sram_size(session);
    }
    (void)pthread_mutex_unlock(&session->mutex);
    return result;
}

int native_session_copy_framebuffer(native_session *session, uint32_t *out, size_t pixel_capacity) {
    if (session == NULL || out == NULL) return GB_ERR_NULL_ARG;
    const size_t pixels = (size_t)session->screen_width * (size_t)session->screen_height;
    if (pixel_capacity < pixels) return GB_ERR_BUFFER_TOO_SMALL;
    (void)pthread_mutex_lock(&session->mutex);
    int result = NS_BUSY;
    if (parked_locked(session)) {
        const uint32_t *frame = core_framebuffer(session);
        if (frame == NULL) {
            result = GB_ERR_NO_ROM;
        } else {
            memcpy(out, frame, pixels * sizeof(uint32_t));
            result = NS_OK;
        }
    }
    (void)pthread_mutex_unlock(&session->mutex);
    return result;
}
