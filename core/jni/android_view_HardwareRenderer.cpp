/*
 * Copyright (C) 2010 The Android Open Source Project
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

#define LOG_TAG "HardwareRenderer"

#include "jni.h"
#include <nativehelper/JNIHelp.h>
#include <android_runtime/AndroidRuntime.h>

#include <EGL/egl_cache.h>

#ifdef USE_OPENGL_RENDERER
    EGLAPI void EGLAPIENTRY eglBeginFrame(EGLDisplay dpy, EGLSurface surface);
#endif

namespace android {

/**
 * Note: OpenGLRenderer JNI layer is generated and compiled only on supported
 *       devices. This means all the logic must be compiled only when the
 *       preprocessor variable USE_OPENGL_RENDERER is defined.
 */

// ----------------------------------------------------------------------------
// Tracing and debugging
// ----------------------------------------------------------------------------

static void android_view_HardwareRenderer_beginFrame(JNIEnv* env, jobject clazz,
        jintArray size) {
    EGLDisplay display = eglGetCurrentDisplay();
    EGLSurface surface = eglGetCurrentSurface(EGL_DRAW);

    if (size) {
        EGLint value;
        jint* storage = env->GetIntArrayElements(size, NULL);

        eglQuerySurface(display, surface, EGL_WIDTH, &value);
        storage[0] = value;

        eglQuerySurface(display, surface, EGL_HEIGHT, &value);
        storage[1] = value;

        env->ReleaseIntArrayElements(size, storage, 0);
    }

    eglBeginFrame(display, surface);
}

// ----------------------------------------------------------------------------
// Misc
// ----------------------------------------------------------------------------

static void android_view_HardwareRenderer_setupShadersDiskCache(JNIEnv* env, jobject clazz,
        jstring diskCachePath) {

    const char* cacheArray = env->GetStringUTFChars(diskCachePath, NULL);
    egl_cache_t::get()->setCacheFilename(cacheArray);
    env->ReleaseStringUTFChars(diskCachePath, cacheArray);
}

// ----------------------------------------------------------------------------
// JNI Glue
// ----------------------------------------------------------------------------

const char* const kClassPathName = "android/view/HardwareRenderer";

static JNINativeMethod gMethods[] = {
    { "nSetupShadersDiskCache", "(Ljava/lang/String;)V",
            (void*) android_view_HardwareRenderer_setupShadersDiskCache },
    { "nBeginFrame",            "([I)V", (void*) android_view_HardwareRenderer_beginFrame },
};

int register_android_view_HardwareRenderer(JNIEnv* env) {
    return AndroidRuntime::registerNativeMethods(env, kClassPathName, gMethods, NELEM(gMethods));
}

};
