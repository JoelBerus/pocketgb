#include "native_session.h"

#include <stdbool.h>
#include <stdlib.h>
#include <time.h>

#include <pthread.h>
#include <android/native_window.h>

#include "audio_output.h"
#include "audio_ring.h"

#define FRAME_NANOS 16742706L

struct native_session {
    gb *core;
    pthread_t thread;
    pthread_mutex_t mutex;
    pthread_cond_t condition;
    enum native_session_state state;
    bool thread_started;
    bool pause_requested;
    bool stop_requested;
    uint64_t frames;
    uint8_t touch_buttons;
    uint8_t physical_buttons;
    uint8_t applied_buttons;
    unsigned speed;
    bool timing_reset;
    audio_ring audio_ring_buffer;
    audio_output audio;
    enum native_audio_state audio_state;
    int16_t audio_scratch[2048];
    ANativeWindow *window;
};

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
    size_t available = gb_audio_available(session->core);
    while (available > 0u) {
        const size_t requested = available < 1024u ? available : 1024u;
        const size_t frames = gb_audio_read(session->core, session->audio_scratch, requested);
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

    const uint32_t *source = gb_framebuffer(session->core);
    if (source != NULL && buffer.width > 0 && buffer.height > 0) {
        const int integer_scale = buffer.width / GB_SCREEN_W < buffer.height / GB_SCREEN_H
            ? buffer.width / GB_SCREEN_W
            : buffer.height / GB_SCREEN_H;
        int draw_width;
        int draw_height;
        if (integer_scale >= 1) {
            draw_width = GB_SCREEN_W * integer_scale;
            draw_height = GB_SCREEN_H * integer_scale;
        } else if ((int64_t)buffer.width * GB_SCREEN_H <= (int64_t)buffer.height * GB_SCREEN_W) {
            draw_width = buffer.width;
            draw_height = buffer.width * GB_SCREEN_H / GB_SCREEN_W;
        } else {
            draw_height = buffer.height;
            draw_width = buffer.height * GB_SCREEN_W / GB_SCREEN_H;
        }
        if (draw_width < 1) draw_width = 1;
        if (draw_height < 1) draw_height = 1;
        const int left = (buffer.width - draw_width) / 2;
        const int top = (buffer.height - draw_height) / 2;
        for (int y = 0; y < draw_height; ++y) {
            const int source_y = y * GB_SCREEN_H / draw_height;
            uint32_t *row = destination + (size_t)(top + y) * (size_t)buffer.stride + (size_t)left;
            for (int x = 0; x < draw_width; ++x) {
                const int source_x = x * GB_SCREEN_W / draw_width;
                row[x] = source[(size_t)source_y * GB_SCREEN_W + (size_t)source_x];
            }
        }
    }
    (void)ANativeWindow_unlockAndPost(window);
    ANativeWindow_release(window);
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
        const uint8_t buttons = session->touch_buttons | session->physical_buttons;
        const unsigned speed = session->speed;
        const bool timing_reset = session->timing_reset;
        session->timing_reset = false;
        (void)pthread_mutex_unlock(&session->mutex);

        if (timing_reset) {
            (void)clock_gettime(CLOCK_MONOTONIC, &deadline);
        }
        sync_audio_speed(session, speed);
        gb_set_buttons(session->core, buttons);
        gb_run_frame(session->core);
        produce_audio(session, speed);
        render_frame(session);

        (void)pthread_mutex_lock(&session->mutex);
        session->applied_buttons = buttons;
        session->frames += 1u;
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

native_session *native_session_create(void) {
    native_session *session = calloc(1u, sizeof(*session));
    if (session == NULL) {
        return NULL;
    }
    session->core = gb_create();
    if (session->core == NULL || pthread_mutex_init(&session->mutex, NULL) != 0) {
        gb_destroy(session->core);
        free(session);
        return NULL;
    }
    if (pthread_cond_init(&session->condition, NULL) != 0) {
        (void)pthread_mutex_destroy(&session->mutex);
        gb_destroy(session->core);
        free(session);
        return NULL;
    }
    session->state = NATIVE_SESSION_NEW;
    session->speed = 1u;
    audio_ring_init(&session->audio_ring_buffer);
    audio_output_init(&session->audio, &session->audio_ring_buffer);
    session->audio_state = NATIVE_AUDIO_STOPPED;
    return session;
}

void native_session_destroy(native_session *session) {
    if (session == NULL) {
        return;
    }
    (void)native_session_stop(session);
    audio_output_stop(&session->audio);
    native_session_set_window(session, NULL);
    gb_destroy(session->core);
    (void)pthread_cond_destroy(&session->condition);
    (void)pthread_mutex_destroy(&session->mutex);
    free(session);
}

gb_result native_session_load(
    native_session *session,
    const uint8_t *rom,
    size_t length,
    const gb_options *options
) {
    if (session == NULL || rom == NULL || options == NULL) {
        return GB_ERR_NULL_ARG;
    }
    (void)pthread_mutex_lock(&session->mutex);
    const bool can_load = !session->thread_started;
    (void)pthread_mutex_unlock(&session->mutex);
    if (!can_load) {
        return GB_ERR_NULL_ARG;
    }
    const gb_result result = gb_load_rom(session->core, rom, length, options);
    if (result == GB_OK) {
        (void)pthread_mutex_lock(&session->mutex);
        session->state = NATIVE_SESSION_READY;
        session->frames = 0u;
        (void)pthread_mutex_unlock(&session->mutex);
    }
    return result;
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

void native_session_set_touch_buttons(native_session *session, uint8_t mask) {
    if (session == NULL) return;
    (void)pthread_mutex_lock(&session->mutex);
    session->touch_buttons = mask;
    (void)pthread_mutex_unlock(&session->mutex);
}

void native_session_set_physical_buttons(native_session *session, uint8_t mask) {
    if (session == NULL) return;
    (void)pthread_mutex_lock(&session->mutex);
    session->physical_buttons = mask;
    (void)pthread_mutex_unlock(&session->mutex);
}

uint8_t native_session_requested_buttons(native_session *session) {
    if (session == NULL) return 0u;
    (void)pthread_mutex_lock(&session->mutex);
    const uint8_t buttons = session->touch_buttons | session->physical_buttons;
    (void)pthread_mutex_unlock(&session->mutex);
    return buttons;
}

uint8_t native_session_applied_buttons(native_session *session) {
    if (session == NULL) return 0u;
    (void)pthread_mutex_lock(&session->mutex);
    const uint8_t buttons = session->applied_buttons;
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
