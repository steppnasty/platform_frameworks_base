/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "SurfaceSession"

#include "JNIHelp.h"

#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/android_view_SurfaceSession.h>
#include <utils/Log.h>
#include <utils/RefBase.h>

#include <gui/SurfaceComposerClient.h>

namespace android {

static struct {
    jfieldID mNativeClient;
} gSurfaceSessionClassInfo;

sp<SurfaceComposerClient> android_view_SurfaceSession_getClient(
        JNIEnv* env, jobject surfaceSessionObj) {
    return reinterpret_cast<SurfaceComposerClient*>(
            env->GetIntField(surfaceSessionObj, gSurfaceSessionClassInfo.mNativeClient));
}

static jint SurfaceSession_init(JNIEnv* env, jobject clazz) {
    SurfaceComposerClient* client = new SurfaceComposerClient();
    client->incStrong(clazz);
    return reinterpret_cast<jint>(client);
}

static void SurfaceSession_destroy(JNIEnv* env, jobject clazz, jint ptr) {
    SurfaceComposerClient* client = reinterpret_cast<SurfaceComposerClient*>(ptr);
    client->decStrong(clazz);
}

static void SurfaceSession_kill(JNIEnv* env, jobject clazz, jint ptr) {
    SurfaceComposerClient* client = reinterpret_cast<SurfaceComposerClient*>(ptr);
    client->dispose();
}

static JNINativeMethod gSurfaceSessionMethods[] = {
    {"init",       "()I",  (void*)SurfaceSession_init },
    {"destroy",    "(I)V", (void*)SurfaceSession_destroy },
    {"nativeKill", "(I)V", (void*)SurfaceSession_kill },
};

int register_android_view_SurfaceSession(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "android/view/SurfaceSession",
            gSurfaceSessionMethods, NELEM(gSurfaceSessionMethods));
    LOG_ALWAYS_FATAL_IF(res < 0, "Unable to register native methods.");

    jclass clazz = env->FindClass("android/view/SurfaceSession");
    gSurfaceSessionClassInfo.mNativeClient = env->GetFieldID(clazz, "mNativeClient", "I");
    return 0;
}

} // namespace android
