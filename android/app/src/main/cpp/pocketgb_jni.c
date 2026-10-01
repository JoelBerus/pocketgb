#include <jni.h>
#include <stdint.h>

#include "pocketgb.h"

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
