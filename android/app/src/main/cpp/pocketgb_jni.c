#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <android/native_window_jni.h>

#include "pocketgb.h"
#include "native_session.h"

/* gb_result no tiene "argumento inválido" (core/ no se toca): el puente usa el siguiente código libre
 * y CoreError.fromResult lo traduce a CoreError.InvalidArgument. */
#define JNI_ERR_INVALID_ARGUMENT 17

static bool options_valid(jint model, jint compat_palette) {
    return model >= (jint)GB_MODEL_AUTO && model <= (jint)GB_MODEL_CGB &&
        compat_palette >= 0 && compat_palette <= (jint)GB_COMPAT_PALETTES;
}

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
    if (!options_valid(model, compat_palette)) {
        return JNI_ERR_INVALID_ARGUMENT;
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
    jbyteArray rom,
    jlong unix_time,
    jint model,
    jint compat_palette
) {
    (void)clazz;
    native_session *session = (native_session *)(uintptr_t)handle;
    if (session == NULL || rom == NULL) {
        return GB_ERR_NULL_ARG;
    }
    if (!options_valid(model, compat_palette)) {
        return JNI_ERR_INVALID_ARGUMENT;
    }
    const jsize length = (*env)->GetArrayLength(env, rom);
    jbyte *bytes = (*env)->GetByteArrayElements(env, rom, NULL);
    if (bytes == NULL) {
        return GB_ERR_OUT_OF_MEMORY;
    }
    gb_options options;
    gb_options_default(&options);
    options.unix_time = (int64_t)unix_time;
    options.model = (gb_model)model;
    options.compat_palette = (uint8_t)compat_palette;
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
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeSessionSetCompatPalette(
    JNIEnv *env, jclass clazz, jlong handle, jint id
) {
    (void)env;
    (void)clazz;
    const int result = native_session_set_compat_palette((native_session *)(uintptr_t)handle, (int)id);
    return result == NS_INVALID ? JNI_ERR_INVALID_ARGUMENT : result;
}

JNIEXPORT jint JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeSessionCompatPalette(
    JNIEnv *env, jclass clazz, jlong handle
) {
    (void)env;
    (void)clazz;
    return native_session_compat_palette((native_session *)(uintptr_t)handle);
}

JNIEXPORT void JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeSessionSetVolume(
    JNIEnv *env, jclass clazz, jlong handle, jfloat gain
) {
    (void)env;
    (void)clazz;
    native_session_set_volume((native_session *)(uintptr_t)handle, (float)gain);
}

JNIEXPORT jfloat JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeSessionVolume(
    JNIEnv *env, jclass clazz, jlong handle
) {
    (void)env;
    (void)clazz;
    return native_session_volume((native_session *)(uintptr_t)handle);
}

JNIEXPORT void JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeSessionSetScaleMode(
    JNIEnv *env, jclass clazz, jlong handle, jint mode
) {
    (void)env;
    (void)clazz;
    native_session_set_scale_mode((native_session *)(uintptr_t)handle, (int)mode);
}

JNIEXPORT jint JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeSessionScaleMode(
    JNIEnv *env, jclass clazz, jlong handle
) {
    (void)env;
    (void)clazz;
    return native_session_scale_mode((native_session *)(uintptr_t)handle);
}

JNIEXPORT jint JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeSessionSpeed(
    JNIEnv *env, jclass clazz, jlong handle
) {
    (void)env;
    (void)clazz;
    return (jint)native_session_speed((native_session *)(uintptr_t)handle);
}

JNIEXPORT jint JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeSessionAudioState(
    JNIEnv *env, jclass clazz, jlong handle
) {
    (void)env;
    (void)clazz;
    return (jint)native_session_audio_state((native_session *)(uintptr_t)handle);
}

JNIEXPORT jlong JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeSessionAudioFramesProduced(
    JNIEnv *env, jclass clazz, jlong handle
) {
    (void)env;
    (void)clazz;
    return (jlong)native_session_audio_frames_produced((native_session *)(uintptr_t)handle);
}

JNIEXPORT jlong JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeSessionAudioFramesConsumed(
    JNIEnv *env, jclass clazz, jlong handle
) {
    (void)env;
    (void)clazz;
    return (jlong)native_session_audio_frames_consumed((native_session *)(uintptr_t)handle);
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

/* ---- Partidas y estados ----
 * Los datos del disco (.sav, .state) son entrada no confiable: se validan longitudes antes de reservar o
 * copiar, se trabaja sobre un búfer temporal nativo y nunca se llama a JNI con el mutex de la sesión tomado
 * (las funciones de native_session ya lo sueltan al volver). */

#define NS_SRAM_RTC_EXTRA 48u

static native_session *session_from_handle(jlong handle) {
    return (native_session *)(uintptr_t)handle;
}

JNIEXPORT jint JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeSessionRomInfo(
    JNIEnv *env,
    jclass clazz,
    jlong handle,
    jintArray ints,
    jbyteArray fingerprint,
    jbyteArray title
) {
    (void)clazz;
    native_session *session = session_from_handle(handle);
    if (session == NULL || ints == NULL || fingerprint == NULL || title == NULL) {
        return GB_ERR_NULL_ARG;
    }
    if ((*env)->GetArrayLength(env, ints) < 10 ||
        (*env)->GetArrayLength(env, fingerprint) < 32 ||
        (*env)->GetArrayLength(env, title) < 17) {
        return GB_ERR_BUFFER_TOO_SMALL;
    }
    gb_rom_info info;
    memset(&info, 0, sizeof(info));
    const int result = native_session_rom_info(session, &info);
    if (result != NS_OK) {
        return result;
    }
    jint values[10];
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
    (*env)->SetIntArrayRegion(env, ints, 0, 10, values);
    (*env)->SetByteArrayRegion(env, fingerprint, 0, 32, (const jbyte *)info.fingerprint);
    (*env)->SetByteArrayRegion(env, title, 0, 17, (const jbyte *)info.title);
    return GB_OK;
}

JNIEXPORT jint JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeSessionSramSize(
    JNIEnv *env, jclass clazz, jlong handle
) {
    (void)env;
    (void)clazz;
    return (jint)native_session_sram_size(session_from_handle(handle));
}

JNIEXPORT jint JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeSessionSramLoad(
    JNIEnv *env,
    jclass clazz,
    jlong handle,
    jbyteArray data
) {
    (void)clazz;
    native_session *session = session_from_handle(handle);
    if (session == NULL || data == NULL) {
        return GB_ERR_NULL_ARG;
    }
    const size_t limit = native_session_sram_size(session) + NS_SRAM_RTC_EXTRA;
    const size_t length = (size_t)(*env)->GetArrayLength(env, data);
    /* Un .sav mayor que RAM + bloque RTC nunca es válido: se rechaza sin copiarlo. */
    if (length > limit) {
        return GB_ERR_SRAM_SIZE;
    }
    uint8_t *temporary = malloc(length > 0u ? length : 1u);
    if (temporary == NULL) {
        return GB_ERR_OUT_OF_MEMORY;
    }
    if (length > 0u) {
        (*env)->GetByteArrayRegion(env, data, 0, (jsize)length, (jbyte *)temporary);
        if ((*env)->ExceptionCheck(env)) {
            free(temporary);
            return GB_ERR_NULL_ARG;
        }
    }
    const int result = native_session_sram_load(session, temporary, length);
    free(temporary);
    return result;
}

JNIEXPORT jlong JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeSessionSramDirtySeq(
    JNIEnv *env, jclass clazz, jlong handle
) {
    (void)env;
    (void)clazz;
    return (jlong)native_session_sram_dirty_seq(session_from_handle(handle));
}

JNIEXPORT jint JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeSessionSramCopy(
    JNIEnv *env,
    jclass clazz,
    jlong handle,
    jbyteArray out
) {
    (void)clazz;
    native_session *session = session_from_handle(handle);
    if (session == NULL || out == NULL) {
        return GB_ERR_NULL_ARG;
    }
    const size_t size = native_session_sram_size(session);
    if ((size_t)(*env)->GetArrayLength(env, out) < size) {
        return GB_ERR_BUFFER_TOO_SMALL;
    }
    uint8_t *temporary = malloc(size > 0u ? size : 1u);
    if (temporary == NULL) {
        return GB_ERR_OUT_OF_MEMORY;
    }
    const int result = native_session_sram_copy(session, temporary, size);
    if (result == NS_OK && size > 0u) {
        (*env)->SetByteArrayRegion(env, out, 0, (jsize)size, (const jbyte *)temporary);
    }
    free(temporary);
    return result;
}

/* Entrega el estado en `holder[0]` (un ByteArray del tamaño exacto): Kotlin no necesita conocer el tamaño antes. */
JNIEXPORT jint JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeSessionStateSave(
    JNIEnv *env,
    jclass clazz,
    jlong handle,
    jobjectArray holder
) {
    (void)clazz;
    native_session *session = session_from_handle(handle);
    if (session == NULL || holder == NULL || (*env)->GetArrayLength(env, holder) < 1) {
        return GB_ERR_NULL_ARG;
    }
    size_t size = 0u;
    const int sized = native_session_state_size(session, &size);
    if (sized != NS_OK) {
        return sized;
    }
    uint8_t *temporary = malloc(size);
    if (temporary == NULL) {
        return GB_ERR_OUT_OF_MEMORY;
    }
    const int result = native_session_state_save(session, temporary, size);
    if (result == NS_OK) {
        jbyteArray array = (*env)->NewByteArray(env, (jsize)size);
        if (array == NULL) {
            free(temporary);
            return GB_ERR_OUT_OF_MEMORY;
        }
        (*env)->SetByteArrayRegion(env, array, 0, (jsize)size, (const jbyte *)temporary);
        (*env)->SetObjectArrayElement(env, holder, 0, array);
        (*env)->DeleteLocalRef(env, array);
    }
    free(temporary);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeSessionStateLoad(
    JNIEnv *env,
    jclass clazz,
    jlong handle,
    jbyteArray data
) {
    (void)clazz;
    native_session *session = session_from_handle(handle);
    if (session == NULL || data == NULL) {
        return GB_ERR_NULL_ARG;
    }
    size_t size = 0u;
    const int sized = native_session_state_size(session, &size);
    if (sized != NS_OK) {
        return sized;
    }
    const size_t length = (size_t)(*env)->GetArrayLength(env, data);
    /* Un estado de este ROM mide exactamente `size`; uno mayor es entrada hostil o de otro juego y no se copia. */
    if (length > size) {
        return GB_ERR_STATE_CORRUPT;
    }
    uint8_t *temporary = malloc(length > 0u ? length : 1u);
    if (temporary == NULL) {
        return GB_ERR_OUT_OF_MEMORY;
    }
    if (length > 0u) {
        (*env)->GetByteArrayRegion(env, data, 0, (jsize)length, (jbyte *)temporary);
        if ((*env)->ExceptionCheck(env)) {
            free(temporary);
            return GB_ERR_NULL_ARG;
        }
    }
    const int result = native_session_state_load(session, temporary, length);
    free(temporary);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeSessionCopyFrame(
    JNIEnv *env,
    jclass clazz,
    jlong handle,
    jintArray out
) {
    (void)clazz;
    native_session *session = session_from_handle(handle);
    if (session == NULL || out == NULL) {
        return GB_ERR_NULL_ARG;
    }
    const size_t pixels = (size_t)GB_SCREEN_W * (size_t)GB_SCREEN_H;
    if ((size_t)(*env)->GetArrayLength(env, out) < pixels) {
        return GB_ERR_BUFFER_TOO_SMALL;
    }
    uint32_t *temporary = malloc(pixels * sizeof(uint32_t));
    if (temporary == NULL) {
        return GB_ERR_OUT_OF_MEMORY;
    }
    const int result = native_session_copy_framebuffer(session, temporary, pixels);
    if (result == NS_OK) {
        (*env)->SetIntArrayRegion(env, out, 0, (jsize)pixels, (const jint *)temporary);
    }
    free(temporary);
    return result;
}
