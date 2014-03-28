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

#define LOG_TAG "Surface"

#include <stdio.h>

#include "android_os_Parcel.h"
#include "android_util_Binder.h"
#include "android/graphics/GraphicsJNI.h"
#include "android/graphics/Region.h"

#include <binder/IMemory.h>

#include <gui/SurfaceTexture.h>
#include <gui/SurfaceComposerClient.h>
#include <gui/Surface.h>

#include <ui/PixelFormat.h>
#include <ui/DisplayInfo.h>
#include <ui/Region.h>
#include <ui/Rect.h>

#include <EGL/egl.h>

#include <SkCanvas.h>
#include <SkBitmap.h>
#include <SkRegion.h>
#include <SkPixelRef.h>

#include "jni.h"
#include "JNIHelp.h"
#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/android_view_Surface.h>
#include <android_runtime/android_view_SurfaceSession.h>
#include <android_runtime/android_graphics_SurfaceTexture.h>
#include <utils/misc.h>

#include <ScopedUtfChars.h>

// ----------------------------------------------------------------------------

namespace android {

enum {
    // should match Parcelable.java
    PARCELABLE_WRITE_RETURN_VALUE = 0x0001
};

// ----------------------------------------------------------------------------

static const char* const OutOfResourcesException =
    "android/view/Surface$OutOfResourcesException";

struct sso_t {
    jfieldID client;
};
static sso_t sso;

static struct {
    jclass clazz;
    jfieldID mNativeSurface;
    jfieldID mNativeSurfaceControl;
    jfieldID mGenerationId;
    jfieldID mCanvas;
    jfieldID mCanvasSaveCount;
} gSurfaceClassInfo;

struct po_t {
    jfieldID x;
    jfieldID y;
};
static po_t po;

struct co_t {
    jfieldID surfaceFormat;
};
static co_t co;

struct no_t {
    jfieldID native_canvas;
    jfieldID native_parcel;
};
static no_t no;

static struct {
    jfieldID width;
    jfieldID height;
    jfieldID display;
    jfieldID pixelFormat;
    jfieldID fps;
    jfieldID density;
    jfieldID xDpi;
    jfieldID yDpi;
} gPhysicalDisplayInfoClassInfo;

static struct {
    jfieldID left;
    jfieldID top;
    jfieldID right;
    jfieldID bottom;
} gRectClassInfo;

// ----------------------------------------------------------------------------

static sp<SurfaceControl> getSurfaceControl(JNIEnv* env, jobject surfaceObj) {
    return reinterpret_cast<SurfaceControl*>(
            env->GetIntField(surfaceObj, gSurfaceClassInfo.mNativeSurfaceControl));
}

static void setSurfaceControl(JNIEnv* env, jobject surfaceObj,
        const sp<SurfaceControl>& surface) {
    SurfaceControl* const p = reinterpret_cast<SurfaceControl*>(
            env->GetIntField(surfaceObj, gSurfaceClassInfo.mNativeSurfaceControl));
    if (surface.get()) {
        surface->incStrong(surfaceObj);
    }
    if (p) {
        p->decStrong(surfaceObj);
    }
    env->SetIntField(surfaceObj, gSurfaceClassInfo.mNativeSurfaceControl,
            reinterpret_cast<jint>(surface.get()));
}

static sp<Surface> getSurface(JNIEnv* env, jobject surfaceObj) {
    sp<Surface> result(android_view_Surface_getSurface(env, surfaceObj));
    if (result == NULL) {
        /*
         * if this method is called from the WindowManager's process, it means
         * the client is is not remote, and therefore is allowed to have
         * a Surface (data), so we create it here.
         * If we don't have a SurfaceControl, it means we're in a different
         * process.
         */

        SurfaceControl* const control = reinterpret_cast<SurfaceControl*>(
                env->GetIntField(surfaceObj, gSurfaceClassInfo.mNativeSurfaceControl));
        if (control) {
            result = control->getSurface();
            if (result != NULL) {
                result->incStrong(surfaceObj);
                env->SetIntField(surfaceObj, gSurfaceClassInfo.mNativeSurface,
                        reinterpret_cast<jint>(result.get()));
            }
        }
    }
    return result;
}

sp<ANativeWindow> android_view_Surface_getNativeWindow(JNIEnv* env, jobject surfaceObj) {
    return getSurface(env, surfaceObj);
}

bool android_Surface_isInstanceOf(JNIEnv* env, jobject obj) {
    return env->IsInstanceOf(obj, gSurfaceClassInfo.clazz);
}

sp<Surface> android_view_Surface_getSurface(JNIEnv* env, jobject surfaceObj) {
    return reinterpret_cast<Surface*>(
            env->GetIntField(surfaceObj, gSurfaceClassInfo.mNativeSurface));
}

static void setSurface(JNIEnv* env, jobject surfaceObj, const sp<Surface>& surface) {
    Surface* const p = reinterpret_cast<Surface*>(
            env->GetIntField(surfaceObj, gSurfaceClassInfo.mNativeSurface));
    if (surface.get()) {
        surface->incStrong(surfaceObj);
    }
    if (p) {
        p->decStrong(surfaceObj);
    }
    env->SetIntField(surfaceObj, gSurfaceClassInfo.mNativeSurface,
            reinterpret_cast<jint>(surface.get()));

    // This test is conservative and it would be better to compare the ISurfaces
    if (p && p != surface.get()) {
        jint generationId = env->GetIntField(surfaceObj,
                gSurfaceClassInfo.mGenerationId);
        generationId++;
        env->SetIntField(surfaceObj,
                gSurfaceClassInfo.mGenerationId, generationId);
    }
}

// ----------------------------------------------------------------------------

static void nativeCreate(JNIEnv* env, jobject surfaceObj, jobject sessionObj,
        jstring nameStr, jint w, jint h, jint format, jint flags) {
    ScopedUtfChars name(env, nameStr);
    sp<SurfaceComposerClient> client(android_view_SurfaceSession_getClient(env, sessionObj));

    sp<SurfaceControl> surface = client->createSurface(
            String8(name.c_str()), 0, w, h, format, flags);
    if (surface == NULL) {
        jniThrowException(env, OutOfResourcesException, NULL);
        return;
    }
    setSurfaceControl(env, surfaceObj, surface);
}

static void Surface_initFromSurfaceTexture(
        JNIEnv* env, jobject clazz, jobject jst)
{
    sp<ISurfaceTexture> st(SurfaceTexture_getSurfaceTexture(env, jst));
    sp<Surface> surface(new Surface(st));
    if (surface == NULL) {
        jniThrowException(env, OutOfResourcesException, NULL);
        return;
    }
    setSurfaceControl(env, clazz, NULL);
    setSurface(env, clazz, surface);
}

static void Surface_initParcel(JNIEnv* env, jobject clazz, jobject argParcel)
{
    Parcel* parcel = (Parcel*)env->GetIntField(argParcel, no.native_parcel);
    if (parcel == NULL) {
        doThrowNPE(env);
        return;
    }

    sp<Surface> sur(Surface::readFromParcel(*parcel));
    setSurface(env, clazz, sur);
}

static jint Surface_getIdentity(JNIEnv* env, jobject clazz)
{
    const sp<SurfaceControl>& control(getSurfaceControl(env, clazz));
    if (control != 0) return (jint) control->getIdentity();
    const sp<Surface>& surface(getSurface(env, clazz));
    if (surface != 0) return (jint) surface->getIdentity();
    return -1;
}

static void Surface_destroy(JNIEnv* env, jobject surfaceObj) {
    sp<SurfaceControl> surfaceControl(getSurfaceControl(env, surfaceObj));
    if (SurfaceControl::isValid(surfaceControl)) {
        surfaceControl->clear();
    }
    setSurfaceControl(env, surfaceObj, NULL);
    setSurface(env, surfaceObj, NULL);
}

static void Surface_release(JNIEnv* env, jobject surfaceObj) {
    setSurfaceControl(env, surfaceObj, NULL);
    setSurface(env, surfaceObj, NULL);
}

static jboolean Surface_isValid(JNIEnv* env, jobject clazz)
{
    const sp<SurfaceControl>& surfaceControl(getSurfaceControl(env, clazz));
    if (surfaceControl != 0) {
        return SurfaceControl::isValid(surfaceControl) ? JNI_TRUE : JNI_FALSE;
    }
    const sp<Surface>& surface(getSurface(env, clazz));
    return Surface::isValid(surface) ? JNI_TRUE : JNI_FALSE;
}

static inline SkBitmap::Config convertPixelFormat(PixelFormat format)
{
    /* note: if PIXEL_FORMAT_RGBX_8888 means that all alpha bytes are 0xFF, then
        we can map to SkBitmap::kARGB_8888_Config, and optionally call
        bitmap.setIsOpaque(true) on the resulting SkBitmap (as an accelerator)
    */
    switch (format) {
    case PIXEL_FORMAT_RGBX_8888:    return SkBitmap::kARGB_8888_Config;
    case PIXEL_FORMAT_RGBA_8888:    return SkBitmap::kARGB_8888_Config;
    case PIXEL_FORMAT_RGBA_4444:    return SkBitmap::kARGB_4444_Config;
    case PIXEL_FORMAT_RGB_565:      return SkBitmap::kRGB_565_Config;
    case PIXEL_FORMAT_A_8:          return SkBitmap::kA8_Config;
    default:                        return SkBitmap::kNo_Config;
    }
}

static jobject nativeLockCanvas(JNIEnv* env, jobject surfaceObj, jobject dirtyRect) {
    sp<Surface> surface(getSurface(env, surfaceObj));
    if (!Surface::isValid(surface)) {
        doThrowIAE(env);
        return NULL;
    }

    // get dirty region
    Region dirtyRegion;
    if (dirtyRect) {
        Rect dirty;
        dirty.left  = env->GetIntField(dirtyRect, gRectClassInfo.left);
        dirty.top   = env->GetIntField(dirtyRect, gRectClassInfo.top);
        dirty.right = env->GetIntField(dirtyRect, gRectClassInfo.right);
        dirty.bottom= env->GetIntField(dirtyRect, gRectClassInfo.bottom);
        if (!dirty.isEmpty()) {
            dirtyRegion.set(dirty);
        }
    } else {
        dirtyRegion.set(Rect(0x3FFF, 0x3FFF));
    }

    Surface::SurfaceInfo info;
    status_t err = surface->lock(&info, &dirtyRegion);
    if (err < 0) {
        const char* const exception = (err == NO_MEMORY) ?
                OutOfResourcesException :
                "java/lang/IllegalArgumentException";
        jniThrowException(env, exception, NULL);
        return NULL;
    }

    // Associate a SkCanvas object to this surface
    jobject canvasObj = env->GetObjectField(surfaceObj, gSurfaceClassInfo.mCanvas);
    env->SetIntField(canvasObj, co.surfaceFormat, info.format);

    SkCanvas* nativeCanvas = reinterpret_cast<SkCanvas*>(
            env->GetIntField(canvasObj, no.native_canvas));
    SkBitmap bitmap;
    ssize_t bpr = info.s * bytesPerPixel(info.format);
    bitmap.setConfig(convertPixelFormat(info.format), info.w, info.h, bpr);
    if (info.format == PIXEL_FORMAT_RGBX_8888) {
        bitmap.setIsOpaque(true);
    }
    if (info.w > 0 && info.h > 0) {
        bitmap.setPixels(info.bits);
    } else {
        // be safe with an empty bitmap.
        bitmap.setPixels(NULL);
    }
    nativeCanvas->setBitmapDevice(bitmap);

    SkRegion clipReg;
    if (dirtyRegion.isRect()) { // very common case
        const Rect b(dirtyRegion.getBounds());
        clipReg.setRect(b.left, b.top, b.right, b.bottom);
    } else {
        size_t count;
        Rect const* r = dirtyRegion.getArray(&count);
        while (count) {
            clipReg.op(r->left, r->top, r->right, r->bottom, SkRegion::kUnion_Op);
            r++, count--;
        }
    }

    nativeCanvas->clipRegion(clipReg);

    int saveCount = nativeCanvas->save();
    env->SetIntField(surfaceObj, gSurfaceClassInfo.mCanvasSaveCount, saveCount);

    if (dirtyRect) {
        const Rect& bounds(dirtyRegion.getBounds());
        env->SetIntField(dirtyRect, gRectClassInfo.left, bounds.left);
        env->SetIntField(dirtyRect, gRectClassInfo.top, bounds.top);
        env->SetIntField(dirtyRect, gRectClassInfo.right, bounds.right);
        env->SetIntField(dirtyRect, gRectClassInfo.bottom, bounds.bottom);
    }

    return canvasObj;
}

static void nativeUnlockCanvasAndPost(JNIEnv* env, jobject surfaceObj, jobject canvasObj) {
    jobject ownCanvasObj = env->GetObjectField(surfaceObj, gSurfaceClassInfo.mCanvas);
    if (!env->IsSameObject(ownCanvasObj, canvasObj)) {
        doThrowIAE(env);
        return;
    }

    sp<Surface> surface(getSurface(env, surfaceObj));
    if (!Surface::isValid(surface)) {
        return;
    }

    // detach the canvas from the surface
    SkCanvas* nativeCanvas = reinterpret_cast<SkCanvas*>(
            env->GetIntField(canvasObj, no.native_canvas));
    int saveCount = env->GetIntField(surfaceObj, gSurfaceClassInfo.mCanvasSaveCount);
    nativeCanvas->restoreToCount(saveCount);
    nativeCanvas->setBitmapDevice(SkBitmap());
    env->SetIntField(surfaceObj, gSurfaceClassInfo.mCanvasSaveCount, 0);

    // unlock surface
    status_t err = surface->unlockAndPost();
    if (err < 0) {
        doThrowIAE(env);
    }
}

static void Surface_openTransaction(
        JNIEnv* env, jobject clazz)
{
    SurfaceComposerClient::openGlobalTransaction();
}

static void Surface_closeTransaction(
        JNIEnv* env, jobject clazz)
{
    SurfaceComposerClient::closeGlobalTransaction();
}

static void setOrientation(JNIEnv* env, jobject clazz, jint display, jint orientation) {
    int err = SurfaceComposerClient::setOrientation(display, orientation, 0);
    if (err < 0) {
        doThrowIAE(env);
    }
}

static void nativeSetAnimationTransaction(JNIEnv* env, jclass clazz) {
    SurfaceComposerClient::setAnimationTransaction();
}

class ScreenshotPixelRef : public SkPixelRef {
public:
    ScreenshotPixelRef(SkColorTable* ctable) {
        fCTable = ctable;
        SkSafeRef(ctable);
        setImmutable();
    }
    virtual ~ScreenshotPixelRef() {
        SkSafeUnref(fCTable);
    }

    status_t update(int width, int height, int minLayer, int maxLayer, bool allLayers) {
        status_t res = (width > 0 && height > 0)
                ? (allLayers
                        ? mScreenshot.update(width, height)
                        : mScreenshot.update(width, height, minLayer, maxLayer))
                : mScreenshot.update();
        if (res != NO_ERROR) {
            return res;
        }

        return NO_ERROR;
    }

    uint32_t getWidth() const {
        return mScreenshot.getWidth();
    }

    uint32_t getHeight() const {
        return mScreenshot.getHeight();
    }

    uint32_t getStride() const {
        return mScreenshot.getStride();
    }

    uint32_t getFormat() const {
        return mScreenshot.getFormat();
    }

protected:
    // overrides from SkPixelRef
    virtual void* onLockPixels(SkColorTable** ct) {
        *ct = fCTable;
        return (void*)mScreenshot.getPixels();
    }

    virtual void onUnlockPixels() {
    }

private:
    ScreenshotClient mScreenshot;
    SkColorTable*    fCTable;

    typedef SkPixelRef INHERITED;
};

static jobject doScreenshot(JNIEnv* env, jobject clazz, jint displayTokenObj,
        jint width, jint height, jint minLayer, jint maxLayer, bool allLayers)
{
    ScreenshotPixelRef* pixels = new ScreenshotPixelRef(NULL);
    if (pixels->update(width, height, minLayer, maxLayer, allLayers) != NO_ERROR) {
        delete pixels;
        return 0;
    }

    uint32_t w = pixels->getWidth();
    uint32_t h = pixels->getHeight();
    uint32_t s = pixels->getStride();
    uint32_t f = pixels->getFormat();
    ssize_t bpr = s * android::bytesPerPixel(f);

    SkBitmap* bitmap = new SkBitmap();
    bitmap->setConfig(convertPixelFormat(f), w, h, bpr);
    if (f == PIXEL_FORMAT_RGBX_8888) {
        bitmap->setIsOpaque(true);
    }

    if (w > 0 && h > 0) {
        bitmap->setPixelRef(pixels)->unref();
        bitmap->lockPixels();
    } else {
        // be safe with an empty bitmap.
        delete pixels;
        bitmap->setPixels(NULL);
    }

    return GraphicsJNI::createBitmap(env, bitmap, false, NULL);
}

static void Surface_setLayer(
        JNIEnv* env, jobject clazz, jint zorder)
{
    const sp<SurfaceControl>& surface(getSurfaceControl(env, clazz));
    if (surface == 0) return;
    status_t err = surface->setLayer(zorder);
    if (err<0 && err!=NO_INIT) {
        doThrowIAE(env);
    }
}

static void Surface_setPosition(
        JNIEnv* env, jobject clazz, jfloat x, jfloat y)
{
    const sp<SurfaceControl>& surface(getSurfaceControl(env, clazz));
    if (surface == 0) return;
    status_t err = surface->setPosition(x, y);
    if (err < 0 && err != NO_INIT) {
        doThrowIAE(env);
    }
}

static void nativeSetSize(JNIEnv* env, jobject surfaceObj, jint w, jint h) {
    sp<SurfaceControl> surface(getSurfaceControl(env, surfaceObj));
    if (surface == NULL) return;

    status_t err = surface->setSize(w, h);
    if (err < 0 && err != NO_INIT) {
        doThrowIAE(env);
    }
}

static void Surface_setStereoscopic3DFormat(JNIEnv* env, jobject clazz, jint f)
{
    const sp<Surface>& surface(getSurface(env, clazz));
    if (!Surface::isValid(surface))
        return;

    status_t err = surface->setStereoscopic3DFormat(f);
    if (err<0 && err!=NO_INIT) {
        doThrowIAE(env);
    }
}

static void Surface_hide(
        JNIEnv* env, jobject clazz)
{
    const sp<SurfaceControl>& surface(getSurfaceControl(env, clazz));
    if (surface == 0) return;
    status_t err = surface->hide();
    if (err<0 && err!=NO_INIT) {
        doThrowIAE(env);
    }
}

static void Surface_show(
        JNIEnv* env, jobject clazz)
{
    const sp<SurfaceControl>& surface(getSurfaceControl(env, clazz));
    if (surface == 0) return;
    status_t err = surface->show();
    if (err<0 && err!=NO_INIT) {
        doThrowIAE(env);
    }
}

static void Surface_setFlags(
        JNIEnv* env, jobject clazz, jint flags, jint mask)
{
    const sp<SurfaceControl>& surface(getSurfaceControl(env, clazz));
    if (surface == 0) return;
    status_t err = surface->setFlags(flags, mask);
    if (err<0 && err!=NO_INIT) {
        doThrowIAE(env);
    }
}

static void nativeSetTransparentRegionHint(JNIEnv* env, jobject surfaceObj, jobject regionObj) {
    sp<SurfaceControl> surface(getSurfaceControl(env, surfaceObj));
    if (surface == NULL) return;

    SkRegion* region = android_graphics_Region_getSkRegion(env, regionObj);
    if (!region) {
        doThrowIAE(env);
        return;
    }

    const SkIRect& b(region->getBounds());
    Region reg(Rect(b.fLeft, b.fTop, b.fRight, b.fBottom));
    if (region->isComplex()) {
        SkRegion::Iterator it(*region);
        while (!it.done()) {
            const SkIRect& r(it.rect());
            reg.addRectUnchecked(r.fLeft, r.fTop, r.fRight, r.fBottom);
            it.next();
        }
    }

    status_t err = surface->setTransparentRegionHint(reg);
    if (err < 0 && err != NO_INIT) {
        doThrowIAE(env);
    }
}

static void Surface_setAlpha(
        JNIEnv* env, jobject clazz, jfloat alpha)
{
    const sp<SurfaceControl>& surface(getSurfaceControl(env, clazz));
    if (surface == 0) return;
    status_t err = surface->setAlpha(alpha);
    if (err<0 && err!=NO_INIT) {
        doThrowIAE(env);
    }
}

static void Surface_setMatrix(
        JNIEnv* env, jobject clazz,
        jfloat dsdx, jfloat dtdx, jfloat dsdy, jfloat dtdy)
{
    const sp<SurfaceControl>& surface(getSurfaceControl(env, clazz));
    if (surface == 0) return;
    status_t err = surface->setMatrix(dsdx, dtdx, dsdy, dtdy);
    if (err<0 && err!=NO_INIT) {
        doThrowIAE(env);
    }
}

static void nativeSetWindowCrop(JNIEnv* env, jobject surfaceObj, jobject cropObj) {
    const sp<SurfaceControl>& surface(getSurfaceControl(env, surfaceObj));
    if (surface == NULL) return;

    Rect crop;
    if (cropObj) {
        crop.left = env->GetIntField(cropObj, gRectClassInfo.left);
        crop.top = env->GetIntField(cropObj, gRectClassInfo.top);
        crop.right = env->GetIntField(cropObj, gRectClassInfo.right);
        crop.bottom = env->GetIntField(cropObj, gRectClassInfo.bottom);
    } else {
        crop.left = crop.top = crop.right = crop.bottom = 0;
    }

    status_t err = surface->setCrop(crop);
    if (err < 0 && err != NO_INIT) {
        doThrowIAE(env);
    }
}

static void nativeSetLayerStack(JNIEnv* env, jobject surfaceObj, jint layerStack) {
    sp<SurfaceControl> surface(getSurfaceControl(env, surfaceObj));
    if (surface == NULL) return;

    status_t err = surface->setLayerStack(layerStack);
    if (err < 0 && err != NO_INIT) {
        doThrowIAE(env);
    }
}

static void nativeSetDisplayLayerStack(JNIEnv* env, jclass clazz,
        jint token, jint layerStack) {
    SurfaceComposerClient::setDisplayLayerStack(token, layerStack);
}

static void nativeSetDisplayProjection(JNIEnv* env, jclass clazz,
        jint token, jint orientation, jobject layerStackRectObj, jobject displayRectObj) {
    Rect layerStackRect;
    layerStackRect.left = env->GetIntField(layerStackRectObj, gRectClassInfo.left);
    layerStackRect.top = env->GetIntField(layerStackRectObj, gRectClassInfo.top);
    layerStackRect.right = env->GetIntField(layerStackRectObj, gRectClassInfo.right);
    layerStackRect.bottom = env->GetIntField(layerStackRectObj, gRectClassInfo.bottom);

    Rect displayRect;
    displayRect.left = env->GetIntField(displayRectObj, gRectClassInfo.left);
    displayRect.top = env->GetIntField(displayRectObj, gRectClassInfo.top);
    displayRect.right = env->GetIntField(displayRectObj, gRectClassInfo.right);
    displayRect.bottom = env->GetIntField(displayRectObj, gRectClassInfo.bottom);

    SurfaceComposerClient::setDisplayProjection(token, orientation, layerStackRect, displayRect);
}

static jboolean nativeGetDisplayInfo(JNIEnv* env, jclass clazz, jint dpy, jobject infoObj) {
    DisplayInfo info;
    status_t err = SurfaceComposerClient::getDisplayInfo(DisplayID(dpy), &info);
    if (err < 0) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return JNI_FALSE;
    }    

    env->SetIntField(infoObj, gPhysicalDisplayInfoClassInfo.width, info.w);
    env->SetIntField(infoObj, gPhysicalDisplayInfoClassInfo.height, info.h);
    env->SetFloatField(infoObj, gPhysicalDisplayInfoClassInfo.fps, info.fps);
    env->SetFloatField(infoObj, gPhysicalDisplayInfoClassInfo.density, info.density);
    env->SetFloatField(infoObj, gPhysicalDisplayInfoClassInfo.xDpi, info.xdpi);
    env->SetFloatField(infoObj, gPhysicalDisplayInfoClassInfo.yDpi, info.ydpi);
    return JNI_TRUE;
}

// ----------------------------------------------------------------------------

static void Surface_copyFrom(
        JNIEnv* env, jobject clazz, jobject other)
{
    if (clazz == other)
        return;

    if (other == NULL) {
        doThrowNPE(env);
        return;
    }

    /*
     * This is used by the WindowManagerService just after constructing
     * a Surface and is necessary for returning the Surface reference to
     * the caller. At this point, we should only have a SurfaceControl.
     */

    const sp<SurfaceControl>& surface = getSurfaceControl(env, clazz);
    const sp<SurfaceControl>& rhs = getSurfaceControl(env, other);
    if (!SurfaceControl::isSameSurface(surface, rhs)) {
        // we reassign the surface only if it's a different one
        // otherwise we would loose our client-side state.
        setSurfaceControl(env, clazz, rhs);
    }
}

static void Surface_transferFrom(
        JNIEnv* env, jobject clazz, jobject other)
{
    if (clazz == other)
        return;

    if (other == NULL) {
        doThrowNPE(env);
        return;
    }

    sp<SurfaceControl> control(getSurfaceControl(env, other));
    sp<Surface> surface(android_view_Surface_getSurface(env, other));
    setSurfaceControl(env, clazz, control);
    setSurface(env, clazz, surface);
    setSurfaceControl(env, other, 0);
    setSurface(env, other, 0);
}

static void Surface_readFromParcel(JNIEnv* env, jobject surfaceObj, jobject parcelObj) {
    Parcel* parcel = parcelForJavaObject(env, parcelObj);
    if (parcel == NULL) {
        doThrowNPE(env);
        return;
    }

    sp<Surface> surface(Surface::readFromParcel(*parcel));
    setSurfaceControl(env, surfaceObj, NULL);
    setSurface(env, surfaceObj, surface);
}

static void Surface_writeToParcel(JNIEnv* env, jobject surfaceObj, jobject parcelObj) {
    Parcel* parcel = parcelForJavaObject(env, parcelObj);
    if (parcel == NULL) {
        doThrowNPE(env);
        return;
    }

    // The Java instance may have a SurfaceControl (in the case of the
    // WindowManager or a system app). In that case, we defer to the
    // SurfaceControl to send its ISurface. Otherwise, if the Surface is
    // available we let it parcel itself. Finally, if the Surface is also
    // NULL we fall back to using the SurfaceControl path which sends an
    // empty surface; this matches legacy behavior.
    sp<SurfaceControl> control(getSurfaceControl(env, surfaceObj));
    if (control != NULL) {
        SurfaceControl::writeSurfaceToParcel(control, parcel);
    } else {
        sp<Surface> surface(android_view_Surface_getSurface(env, surfaceObj));
        if (surface != NULL) {
            Surface::writeToParcel(surface, parcel);
        } else {
            SurfaceControl::writeSurfaceToParcel(NULL, parcel);
        }
    }
}

// ----------------------------------------------------------------------------
// ----------------------------------------------------------------------------
// ----------------------------------------------------------------------------

static void nativeClassInit(JNIEnv* env, jclass clazz);

static JNINativeMethod gSurfaceMethods[] = {
    {"nativeClassInit",      "()V",  (void*)nativeClassInit },
    {"nativeCreate", "(Landroid/view/SurfaceSession;Ljava/lang/String;IIII)V",
            (void*)nativeCreate },
    {"init",                 "(Landroid/os/Parcel;)V",  (void*)Surface_initParcel },
    {"initFromSurfaceTexture", "(Landroid/graphics/SurfaceTexture;)V", (void*)Surface_initFromSurfaceTexture },
    {"nativeGetIdentity",    "()I",  (void*)Surface_getIdentity },
    {"nativeDestroy",        "()V",  (void*)Surface_destroy },
    {"nativeRelease",        "()V",  (void*)Surface_release },
    {"nativeCopyFrom",       "(Landroid/view/Surface;)V",  (void*)Surface_copyFrom },
    {"nativeTransferFrom",   "(Landroid/view/Surface;)V",  (void*)Surface_transferFrom },
    {"nativeIsValid",        "()Z",  (void*)Surface_isValid },
    {"nativeLockCanvas", "(Landroid/graphics/Rect;)Landroid/graphics/Canvas;",
            (void*)nativeLockCanvas },
    {"nativeUnlockCanvasAndPost", "(Landroid/graphics/Canvas;)V",
            (void*)nativeUnlockCanvasAndPost },
    {"nativeOpenTransaction", "()V",  (void*)Surface_openTransaction },
    {"nativeCloseTransaction", "()V",  (void*)Surface_closeTransaction },
    {"setOrientation", "(II)V", (void*)setOrientation },
    {"nativeSetAnimationTransaction", "()V",
            (void*)nativeSetAnimationTransaction },
    {"nativeScreenshot",           "(IIIIIZ)Landroid/graphics/Bitmap;", (void*)doScreenshot },
    {"nativeSetLayer",             "(I)V", (void*)Surface_setLayer },
    {"nativeSetPosition",          "(FF)V",(void*)Surface_setPosition },
    {"nativeSetSize", "(II)V",
            (void*)nativeSetSize },
    {"nativeSetFlags",       "(II)V",(void*)Surface_setFlags },
    {"nativeSetTransparentRegionHint", "(Landroid/graphics/Region;)V",
            (void*)nativeSetTransparentRegionHint },
    {"nativeSetAlpha",       "(F)V", (void*)Surface_setAlpha },
    {"nativeSetMatrix",      "(FFFF)V",  (void*)Surface_setMatrix },
    {"nativeSetWindowCrop",        "(Landroid/graphics/Rect;)V",
            (void*)nativeSetWindowCrop },
    {"nativeSetLayerStack", "(I)V", (void*)nativeSetLayerStack },
    {"nativeSetDisplayLayerStack", "(II)V",
            (void*)nativeSetDisplayLayerStack },
    {"nativeSetDisplayProjection", "(IILandroid/graphics/Rect;Landroid/graphics/Rect;)V",
            (void*)nativeSetDisplayProjection },
    {"nativeGetDisplayInfo", "(ILandroid/view/Surface$PhysicalDisplayInfo;)Z",
            (void*)nativeGetDisplayInfo },
    {"nativeReadFromParcel", "(Landroid/os/Parcel;)V", (void*)Surface_readFromParcel },
    {"nativeWriteToParcel",  "(Landroid/os/Parcel;)V", (void*)Surface_writeToParcel },
};

void nativeClassInit(JNIEnv* env, jclass clazz)
{
    jclass parcel = env->FindClass("android/os/Parcel");
}

int register_android_view_Surface(JNIEnv* env)
{
    int err = AndroidRuntime::registerNativeMethods(env, "android/view/Surface",
            gSurfaceMethods, NELEM(gSurfaceMethods));

    jclass clazz = env->FindClass("android/view/Surface");
    gSurfaceClassInfo.clazz = jclass(env->NewGlobalRef(clazz));
    gSurfaceClassInfo.mNativeSurface =
            env->GetFieldID(gSurfaceClassInfo.clazz, ANDROID_VIEW_SURFACE_JNI_ID, "I");
    gSurfaceClassInfo.mNativeSurfaceControl =
            env->GetFieldID(gSurfaceClassInfo.clazz, "mNativeSurfaceControl", "I");
    gSurfaceClassInfo.mGenerationId =
            env->GetFieldID(gSurfaceClassInfo.clazz, "mGenerationId", "I");
    gSurfaceClassInfo.mCanvas =
            env->GetFieldID(gSurfaceClassInfo.clazz, "mCanvas", "Landroid/graphics/Canvas;");
    gSurfaceClassInfo.mCanvasSaveCount =
            env->GetFieldID(gSurfaceClassInfo.clazz, "mCanvasSaveCount", "I");

    clazz = env->FindClass("android/graphics/Canvas");
    no.native_canvas = env->GetFieldID(clazz, "mNativeCanvas", "I");
    co.surfaceFormat = env->GetFieldID(clazz, "mSurfaceFormat", "I");

    clazz = env->FindClass("android/graphics/Rect");
    gRectClassInfo.left = env->GetFieldID(clazz, "left", "I");
    gRectClassInfo.top = env->GetFieldID(clazz, "top", "I");
    gRectClassInfo.right = env->GetFieldID(clazz, "right", "I");
    gRectClassInfo.bottom = env->GetFieldID(clazz, "bottom", "I");

    clazz = env->FindClass("android/view/Surface$PhysicalDisplayInfo");
    gPhysicalDisplayInfoClassInfo.width = env->GetFieldID(clazz, "width", "I");
    gPhysicalDisplayInfoClassInfo.height = env->GetFieldID(clazz, "height", "I");
    gPhysicalDisplayInfoClassInfo.fps = env->GetFieldID(clazz, "refreshRate", "F");
    gPhysicalDisplayInfoClassInfo.density = env->GetFieldID(clazz, "density", "F");
    gPhysicalDisplayInfoClassInfo.xDpi = env->GetFieldID(clazz, "xDpi", "F");
    gPhysicalDisplayInfoClassInfo.yDpi = env->GetFieldID(clazz, "yDpi", "F");

    return err;
}

};
