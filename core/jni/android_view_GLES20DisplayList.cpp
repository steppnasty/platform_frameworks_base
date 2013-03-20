/*
 * Copyright (C) 2012 The Android Open Source Project
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

#define LOG_TAG "OpenGLRenderer"

#include <EGL/egl.h>

#include "jni.h"
#include "GraphicsJNI.h"
#include <nativehelper/JNIHelp.h>
#include <android_runtime/AndroidRuntime.h>

#include <DisplayListRenderer.h>

namespace android {

using namespace uirenderer;

/**
 * Note: OpenGLRenderer JNI layer is generated and compiled only on supported
 *       devices. This means all the logic must be compiled only when the
 *       preprocessor variable USE_OPENGL_RENDERER is defined.
 */
#ifdef USE_OPENGL_RENDERER

// ----------------------------------------------------------------------------
// DisplayList view properties
// ----------------------------------------------------------------------------

static void android_view_GLES20DisplayList_setAnimationMatrix(JNIEnv* env,
        jobject clazz, DisplayList* displayList, SkMatrix* matrix) {
    displayList->setAnimationMatrix(matrix);
}

static void android_view_GLES20DisplayList_setAlpha(JNIEnv* env,
        jobject clazz, DisplayList* displayList, float alpha) {
    displayList->setAlpha(alpha);
}

#endif // USE_OPENGL_RENDERER

// ----------------------------------------------------------------------------
// JNI Glue
// ----------------------------------------------------------------------------

const char* const kClassPathName = "android/view/GLES20DisplayList";

static JNINativeMethod gMethods[] = {
#ifdef USE_OPENGL_RENDERER
    { "nSetAnimationMatrix",   "(II)V",  (void*) android_view_GLES20DisplayList_setAnimationMatrix },
    { "nSetAlpha",             "(IF)V",  (void*) android_view_GLES20DisplayList_setAlpha },

#endif
};

#ifdef USE_OPENGL_RENDERER
    #define FIND_CLASS(var, className) \
            var = env->FindClass(className); \
            LOG_FATAL_IF(! var, "Unable to find class " className);

    #define GET_METHOD_ID(var, clazz, methodName, methodDescriptor) \
            var = env->GetMethodID(clazz, methodName, methodDescriptor); \
            LOG_FATAL_IF(! var, "Unable to find method " methodName);
#else
    #define FIND_CLASS(var, className)
    #define GET_METHOD_ID(var, clazz, methodName, methodDescriptor)
#endif

int register_android_view_GLES20DisplayList(JNIEnv* env) {
    return AndroidRuntime::registerNativeMethods(env, kClassPathName, gMethods, NELEM(gMethods));
}

};
