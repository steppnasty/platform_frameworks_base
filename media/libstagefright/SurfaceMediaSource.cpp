/*
 * Copyright (C) 2011 The Android Open Source Project
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
//#define LOG_NDEBUG 0
#define LOG_TAG "SurfaceMediaSource"

#include <media/stagefright/SurfaceMediaSource.h>
#include <ui/GraphicBuffer.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaDebug.h>
#include <OMX_IVCommon.h>
#include <media/stagefright/MetadataBufferType.h>

#include <ui/GraphicBuffer.h>
#include <surfaceflinger/ISurfaceComposer.h>
#include <gui/SurfaceComposerClient.h>
#include <gui/IGraphicBufferAlloc.h>
#include <OMX_Component.h>

#include <utils/Log.h>
#include <utils/String8.h>

#include <private/surfaceflinger/ComposerService.h>
#include <cutils/properties.h>
#include <gralloc_priv.h>

namespace android {

SurfaceMediaSource::SurfaceMediaSource(uint32_t bufferWidth, uint32_t bufferHeight) :
    mWidth(bufferWidth),
    mHeight(bufferHeight),
    mCurrentSlot(BufferQueue::INVALID_BUFFER_SLOT),
    mNumPendingBuffers(0),
    mCurrentTimestamp(0),
    mFrameRate(30),
    mStarted(false),
    mNumFramesReceived(0),
    mNumFramesEncoded(0),
    mFirstFrameTimestamp(0),
    mMaxAcquiredBufferCount(4),  // XXX double-check the default
    mUseAbsoluteTimestamps(false) {
    ALOGV("SurfaceMediaSource");

    if (bufferWidth == 0 || bufferHeight == 0) {
        ALOGE("Invalid dimensions %dx%d", bufferWidth, bufferHeight);
    }

    mBufferQueue = new BufferQueue(true);
    mBufferQueue->setDefaultBufferSize(bufferWidth, bufferHeight);
    mBufferQueue->setSynchronousMode(true);
    mBufferQueue->setConsumerUsageBits(GRALLOC_USAGE_HW_VIDEO_ENCODER |
            GRALLOC_USAGE_HW_TEXTURE);

    sp<ISurfaceComposer> composer(ComposerService::getComposerService());

    // Note that we can't create an sp<...>(this) in a ctor that will not keep a
    // reference once the ctor ends, as that would cause the refcount of 'this'
    // dropping to 0 at the end of the ctor.  Since all we need is a wp<...>
    // that's what we create.
    wp<BufferQueue::ConsumerListener> listener;
    sp<BufferQueue::ConsumerListener> proxy;
    listener = static_cast<BufferQueue::ConsumerListener*>(this);
    proxy = new BufferQueue::ProxyConsumerListener(listener);

    status_t err = mBufferQueue->consumerConnect(proxy);
    if (err != NO_ERROR) {
        ALOGE("SurfaceMediaSource: error connecting to BufferQueue: %s (%d)",
                strerror(-err), err);
    }
}

SurfaceMediaSource::~SurfaceMediaSource() {
    ALOGV("SurfaceMediaSource::~SurfaceMediaSource");
    if (!mStopped) {
        stop();
    }
}

size_t SurfaceMediaSource::getQueuedCount() const {
    Mutex::Autolock lock(mMutex);
    return mQueue.size();
}

status_t SurfaceMediaSource::setBufferCountServerLocked(int bufferCount) {
    if (bufferCount > NUM_BUFFER_SLOTS)
        return BAD_VALUE;

    // special-case, nothing to do
    if (bufferCount == mBufferCount)
        return OK;

    if (!mClientBufferCount &&
        bufferCount >= mBufferCount) {
        // easy, we just have more buffers
        mBufferCount = bufferCount;
        mServerBufferCount = bufferCount;
        mDequeueCondition.signal();
    } else {
        // we're here because we're either
        // - reducing the number of available buffers
        // - or there is a client-buffer-count in effect

        // less than 2 buffers is never allowed
        if (bufferCount < 2)
            return BAD_VALUE;

        // when there is non client-buffer-count in effect, the client is not
        // allowed to dequeue more than one buffer at a time,
        // so the next time they dequeue a buffer, we know that they don't
        // own one. the actual resizing will happen during the next
        // dequeueBuffer.

        mServerBufferCount = bufferCount;
    }
    return OK;
}

// Called from the consumer side
status_t SurfaceMediaSource::setBufferCountServer(int bufferCount) {
    Mutex::Autolock lock(mMutex);
    return setBufferCountServerLocked(bufferCount);
}

status_t SurfaceMediaSource::setBufferCount(int bufferCount) {
    ALOGV("SurfaceMediaSource::setBufferCount");
    if (bufferCount > NUM_BUFFER_SLOTS) {
        ALOGE("setBufferCount: bufferCount is larger than the number of buffer slots");
        return BAD_VALUE;
    }

    Mutex::Autolock lock(mMutex);
    // Error out if the user has dequeued buffers
    for (int i = 0 ; i < mBufferCount ; i++) {
        if (mSlots[i].mBufferState == BufferSlot::DEQUEUED) {
            ALOGE("setBufferCount: client owns some buffers");
            return INVALID_OPERATION;
        }
    }

    if (bufferCount == 0) {
        const int minBufferSlots = mSynchronousMode ?
                MIN_SYNC_BUFFER_SLOTS : MIN_ASYNC_BUFFER_SLOTS;
        mClientBufferCount = 0;
        bufferCount = (mServerBufferCount >= minBufferSlots) ?
                mServerBufferCount : minBufferSlots;
        return setBufferCountServerLocked(bufferCount);
    }

    // We don't allow the client to set a buffer-count less than
    // MIN_ASYNC_BUFFER_SLOTS (3), there is no reason for it.
    if (bufferCount < MIN_ASYNC_BUFFER_SLOTS) {
        return BAD_VALUE;
    }

    // here we're guaranteed that the client doesn't have dequeued buffers
    // and will release all of its buffer references.
    mBufferCount = bufferCount;
    mClientBufferCount = bufferCount;
    mCurrentSlot = INVALID_BUFFER_SLOT;
    mQueue.clear();
    mDequeueCondition.signal();
    freeAllBuffersLocked();
    return OK;
}

status_t SurfaceMediaSource::requestBuffer(int slot, sp<GraphicBuffer>* buf) {
    ALOGV("SurfaceMediaSource::requestBuffer");
    Mutex::Autolock lock(mMutex);
    if (slot < 0 || mBufferCount <= slot) {
        ALOGE("requestBuffer: slot index out of range [0, %d]: %d",
                mBufferCount, slot);
        return BAD_VALUE;
    }
    mSlots[slot].mRequestBufferCalled = true;
    *buf = mSlots[slot].mGraphicBuffer;
    return NO_ERROR;
}

// TODO: clean this up
status_t SurfaceMediaSource::setSynchronousMode(bool enabled) {
    Mutex::Autolock lock(mMutex);
    if (mStopped) {
        ALOGE("setSynchronousMode: SurfaceMediaSource has been stopped!");
        return NO_INIT;
    }

    if (!enabled) {
        // Async mode is not allowed
        ALOGE("SurfaceMediaSource can be used only synchronous mode!");
        return INVALID_OPERATION;
    }

    if (mSynchronousMode != enabled) {
        // - if we're going to asynchronous mode, the queue is guaranteed to be
        // empty here
        // - if the client set the number of buffers, we're guaranteed that
        // we have at least 3 (because we don't allow less)
        mSynchronousMode = enabled;
        mDequeueCondition.signal();
    }
    return OK;
}

status_t SurfaceMediaSource::connect(int api,
        uint32_t* outWidth, uint32_t* outHeight, uint32_t* outTransform) {
    ALOGV("SurfaceMediaSource::connect");
    Mutex::Autolock lock(mMutex);

    if (mStopped) {
        ALOGE("Connect: SurfaceMediaSource has been stopped!");
        return NO_INIT;
    }

    status_t err = NO_ERROR;
    switch (api) {
        case NATIVE_WINDOW_API_EGL:
        case NATIVE_WINDOW_API_CPU:
        case NATIVE_WINDOW_API_MEDIA:
        case NATIVE_WINDOW_API_CAMERA:
            if (mConnectedApi != NO_CONNECTED_API) {
                err = -EINVAL;
            } else {
                mConnectedApi = api;
                *outWidth = mDefaultWidth;
                *outHeight = mDefaultHeight;
                *outTransform = 0;
            }
            break;
        default:
            err = -EINVAL;
            break;
    }
    return err;
}

// This is called by the client side when it is done
// TODO: Currently, this also sets mStopped to true which
// is needed for unblocking the encoder which might be
// waiting to read more frames. So if on the client side,
// the same thread supplies the frames and also calls stop
// on the encoder, the client has to call disconnect before
// it calls stop.
// In the case of the camera,
// that need not be required since the thread supplying the
// frames is separate than the one calling stop.
status_t SurfaceMediaSource::disconnect(int api) {
    ALOGV("SurfaceMediaSource::disconnect");
    Mutex::Autolock lock(mMutex);

    if (mStopped) {
        ALOGE("disconnect: SurfaceMediaSoource is already stopped!");
        return NO_INIT;
    }

    status_t err = NO_ERROR;
    switch (api) {
        case NATIVE_WINDOW_API_EGL:
        case NATIVE_WINDOW_API_CPU:
        case NATIVE_WINDOW_API_MEDIA:
        case NATIVE_WINDOW_API_CAMERA:
            if (mConnectedApi == api) {
                mConnectedApi = NO_CONNECTED_API;
                mStopped = true;
                mDequeueCondition.signal();
                mFrameAvailableCondition.signal();
            } else {
                err = -EINVAL;
            }
            break;
        default:
            err = -EINVAL;
            break;
    }
    return err;
}

// onFrameReceivedLocked informs the buffer consumers (StageFrightRecorder)
// or listeners that a frame has been received
// It is supposed to be called only from queuebuffer.
// The buffer is NOT made available for dequeueing immediately. We need to
// wait to hear from StageFrightRecorder to set the buffer FREE
// Make sure this is called when the mutex is locked
status_t SurfaceMediaSource::onFrameReceivedLocked() {
    ALOGV("On Frame Received locked");
    // Signal the encoder that a new frame has arrived
    mFrameAvailableCondition.signal();

    // call back the listener
    // TODO: The listener may not be needed in SurfaceMediaSource at all.
    // This can be made a SurfaceTexture specific thing
    sp<FrameAvailableListener> listener;
    if (mSynchronousMode || mQueue.empty()) {
        listener = mFrameAvailableListener;
    }

    if (listener != 0) {
        listener->onFrameAvailable();
    }
    return OK;
}

nsecs_t SurfaceMediaSource::getTimestamp() {
    ALOGV("SurfaceMediaSource::getTimestamp");
    Mutex::Autolock lock(mMutex);
    return mCurrentTimestamp;
}


void SurfaceMediaSource::setFrameAvailableListener(
        const sp<FrameAvailableListener>& listener) {
    ALOGV("SurfaceMediaSource::setFrameAvailableListener");
    Mutex::Autolock lock(mMutex);
    mFrameAvailableListener = listener;
}

void SurfaceMediaSource::freeAllBuffersLocked() {
    ALOGV("freeAllBuffersLocked");
    for (int i = 0; i < NUM_BUFFER_SLOTS; i++) {
        mSlots[i].mGraphicBuffer = 0;
        mSlots[i].mBufferState = BufferSlot::FREE;
    }
}

sp<GraphicBuffer> SurfaceMediaSource::getCurrentBuffer() const {
    Mutex::Autolock lock(mMutex);
    return mCurrentBuf;
}

int SurfaceMediaSource::query(int what, int* outValue)
{
    ALOGV("query");
    Mutex::Autolock lock(mMutex);
    int value;
    switch (what) {
    case NATIVE_WINDOW_WIDTH:
        value = mDefaultWidth;
        if (!mDefaultWidth && !mDefaultHeight && mCurrentBuf != 0)
            value = mCurrentBuf->width;
        break;
    case NATIVE_WINDOW_HEIGHT:
        value = mDefaultHeight;
        if (!mDefaultWidth && !mDefaultHeight && mCurrentBuf != 0)
            value = mCurrentBuf->height;
        break;
    case NATIVE_WINDOW_FORMAT:
        value = mPixelFormat;
        break;
    case NATIVE_WINDOW_MIN_UNDEQUEUED_BUFFERS:
        value = mSynchronousMode ?
                (MIN_UNDEQUEUED_BUFFERS-1) : MIN_UNDEQUEUED_BUFFERS;
        break;
    default:
        return BAD_VALUE;
    }
    outValue[0] = value;
    return NO_ERROR;
}

void SurfaceMediaSource::dump(String8& result) const
{
    char buffer[1024];
    dump(result, "", buffer, 1024);
}

void SurfaceMediaSource::dump(String8& result, const char* prefix,
        char* buffer, size_t SIZE) const
{
    Mutex::Autolock _l(mMutex);
    snprintf(buffer, SIZE,
            "%smBufferCount=%d, mSynchronousMode=%d, default-size=[%dx%d], "
            "mPixelFormat=%d, \n",
            prefix, mBufferCount, mSynchronousMode, mDefaultWidth, mDefaultHeight,
            mPixelFormat);
    result.append(buffer);

    String8 fifo;
    int fifoSize = 0;
    Fifo::const_iterator i(mQueue.begin());
    while (i != mQueue.end()) {
        snprintf(buffer, SIZE, "%02d ", *i++);
        fifoSize++;
        fifo.append(buffer);
    }

    result.append(buffer);

    struct {
        const char * operator()(int state) const {
            switch (state) {
                case BufferSlot::DEQUEUED: return "DEQUEUED";
                case BufferSlot::QUEUED: return "QUEUED";
                case BufferSlot::FREE: return "FREE";
                default: return "Unknown";
            }
        }
    } stateName;

    for (int i = 0; i < mBufferCount; i++) {
        const BufferSlot& slot(mSlots[i]);
        snprintf(buffer, SIZE,
                "%s%s[%02d] state=%-8s, "
                "timestamp=%lld\n",
                prefix, (i==mCurrentSlot)?">":" ", i, stateName(slot.mBufferState),
                slot.mTimestamp
        );
        result.append(buffer);
    }
}

status_t SurfaceMediaSource::setFrameRate(int32_t fps)
{
    Mutex::Autolock lock(mMutex);
    const int MAX_FRAME_RATE = 60;
    if (fps < 0 || fps > MAX_FRAME_RATE) {
        return BAD_VALUE;
    }
    mFrameRate = fps;
    return OK;
}

bool SurfaceMediaSource::isMetaDataStoredInVideoBuffers() const {
    ALOGV("isMetaDataStoredInVideoBuffers");
    return true;
}

int32_t SurfaceMediaSource::getFrameRate( ) const {
    Mutex::Autolock lock(mMutex);
    return mFrameRate;
}

status_t SurfaceMediaSource::start(MetaData *params)
{
    ALOGV("started!");

    mStartTimeNs = 0;
    int64_t startTimeUs;
    if (params && params->findInt64(kKeyTime, &startTimeUs)) {
        mStartTimeNs = startTimeUs * 1000;
    }

    return OK;
}


status_t SurfaceMediaSource::stop()
{
    ALOGV("Stop");

    Mutex::Autolock lock(mMutex);
    // TODO: Add waiting on mFrameCompletedCondition here?
    mStopped = true;
    mFrameAvailableCondition.signal();
    mDequeueCondition.signal();
    mQueue.clear();
    freeAllBuffersLocked();

    return OK;
}

sp<MetaData> SurfaceMediaSource::getFormat()
{
    ALOGV("getFormat");
    Mutex::Autolock autoLock(mMutex);
    sp<MetaData> meta = new MetaData;

    meta->setInt32(kKeyWidth, mDefaultWidth);
    meta->setInt32(kKeyHeight, mDefaultHeight);
    // The encoder format is set as an opaque colorformat
    // The encoder will later find out the actual colorformat
    // from the GL Frames itself.
    meta->setInt32(kKeyColorFormat, OMX_COLOR_FormatAndroidOpaque);
    meta->setInt32(kKeyStride, mDefaultWidth);
    meta->setInt32(kKeySliceHeight, mDefaultHeight);
    meta->setInt32(kKeyFrameRate, mFrameRate);
    meta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_VIDEO_RAW);
    return meta;
}

status_t SurfaceMediaSource::read( MediaBuffer **buffer,
                                    const ReadOptions *options)
{
    Mutex::Autolock autoLock(mMutex) ;

    ALOGV("Read. Size of queued buffer: %d", mQueue.size());
    *buffer = NULL;

    // If the recording has started and the queue is empty, then just
    // wait here till the frames come in from the client side
    while (!mStopped && mQueue.empty()) {
        ALOGV("NO FRAMES! Recorder waiting for FrameAvailableCondition");
        mFrameAvailableCondition.wait(mMutex);
    }

    // If the loop was exited as a result of stopping the recording,
    // it is OK
    if (mStopped) {
        ALOGV("Read: SurfaceMediaSource is stopped. Returning ERROR_END_OF_STREAM.");
        return ERROR_END_OF_STREAM;
    }

    // Update the current buffer info
    // TODO: mCurrentSlot can be made a bufferstate since there
    // can be more than one "current" slots.
    Fifo::iterator front(mQueue.begin());
    mCurrentSlot = *front;
    mQueue.erase(front);
    mCurrentBuf = mSlots[mCurrentSlot].mGraphicBuffer;
    int64_t prevTimeStamp = mCurrentTimestamp;
    mCurrentTimestamp = mSlots[mCurrentSlot].mTimestamp;

    mNumFramesEncoded++;
    // Pass the data to the MediaBuffer. Pass in only the metadata
    passMetadataBufferLocked(buffer);

    (*buffer)->setObserver(this);
    (*buffer)->add_ref();
    (*buffer)->meta_data()->setInt64(kKeyTime, mCurrentTimestamp / 1000);
    ALOGV("Frames encoded = %d, timestamp = %lld, time diff = %lld",
            mNumFramesEncoded, mCurrentTimestamp / 1000,
            mCurrentTimestamp / 1000 - prevTimeStamp / 1000);

    return OK;
}

// Pass the data to the MediaBuffer. Pass in only the metadata
// The metadata passed consists of two parts:
// 1. First, there is an integer indicating that it is a GRAlloc
// source (kMetadataBufferTypeGrallocSource)
// 2. This is followed by the buffer_handle_t that is a handle to the
// GRalloc buffer. The encoder needs to interpret this GRalloc handle
// and encode the frames.
// --------------------------------------------------------------
// |  kMetadataBufferTypeGrallocSource | sizeof(buffer_handle_t) |
// --------------------------------------------------------------
// Note: Call only when you have the lock
void SurfaceMediaSource::passMetadataBufferLocked(MediaBuffer **buffer) {
    ALOGV("passMetadataBuffer");
    // MediaBuffer allocates and owns this data
    MediaBuffer *tempBuffer =
        new MediaBuffer(4 + sizeof(buffer_handle_t));
    char *data = (char *)tempBuffer->data();
    if (data == NULL) {
        ALOGE("Cannot allocate memory for metadata buffer!");
        return;
    }
    OMX_U32 type = kMetadataBufferTypeGrallocSource;
    memcpy(data, &type, 4);
    memcpy(data + 4, &(mCurrentBuf->handle), sizeof(buffer_handle_t));
    *buffer = tempBuffer;

    ALOGV("handle = %p, , offset = %d, length = %d",
            mCurrentBuf->handle, (*buffer)->range_length(), (*buffer)->range_offset());
}

void SurfaceMediaSource::signalBufferReturned(MediaBuffer *buffer) {
    ALOGV("signalBufferReturned");

    bool foundBuffer = false;
    Mutex::Autolock autoLock(mMutex);

    if (mStopped) {
        ALOGV("signalBufferReturned: mStopped = true! Nothing to do!");
        return;
    }

    for (int id = 0; id < NUM_BUFFER_SLOTS; id++) {
        if (mSlots[id].mGraphicBuffer == NULL) {
            continue;
        }
        if (checkBufferMatchesSlot(id, buffer)) {
            ALOGV("Slot %d returned, matches handle = %p", id,
                    mSlots[id].mGraphicBuffer->handle);
            mSlots[id].mBufferState = BufferSlot::FREE;
            buffer->setObserver(0);
            buffer->release();
            mDequeueCondition.signal();
            mFrameCompleteCondition.signal();
            foundBuffer = true;
            break;
        }
    }

    if (!foundBuffer) {
        CHECK_EQ(0, "signalBufferReturned: bogus buffer");
    }
}

bool SurfaceMediaSource::checkBufferMatchesSlot(int slot, MediaBuffer *buffer) {
    ALOGV("Check if Buffer matches slot");
    // need to convert to char* for pointer arithmetic and then
    // copy the byte stream into our handle
    buffer_handle_t bufferHandle ;
    memcpy( &bufferHandle, (char *)(buffer->data()) + 4, sizeof(buffer_handle_t));
    return mSlots[slot].mGraphicBuffer->handle  ==  bufferHandle;
}

// Part of the BufferQueue::ConsumerListener
void SurfaceMediaSource::onFrameAvailable() {
    ALOGV("onFrameAvailable");

    sp<FrameAvailableListener> listener;
    { // scope for the lock
        Mutex::Autolock lock(mMutex);
        mFrameAvailableCondition.broadcast();
        listener = mFrameAvailableListener;
    }

    if (listener != NULL) {
        ALOGV("actually calling onFrameAvailable");
        listener->onFrameAvailable();
    }
}

// SurfaceMediaSource hijacks this event to assume
// the prodcuer is disconnecting from the BufferQueue
// and that it should stop the recording
void SurfaceMediaSource::onBuffersReleased() {
    ALOGV("onBuffersReleased");

    Mutex::Autolock lock(mMutex);

    mFrameAvailableCondition.signal();

    for (int i = 0; i < BufferQueue::NUM_BUFFER_SLOTS; i++) {
       mBufferSlot[i] = 0;
    }
}

} // end of namespace android
