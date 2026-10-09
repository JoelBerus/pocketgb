#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <android/native_window_jni.h>

#include "pocketgb.h"
#include "pocketgb_progress.h"
#include "pocketgb_pgbm.h"
#include "pocketgba.h"
#include "native_session.h"

/* gb_result no tiene "argumento inválido" (core/ no se toca): el puente usa el siguiente código libre
 * y CoreError.fromResult lo traduce a CoreError.InvalidArgument. */
#define JNI_ERR_INVALID_ARGUMENT 17
_Static_assert(GB_ERR_BUFFER_TOO_SMALL == 16,
    "gb_result cambió: JNI_ERR_INVALID_ARGUMENT debe seguir siendo el siguiente código libre");
_Static_assert(JNI_ERR_INVALID_ARGUMENT == NS_ERR_INVALID_ARGUMENT,
    "el puente y la sesión deben usar el mismo código de argumento inválido");

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
    /* Entrada hostil: un "ROM" mayor que el máximo del núcleo no se copia. */
    if ((size_t)length > GB_ROM_MAX_BYTES) {
        return GB_ERR_ROM_TOO_LARGE;
    }
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
    if (!options_valid(model, compat_palette) || native_session_console(session) != NATIVE_CONSOLE_GB) {
        return JNI_ERR_INVALID_ARGUMENT;
    }
    const jsize length = (*env)->GetArrayLength(env, rom);
    if ((size_t)length > GB_ROM_MAX_BYTES) {
        return GB_ERR_ROM_TOO_LARGE;
    }
    jbyte *bytes = (*env)->GetByteArrayElements(env, rom, NULL);
    if (bytes == NULL) {
        return GB_ERR_OUT_OF_MEMORY;
    }
    gb_options options;
    gb_options_default(&options);
    options.unix_time = (int64_t)unix_time;
    options.model = (gb_model)model;
    options.compat_palette = (uint8_t)compat_palette;
    const int result = native_session_load(
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
    native_session_set_touch_buttons((native_session *)(uintptr_t)handle, (uint16_t)mask);
}

JNIEXPORT void JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeSessionSetPhysicalButtons(
    JNIEnv *env, jclass clazz, jlong handle, jint mask
) {
    (void)env;
    (void)clazz;
    native_session_set_physical_buttons((native_session *)(uintptr_t)handle, (uint16_t)mask);
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
    const size_t limit = native_session_sram_capacity(session) + NS_SRAM_RTC_EXTRA;
    const size_t length = (size_t)(*env)->GetArrayLength(env, data);
    /* Un .sav mayor que el mayor posible (RAM + bloque RTC; en GBA, el medio más grande + 16) nunca es válido:
     * se rechaza sin copiarlo. */
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

/* Entrega la partida en `holder[0]` (un ByteArray del tamaño exacto de ese instante): en GBA una EEPROM sin
 * tamaño confirmado puede crecer de 512 B a 8 KiB entre dos llamadas, así que Kotlin no lo pide antes. */
JNIEXPORT jint JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeSessionSramCopy(
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
    const size_t capacity = native_session_sram_capacity(session);
    uint8_t *temporary = malloc(capacity > 0u ? capacity : 1u);
    if (temporary == NULL) {
        return GB_ERR_OUT_OF_MEMORY;
    }
    size_t length = 0u;
    const int result = native_session_sram_copy(session, temporary, capacity, &length);
    if (result == NS_OK) {
        jbyteArray array = (*env)->NewByteArray(env, (jsize)length);
        if (array == NULL) {
            free(temporary);
            return GB_ERR_OUT_OF_MEMORY;
        }
        if (length > 0u) {
            (*env)->SetByteArrayRegion(env, array, 0, (jsize)length, (const jbyte *)temporary);
        }
        (*env)->SetObjectArrayElement(env, holder, 0, array);
        (*env)->DeleteLocalRef(env, array);
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
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeSessionSetRtcTime(
    JNIEnv *env,
    jclass clazz,
    jlong handle,
    jlong unix_time
) {
    (void)env;
    (void)clazz;
    native_session *session = session_from_handle(handle);
    if (session == NULL) {
        return GB_ERR_NULL_ARG;
    }
    return native_session_set_rtc_time(session, (int64_t)unix_time);
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
    const size_t pixels = (size_t)native_session_screen_width(session) * (size_t)native_session_screen_height(session);
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

/* ---- Game Boy Advance (N8) ----
 * Mismas reglas que arriba: el ROM, la BIOS y los .sav son entrada no confiable. Las longitudes se validan antes
 * de copiar nada (ROM ≤ 32 MiB, BIOS exactamente 16 KiB) y la BIOS solo se carga si es la oficial (SHA-256). Los
 * resultados van en el espacio común de códigos de native_session.h (native_result_from_gba). */

#define GBA_INFO_INTS 8
#define GBA_INFO_TITLE 13
#define GBA_INFO_CODES 8

static bool gba_options_valid(jint save_type, jint rtc) {
    return save_type >= (jint)GBA_SAVE_AUTO && save_type <= (jint)GBA_SAVE_EEPROM8K &&
        rtc >= (jint)GBA_RTC_AUTO && rtc <= (jint)GBA_RTC_OFF;
}

/* La BIOS del usuario, si tiene el tamaño de una BIOS de GBA, en un búfer nativo (el llamador lo libera). Con
 * otro tamaño no se copia y no se usa (HLE): `*out` queda en NULL. */
static int copy_gba_bios(JNIEnv *env, jbyteArray bios, uint8_t **out, size_t *length) {
    *out = NULL;
    *length = 0u;
    if (bios == NULL || (size_t)(*env)->GetArrayLength(env, bios) != GBA_BIOS_BYTES) {
        return NS_OK;
    }
    uint8_t *copy = malloc(GBA_BIOS_BYTES);
    if (copy == NULL) {
        return GB_ERR_OUT_OF_MEMORY;
    }
    (*env)->GetByteArrayRegion(env, bios, 0, (jsize)GBA_BIOS_BYTES, (jbyte *)copy);
    if ((*env)->ExceptionCheck(env)) {
        free(copy);
        return GB_ERR_NULL_ARG;
    }
    *out = copy;
    *length = GBA_BIOS_BYTES;
    return NS_OK;
}

static void gba_options_from_jni(gba_options *options, jlong unix_time, jint save_type, jint rtc) {
    gba_options_default(options);
    options->save_type = (gba_save_type)save_type;
    options->rtc = (uint8_t)rtc;
    /* El RTC del GBA cuenta hora local (como iOS, GBACoreBridge.localTime). */
    options->unix_time = native_local_time((int64_t)unix_time);
}

static bool gba_eeprom_size_fixed(const gba_rom_info *info, jint requested_save_type) {
    const bool eeprom = info->save_type == GBA_SAVE_EEPROM512 || info->save_type == GBA_SAVE_EEPROM8K;
    return eeprom && (requested_save_type == (jint)GBA_SAVE_EEPROM512 || requested_save_type == (jint)GBA_SAVE_EEPROM8K);
}

/* ints: tamaño del ROM, tipo de medio (gba_save_type), bytes del .sav sin RTC, RTC, checksum de cabecera, BIOS
 * real, versión y EEPROM de tamaño fijo. title: 13 bytes ASCII con NUL. codes: código de juego (5 con NUL) y
 * de fabricante (3 con NUL). */
static int write_gba_info(
    JNIEnv *env,
    const gba_rom_info *info,
    bool eeprom_size_fixed,
    jintArray ints,
    jbyteArray fingerprint,
    jbyteArray title,
    jbyteArray codes
) {
    if (ints == NULL || fingerprint == NULL || title == NULL || codes == NULL) {
        return GB_ERR_NULL_ARG;
    }
    if ((*env)->GetArrayLength(env, ints) < GBA_INFO_INTS ||
        (*env)->GetArrayLength(env, fingerprint) < 32 ||
        (*env)->GetArrayLength(env, title) < GBA_INFO_TITLE ||
        (*env)->GetArrayLength(env, codes) < GBA_INFO_CODES) {
        return GB_ERR_BUFFER_TOO_SMALL;
    }
    jint values[GBA_INFO_INTS];
    values[0] = (jint)info->rom_bytes;
    values[1] = (jint)info->save_type;
    values[2] = (jint)info->save_bytes;
    values[3] = info->has_rtc;
    values[4] = info->header_checksum_ok;
    values[5] = info->bios_loaded;
    values[6] = info->version;
    values[7] = eeprom_size_fixed;
    jbyte code_bytes[GBA_INFO_CODES];
    memcpy(code_bytes, info->game_code, 5);
    memcpy(code_bytes + 5, info->maker_code, 3);
    (*env)->SetIntArrayRegion(env, ints, 0, GBA_INFO_INTS, values);
    (*env)->SetByteArrayRegion(env, fingerprint, 0, 32, (const jbyte *)info->fingerprint);
    (*env)->SetByteArrayRegion(env, title, 0, GBA_INFO_TITLE, (const jbyte *)info->title);
    (*env)->SetByteArrayRegion(env, codes, 0, GBA_INFO_CODES, code_bytes);
    return NS_OK;
}

/* (ancho << 16) | alto del framebuffer de `console`; 0 si la consola no existe. */
JNIEXPORT jint JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeConsoleScreenSize(
    JNIEnv *env, jclass clazz, jint console
) {
    (void)env;
    (void)clazz;
    if (console == NATIVE_CONSOLE_GB) return (GB_SCREEN_W << 16) | GB_SCREEN_H;
    if (console == NATIVE_CONSOLE_GBA) return (GBA_SCREEN_W << 16) | GBA_SCREEN_H;
    return 0;
}

JNIEXPORT jboolean JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeGbaBiosIsOfficial(
    JNIEnv *env, jclass clazz, jbyteArray bios
) {
    (void)clazz;
    uint8_t *copy = NULL;
    size_t length = 0u;
    if (copy_gba_bios(env, bios, &copy, &length) != NS_OK || copy == NULL) {
        return JNI_FALSE;
    }
    const bool official = native_gba_bios_is_official(copy, length);
    free(copy);
    return official ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeSessionCreateConsole(
    JNIEnv *env, jclass clazz, jint console
) {
    (void)env;
    (void)clazz;
    return (jlong)(uintptr_t)native_session_create_console((int)console);
}

JNIEXPORT jint JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeSessionScreenSize(
    JNIEnv *env, jclass clazz, jlong handle
) {
    (void)env;
    (void)clazz;
    native_session *session = session_from_handle(handle);
    return (native_session_screen_width(session) << 16) | native_session_screen_height(session);
}

JNIEXPORT jint JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeSessionSramCapacity(
    JNIEnv *env, jclass clazz, jlong handle
) {
    (void)env;
    (void)clazz;
    return (jint)native_session_sram_capacity(session_from_handle(handle));
}

JNIEXPORT jint JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeSessionLoadGba(
    JNIEnv *env,
    jclass clazz,
    jlong handle,
    jbyteArray rom,
    jbyteArray bios,
    jlong unix_time,
    jint save_type,
    jint rtc
) {
    (void)clazz;
    native_session *session = session_from_handle(handle);
    if (session == NULL || rom == NULL) {
        return GB_ERR_NULL_ARG;
    }
    if (!gba_options_valid(save_type, rtc) || native_session_console(session) != NATIVE_CONSOLE_GBA) {
        return JNI_ERR_INVALID_ARGUMENT;
    }
    const jsize length = (*env)->GetArrayLength(env, rom);
    if ((size_t)length > GBA_ROM_MAX_BYTES) {
        return GB_ERR_ROM_TOO_LARGE;
    }
    uint8_t *bios_bytes = NULL;
    size_t bios_length = 0u;
    const int copied = copy_gba_bios(env, bios, &bios_bytes, &bios_length);
    if (copied != NS_OK) {
        return copied;
    }
    jbyte *bytes = (*env)->GetByteArrayElements(env, rom, NULL);
    if (bytes == NULL) {
        free(bios_bytes);
        return GB_ERR_OUT_OF_MEMORY;
    }
    gba_options options;
    gba_options_from_jni(&options, unix_time, save_type, rtc);
    const int result = native_session_load_gba(
        session,
        (const uint8_t *)bytes,
        (size_t)length,
        bios_bytes,
        bios_length,
        &options
    );
    (*env)->ReleaseByteArrayElements(env, rom, bytes, JNI_ABORT);
    free(bios_bytes);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeSessionGbaRomInfo(
    JNIEnv *env,
    jclass clazz,
    jlong handle,
    jintArray ints,
    jbyteArray fingerprint,
    jbyteArray title,
    jbyteArray codes
) {
    (void)clazz;
    native_session *session = session_from_handle(handle);
    if (session == NULL) {
        return GB_ERR_NULL_ARG;
    }
    gba_rom_info info;
    memset(&info, 0, sizeof(info));
    bool eeprom_size_fixed = false;
    const int result = native_session_gba_rom_info(session, &info, &eeprom_size_fixed);
    if (result != NS_OK) {
        return result;
    }
    return write_gba_info(env, &info, eeprom_size_fixed, ints, fingerprint, title, codes);
}

/* Núcleo GBA suelto (CoreBridge): cabecera para la biblioteca y frames deterministas en las pruebas. */

static gba *gba_from_handle(jlong handle) {
    return (gba *)(uintptr_t)handle;
}

JNIEXPORT jlong JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeGbaCreate(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    return (jlong)(uintptr_t)gba_create();
}

JNIEXPORT void JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeGbaDestroy(JNIEnv *env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    gba_destroy(gba_from_handle(handle));
}

JNIEXPORT jint JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeGbaLoadRom(
    JNIEnv *env,
    jclass clazz,
    jlong handle,
    jbyteArray rom,
    jbyteArray bios,
    jlong unix_time,
    jint save_type,
    jint rtc
) {
    (void)clazz;
    gba *core = gba_from_handle(handle);
    if (core == NULL || rom == NULL) {
        return GB_ERR_NULL_ARG;
    }
    if (!gba_options_valid(save_type, rtc)) {
        return JNI_ERR_INVALID_ARGUMENT;
    }
    const jsize length = (*env)->GetArrayLength(env, rom);
    if ((size_t)length > GBA_ROM_MAX_BYTES) {
        return GB_ERR_ROM_TOO_LARGE;
    }
    uint8_t *bios_bytes = NULL;
    size_t bios_length = 0u;
    const int copied = copy_gba_bios(env, bios, &bios_bytes, &bios_length);
    if (copied != NS_OK) {
        return copied;
    }
    gba_result result = GBA_OK;
    if (native_gba_bios_is_official(bios_bytes, bios_length)) {
        result = gba_load_bios(core, bios_bytes, bios_length);
    }
    free(bios_bytes);
    if (result != GBA_OK) {
        return native_result_from_gba(result);
    }
    jbyte *bytes = (*env)->GetByteArrayElements(env, rom, NULL);
    if (bytes == NULL) {
        return GB_ERR_OUT_OF_MEMORY;
    }
    gba_options options;
    gba_options_from_jni(&options, unix_time, save_type, rtc);
    result = gba_load_rom(core, (const uint8_t *)bytes, (size_t)length, &options);
    (*env)->ReleaseByteArrayElements(env, rom, bytes, JNI_ABORT);
    return native_result_from_gba(result);
}

JNIEXPORT jint JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeGbaRomInfo(
    JNIEnv *env,
    jclass clazz,
    jlong handle,
    jintArray ints,
    jbyteArray fingerprint,
    jbyteArray title,
    jbyteArray codes,
    jint requested_save_type
) {
    (void)clazz;
    gba *core = gba_from_handle(handle);
    if (core == NULL) {
        return GB_ERR_NULL_ARG;
    }
    gba_rom_info info;
    memset(&info, 0, sizeof(info));
    const gba_result result = gba_rom_info_get(core, &info);
    if (result != GBA_OK) {
        return native_result_from_gba(result);
    }
    return write_gba_info(env, &info, gba_eeprom_size_fixed(&info, requested_save_type), ints, fingerprint, title, codes);
}

JNIEXPORT void JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeGbaRunFrame(JNIEnv *env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    gba *core = gba_from_handle(handle);
    if (core != NULL) gba_run_frame(core);
}

JNIEXPORT jint JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeGbaCopyFrame(
    JNIEnv *env,
    jclass clazz,
    jlong handle,
    jintArray destination
) {
    (void)clazz;
    gba *core = gba_from_handle(handle);
    if (core == NULL || destination == NULL) {
        return GB_ERR_NULL_ARG;
    }
    const jsize pixels = GBA_SCREEN_W * GBA_SCREEN_H;
    if ((*env)->GetArrayLength(env, destination) < pixels) {
        return GB_ERR_BUFFER_TOO_SMALL;
    }
    const uint32_t *frame = gba_framebuffer(core);
    if (frame == NULL) {
        return GB_ERR_NO_ROM;
    }
    (*env)->SetIntArrayRegion(env, destination, 0, pixels, (const jint *)frame);
    return NS_OK;
}

/*
 * N6 · lector de progreso Pokémon (pgb_progress_read, función pura de core/). Devuelve null si no hay datos; si no,
 * [juego, máscara de medallas, medallas, capturados, vistos, horas, minutos, segundos, dinero, bytes del nombre…]
 * con el nombre en UTF-8 (un byte por entero, sin el NUL). Los bytes de Java se copian a buffers acotados: la
 * cabecera basta con sus primeros 0x150 bytes y la partida se rechaza si supera 32 KiB + 48.
 */
JNIEXPORT jintArray JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativeProgressRead(
    JNIEnv *env,
    jclass clazz,
    jbyteArray header,
    jbyteArray sram
) {
    (void)clazz;
    if (header == NULL || sram == NULL) return NULL;
    const jsize header_len = (*env)->GetArrayLength(env, header);
    const jsize sram_len = (*env)->GetArrayLength(env, sram);
    if (header_len < (jsize)PGB_PROG_HEADER_MIN || sram_len <= 0 || sram_len > (jsize)(PGB_PROG_SAVE_BYTES + 48u)) {
        return NULL;
    }
    uint8_t head[PGB_PROG_HEADER_MIN];
    (*env)->GetByteArrayRegion(env, header, 0, (jsize)PGB_PROG_HEADER_MIN, (jbyte *)head);
    uint8_t *save = malloc((size_t)sram_len);
    if (save == NULL) return NULL;
    (*env)->GetByteArrayRegion(env, sram, 0, sram_len, (jbyte *)save);
    pgb_progress progress;
    const bool ok = pgb_progress_read(head, sizeof head, save, (size_t)sram_len, &progress);
    free(save);
    if (!ok) return NULL;
    size_t name_len = 0;
    while (name_len < PGB_PROG_NAME_MAX - 1 && progress.player_name[name_len] != '\0') name_len++;
    jint values[9 + PGB_PROG_NAME_MAX];
    values[0] = (jint)progress.game;
    values[1] = (jint)progress.badges_mask;
    values[2] = (jint)progress.badges_count;
    values[3] = (jint)progress.pokedex_owned;
    values[4] = (jint)progress.pokedex_seen;
    values[5] = (jint)progress.play_hours;
    values[6] = (jint)progress.play_minutes;
    values[7] = (jint)progress.play_seconds;
    values[8] = (jint)progress.money;
    for (size_t i = 0; i < name_len; i++) values[9 + i] = (jint)(uint8_t)progress.player_name[i];
    const jsize count = (jsize)(9 + name_len);
    jintArray result = (*env)->NewIntArray(env, count);
    if (result != NULL) (*env)->SetIntArrayRegion(env, result, 0, count, values);
    return result;
}

/*
 * N7b · contenedor `.pgbm` (docs/12-formato-pgbm.md). El paquete es ENTRADA NO CONFIABLE: se copia a un bloque propio de
 * tamaño exacto (tope PGBM_MAX_TOTAL antes de reservar) y lo valida pgbm_parse. Devuelve el `pgbm_result` (sus valores
 * son contrato: Kotlin los mapea por número). En éxito, `spans` recibe desplazamiento y longitud de META, SAVE, STAT y
 * THMB dentro del propio paquete (-1, 0 = ausente) y `rom_fp` los 32 bytes de ROMF.
 */
JNIEXPORT jint JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativePgbmParse(
    JNIEnv *env, jclass clazz, jbyteArray package, jintArray spans, jbyteArray rom_fp
) {
    (void)clazz;
    if (package == NULL || spans == NULL || rom_fp == NULL) return (jint)PGBM_ERR_ARG;
    if ((*env)->GetArrayLength(env, spans) < 8 || (*env)->GetArrayLength(env, rom_fp) < (jsize)PGBM_ROMF_BYTES) {
        return (jint)PGBM_ERR_ARG;
    }
    const jsize len = (*env)->GetArrayLength(env, package);
    if (len < 0 || (size_t)len > PGBM_MAX_TOTAL) return (jint)PGBM_ERR_TOO_LARGE;
    uint8_t *buf = malloc(len > 0 ? (size_t)len : 1u);
    if (buf == NULL) return (jint)PGBM_ERR_ARG;
    (*env)->GetByteArrayRegion(env, package, 0, len, (jbyte *)buf);
    pgbm_view view;
    const pgbm_result r = pgbm_parse(buf, (size_t)len, &view);
    if (r == PGBM_OK) {
        const pgbm_span s[4] = { view.meta, view.sav, view.state, view.thumb };
        jint out[8];
        for (int i = 0; i < 4; i++) {
            /* SAVE puede ir vacía (presente con longitud 0): su desplazamiento sigue siendo válido o NULL. */
            out[2 * i] = s[i].data != NULL ? (jint)(s[i].data - buf) : -1;
            out[2 * i + 1] = (jint)s[i].len;
        }
        (*env)->SetIntArrayRegion(env, spans, 0, 8, out);
        (*env)->SetByteArrayRegion(env, rom_fp, 0, (jsize)PGBM_ROMF_BYTES, (const jbyte *)view.rom_fp);
    }
    free(buf);
    return (jint)r;
}

static bool copy_span(JNIEnv *env, jbyteArray a, uint32_t max, uint8_t **out, uint32_t *len) {
    *out = NULL;
    *len = 0;
    if (a == NULL) return true;
    const jsize n = (*env)->GetArrayLength(env, a);
    if (n < 0 || (uint32_t)n > max) return false;
    if (n == 0) return true;
    *out = malloc((size_t)n);
    if (*out == NULL) return false;
    (*env)->GetByteArrayRegion(env, a, 0, n, (jbyte *)*out);
    *len = (uint32_t)n;
    return true;
}

/*
 * Codifica el paquete canónico (pgbm_encode). `result[0]` recibe el `pgbm_result`; devuelve los bytes o NULL. Las
 * opcionales NULL o vacías no se escriben; `sav` NULL = SAVE vacía.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_joelbermudez_pocketgb_emulator_NativeLibrary_nativePgbmEncode(
    JNIEnv *env, jclass clazz, jbyteArray rom_fp, jbyteArray meta, jbyteArray sav, jbyteArray state, jbyteArray thumb,
    jintArray result
) {
    (void)clazz;
    jint code = (jint)PGBM_ERR_ARG;
    jbyteArray out_array = NULL;
    uint8_t *m = NULL, *s = NULL, *st = NULL, *th = NULL, *out = NULL;
    pgbm_view v;
    memset(&v, 0, sizeof v);
    if (rom_fp == NULL || result == NULL || (*env)->GetArrayLength(env, result) < 1) goto done;
    if ((*env)->GetArrayLength(env, rom_fp) != (jsize)PGBM_ROMF_BYTES) goto done;
    (*env)->GetByteArrayRegion(env, rom_fp, 0, (jsize)PGBM_ROMF_BYTES, (jbyte *)v.rom_fp);
    code = (jint)PGBM_ERR_TOO_LARGE;
    if (!copy_span(env, meta, PGBM_MAX_META, &m, &v.meta.len) || !copy_span(env, sav, PGBM_MAX_SAV, &s, &v.sav.len) ||
        !copy_span(env, state, PGBM_MAX_STATE, &st, &v.state.len) || !copy_span(env, thumb, PGBM_MAX_THUMB, &th, &v.thumb.len)) {
        goto done;
    }
    v.meta.data = m;
    v.sav.data = s;
    v.state.data = st;
    v.thumb.data = th;
    const size_t size = pgbm_encoded_size(&v);
    if (size == 0) goto done;
    out = malloc(size);
    code = (jint)PGBM_ERR_ARG;
    if (out == NULL) goto done;
    size_t written = 0;
    code = (jint)pgbm_encode(&v, out, size, &written);
    if (code == (jint)PGBM_OK) {
        out_array = (*env)->NewByteArray(env, (jsize)written);
        if (out_array != NULL) (*env)->SetByteArrayRegion(env, out_array, 0, (jsize)written, (const jbyte *)out);
    }
done:
    if (result != NULL && (*env)->GetArrayLength(env, result) >= 1) (*env)->SetIntArrayRegion(env, result, 0, 1, &code);
    free(m);
    free(s);
    free(st);
    free(th);
    free(out);
    return out_array;
}
