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

native_session *native_session_create(void);
void native_session_destroy(native_session *session);
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
void native_session_set_window(native_session *session, ANativeWindow *window);

#endif
