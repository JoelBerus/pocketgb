#include <jni.h>
#include <stdint.h>
#include <string.h>
#include <android/native_window_jni.h>

#include "pocketgb.h"
#include "native_session.h"

JNIEXPORT jlong JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeCreate(
    JNIEnv *env,
    jclass clazz
) {
    (void)env;
    (void)clazz;
    return (jlong)(uintptr_t)gb_create();
}

JNIEXPORT void JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeDestroy(
    JNIEnv *env,
    jclass clazz,
    jlong handle
) {
    (void)env;
    (void)clazz;
    gb_destroy((gb *)(uintptr_t)handle);
}

static gb *core_from_handle(jlong handle) {
    return (gb *)(uintptr_t)handle;
}

static gb_result read_rom_info(jlong handle, gb_rom_info *info) {
    gb *core = core_from_handle(handle);
    if (core == NULL || info == NULL) {
        return GB_ERR_NULL_ARG;
    }
    return gb_rom_info_get(core, info);
}

JNIEXPORT jint JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeLoadRom(
    JNIEnv *env,
    jclass clazz,
    jlong handle,
    jbyteArray rom,
    jint model,
    jint sample_rate,
    jlong unix_time,
    jint compat_palette
) {
    (void)clazz;
    gb *core = core_from_handle(handle);
    if (core == NULL || rom == NULL) {
        return GB_ERR_NULL_ARG;
    }
    const jsize length = (*env)->GetArrayLength(env, rom);
    jbyte *bytes = (*env)->GetByteArrayElements(env, rom, NULL);
    if (bytes == NULL) {
        return GB_ERR_OUT_OF_MEMORY;
    }
    gb_options options;
    gb_options_default(&options);
    options.model = (gb_model)model;
    options.sample_rate = sample_rate < 0 ? 0u : (uint32_t)sample_rate;
    options.unix_time = (int64_t)unix_time;
    options.compat_palette = (uint8_t)compat_palette;
    const gb_result result = gb_load_rom(
        core,
        (const uint8_t *)bytes,
        (size_t)length,
        &options
    );
    (*env)->ReleaseByteArrayElements(env, rom, bytes, JNI_ABORT);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeRomTitle(
    JNIEnv *env,
    jclass clazz,
    jlong handle
) {
    (void)clazz;
    gb_rom_info info;
    if (read_rom_info(handle, &info) != GB_OK) {
        return (*env)->NewStringUTF(env, "");
    }
    return (*env)->NewStringUTF(env, info.title);
}

JNIEXPORT jintArray JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeRomMetadata(
    JNIEnv *env,
    jclass clazz,
    jlong handle
) {
    (void)clazz;
    gb_rom_info info;
    jint values[10] = {0};
    if (read_rom_info(handle, &info) == GB_OK) {
        values[0] = info.cgb_flag;
        values[1] = info.cart_type;
        values[2] = (jint)info.rom_bytes;
        values[3] = (jint)info.sram_bytes;
        values[4] = info.has_battery;
        values[5] = info.has_rtc;
        values[6] = info.header_checksum_ok;
        values[7] = info.global_checksum_ok;
        values[8] = info.cgb_mode;
        values[9] = info.cgb_compat;
    }
    jintArray result = (*env)->NewIntArray(env, 10);
    if (result != NULL) {
        (*env)->SetIntArrayRegion(env, result, 0, 10, values);
    }
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeRomFingerprint(
    JNIEnv *env,
    jclass clazz,
    jlong handle
) {
    (void)clazz;
    gb_rom_info info;
    memset(&info, 0, sizeof(info));
    (void)read_rom_info(handle, &info);
    jbyteArray result = (*env)->NewByteArray(env, 32);
    if (result != NULL) {
        (*env)->SetByteArrayRegion(env, result, 0, 32, (const jbyte *)info.fingerprint);
    }
    return result;
}

JNIEXPORT void JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeRunFrame(
    JNIEnv *env,
    jclass clazz,
    jlong handle
) {
    (void)env;
    (void)clazz;
    gb_run_frame(core_from_handle(handle));
}

JNIEXPORT jint JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeCopyFrame(
    JNIEnv *env,
    jclass clazz,
    jlong handle,
    jintArray destination
) {
    (void)clazz;
    gb *core = core_from_handle(handle);
    if (core == NULL || destination == NULL) {
        return GB_ERR_NULL_ARG;
    }
    if ((*env)->GetArrayLength(env, destination) < GB_SCREEN_W * GB_SCREEN_H) {
        return GB_ERR_BUFFER_TOO_SMALL;
    }
    const uint32_t *frame = gb_framebuffer(core);
    if (frame == NULL) {
        return GB_ERR_NO_ROM;
    }
    jint *pixels = (*env)->GetPrimitiveArrayCritical(env, destination, NULL);
    if (pixels == NULL) {
        return GB_ERR_OUT_OF_MEMORY;
    }
    memcpy(pixels, frame, GB_SCREEN_W * GB_SCREEN_H * sizeof(uint32_t));
    (*env)->ReleasePrimitiveArrayCritical(env, destination, pixels, 0);
    return GB_OK;
}

JNIEXPORT jlong JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeSessionCreate(
    JNIEnv *env,
    jclass clazz
) {
    (void)env;
    (void)clazz;
    return (jlong)(uintptr_t)native_session_create();
}

JNIEXPORT void JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeSessionDestroy(
    JNIEnv *env,
    jclass clazz,
    jlong handle
) {
    (void)env;
    (void)clazz;
    native_session_destroy((native_session *)(uintptr_t)handle);
}

JNIEXPORT jint JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeSessionLoad(
    JNIEnv *env,
    jclass clazz,
    jlong handle,
    jbyteArray rom
) {
    (void)clazz;
    native_session *session = (native_session *)(uintptr_t)handle;
    if (session == NULL || rom == NULL) {
        return GB_ERR_NULL_ARG;
    }
    const jsize length = (*env)->GetArrayLength(env, rom);
    jbyte *bytes = (*env)->GetByteArrayElements(env, rom, NULL);
    if (bytes == NULL) {
        return GB_ERR_OUT_OF_MEMORY;
    }
    gb_options options;
    gb_options_default(&options);
    const gb_result result = native_session_load(
        session,
        (const uint8_t *)bytes,
        (size_t)length,
        &options
    );
    (*env)->ReleaseByteArrayElements(env, rom, bytes, JNI_ABORT);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeSessionStart(
    JNIEnv *env,
    jclass clazz,
    jlong handle
) {
    (void)env;
    (void)clazz;
    return native_session_start((native_session *)(uintptr_t)handle);
}

JNIEXPORT jint JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeSessionPause(
    JNIEnv *env,
    jclass clazz,
    jlong handle
) {
    (void)env;
    (void)clazz;
    return native_session_pause((native_session *)(uintptr_t)handle);
}

JNIEXPORT jint JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeSessionResume(
    JNIEnv *env,
    jclass clazz,
    jlong handle
) {
    (void)env;
    (void)clazz;
    return native_session_resume((native_session *)(uintptr_t)handle);
}

JNIEXPORT jint JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeSessionStop(
    JNIEnv *env,
    jclass clazz,
    jlong handle
) {
    (void)env;
    (void)clazz;
    return native_session_stop((native_session *)(uintptr_t)handle);
}

JNIEXPORT jlong JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeSessionFrameCount(
    JNIEnv *env,
    jclass clazz,
    jlong handle
) {
    (void)env;
    (void)clazz;
    return (jlong)native_session_frame_count((native_session *)(uintptr_t)handle);
}

JNIEXPORT void JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeSessionSetTouchButtons(
    JNIEnv *env, jclass clazz, jlong handle, jint mask
) {
    (void)env;
    (void)clazz;
    native_session_set_touch_buttons((native_session *)(uintptr_t)handle, (uint8_t)mask);
}

JNIEXPORT void JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeSessionSetPhysicalButtons(
    JNIEnv *env, jclass clazz, jlong handle, jint mask
) {
    (void)env;
    (void)clazz;
    native_session_set_physical_buttons((native_session *)(uintptr_t)handle, (uint8_t)mask);
}

JNIEXPORT jint JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeSessionRequestedButtons(
    JNIEnv *env, jclass clazz, jlong handle
) {
    (void)env;
    (void)clazz;
    return native_session_requested_buttons((native_session *)(uintptr_t)handle);
}

JNIEXPORT jint JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeSessionAppliedButtons(
    JNIEnv *env, jclass clazz, jlong handle
) {
    (void)env;
    (void)clazz;
    return native_session_applied_buttons((native_session *)(uintptr_t)handle);
}

JNIEXPORT void JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeSessionSetSpeed(
    JNIEnv *env, jclass clazz, jlong handle, jint speed
) {
    (void)env;
    (void)clazz;
    native_session_set_speed((native_session *)(uintptr_t)handle, (unsigned)speed);
}

JNIEXPORT jint JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeSessionSpeed(
    JNIEnv *env, jclass clazz, jlong handle
) {
    (void)env;
    (void)clazz;
    return (jint)native_session_speed((native_session *)(uintptr_t)handle);
}

JNIEXPORT void JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeSessionAttachSurface(
    JNIEnv *env,
    jclass clazz,
    jlong handle,
    jobject surface
) {
    (void)clazz;
    ANativeWindow *window = surface == NULL ? NULL : ANativeWindow_fromSurface(env, surface);
    native_session_set_window((native_session *)(uintptr_t)handle, window);
}

JNIEXPORT void JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeSessionDetachSurface(
    JNIEnv *env,
    jclass clazz,
    jlong handle
) {
    (void)env;
    (void)clazz;
    native_session_set_window((native_session *)(uintptr_t)handle, NULL);
}
