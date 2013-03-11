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

package android.view;

import dalvik.system.CloseGuard;

import android.os.Looper;
import android.os.MessageQueue;
import android.util.Log;
import android.util.SparseIntArray;

/**
 * Provides a low-level mechanism for an application to receive input events.
 * @hide
 */
public abstract class InputEventReceiver {
    private static final String TAG = "InputEventReceiver";

    private final CloseGuard mCloseGuard = CloseGuard.get();

    private int mReceiverPtr;

    // We keep references to the input channel and message queue objects here so that
    // they are not GC'd while the native peer of the receiver is using them.
    private InputChannel mInputChannel;
    private MessageQueue mMessageQueue;

    // Map from InputEvent sequence numbers to dispatcher sequence numbers.
    private final SparseIntArray mSeqMap = new SparseIntArray();

    private static native int nativeInit(InputEventReceiver receiver,
            InputChannel inputChannel, MessageQueue messageQueue);
    private static native void nativeDispose(int receiverPtr);
    private static native void nativeFinishInputEvent(int receiverPtr, int seq, boolean handled);

    /**
     * Creates an input event receiver bound to the specified input channel.
     *
     * @param inputChannel The input channel.
     * @param looper The looper to use when invoking callbacks.
     */
    public InputEventReceiver(InputChannel inputChannel, Looper looper) {
        if (inputChannel == null) {
            throw new IllegalArgumentException("inputChannel must not be null");
        }
        if (looper == null) {
            throw new IllegalArgumentException("looper must not be null");
        }

        mInputChannel = inputChannel;
        mMessageQueue = looper.getQueue();
        mReceiverPtr = nativeInit(this, inputChannel, mMessageQueue);

        mCloseGuard.open("dispose");
    }

    /**
     * Disposes the receiver.
     */
    public void dispose() {
        dispose(false);
    }

    private void dispose(boolean finalized) {
        if (mCloseGuard != null) {
            if (finalized) {
                mCloseGuard.warnIfOpen();
            }
            mCloseGuard.close();
        }

        if (mReceiverPtr != 0) {
            nativeDispose(mReceiverPtr);
            mReceiverPtr = 0;
        }
        mInputChannel = null;
        mMessageQueue = null;
    }

    /**
     * Called when an input event is received.
     * The recipient should process the input event and then call {@link #finishInputEvent}
     * to indicate whether the event was handled.  No new input events will be received
     * until {@link #finishInputEvent} is called.
     *
     * @param event The input event that was received.
     */
    public void onInputEvent(InputEvent event) {
        finishInputEvent(event, false);
    }

    /**
     * Finishes an input event and indicates whether it was handled.
     * Must be called on the same Looper thread to which the receiver is attached.
     *
     * @param event The input event that was finished.
     * @param handled True if the event was handled.
     */
    public final void finishInputEvent(InputEvent event, boolean handled) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        if (mReceiverPtr == 0) {
            Log.w(TAG, "Attempted to finish an input event but the input event "
                    + "receiver has already been disposed.");
        } else {
            int index = mSeqMap.indexOfKey(event.getSequenceNumber());
            if (index < 0) {
                Log.w(TAG, "Attempted to finish an input event that is not in progress.");
            } else {
                int seq = mSeqMap.valueAt(index);
                mSeqMap.removeAt(index);
                nativeFinishInputEvent(mReceiverPtr, seq, handled);
            }
        }
        event.recycleIfNeededAfterDispatch();
    }

    public static interface Factory {
        public InputEventReceiver createInputEventReceiver(
                InputChannel inputChannel, Looper looper);
    }
}
