#include "native_session.h"

#include <stdbool.h>
#include <stdlib.h>
#include <time.h>

#include <pthread.h>

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
};

static void advance_deadline(struct timespec *deadline) {
    deadline->tv_nsec += FRAME_NANOS;
    if (deadline->tv_nsec >= 1000000000L) {
        deadline->tv_sec += 1;
        deadline->tv_nsec -= 1000000000L;
    }
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
        (void)pthread_mutex_unlock(&session->mutex);

        gb_run_frame(session->core);

        (void)pthread_mutex_lock(&session->mutex);
        session->frames += 1u;
        (void)pthread_mutex_unlock(&session->mutex);

        advance_deadline(&deadline);
        (void)clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &deadline, NULL);
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
    return session;
}

void native_session_destroy(native_session *session) {
    if (session == NULL) {
        return;
    }
    (void)native_session_stop(session);
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
    (void)pthread_mutex_lock(&session->mutex);
    session->thread_started = false;
    session->state = NATIVE_SESSION_STOPPED;
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
