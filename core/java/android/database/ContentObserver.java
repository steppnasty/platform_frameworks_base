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

package android.database;

import android.net.Uri;
import android.os.Handler;

/**
 * Receives call backs for changes to content. Must be implemented by objects which are added
 * to a {@link ContentObservable}.
 */
public abstract class ContentObserver {

    private Transport mTransport;

    // Protects mTransport
    private Object lock = new Object();

    /* package */ Handler mHandler;

    private final class NotificationRunnable implements Runnable {
        private boolean mSelfChange;
        private final Uri mUri;

        public NotificationRunnable(boolean selfChange, Uri uri) {
            mSelfChange = selfChange;
            mUri = uri;
        }

        public void run() {
            ContentObserver.this.onChange(mSelfChange, mUri);
        }
    }

    private static final class Transport extends IContentObserver.Stub {
        ContentObserver mContentObserver;

        public Transport(ContentObserver contentObserver) {
            mContentObserver = contentObserver;
        }

        public boolean deliverSelfNotifications() {
            ContentObserver contentObserver = mContentObserver;
            if (contentObserver != null) {
                return contentObserver.deliverSelfNotifications();
            }
            return false;
        }

        public void onChange(boolean selfChange, Uri uri) {
            ContentObserver contentObserver = mContentObserver;
            if (contentObserver != null) {
                contentObserver.dispatchChange(selfChange, uri);
            }
        }

        public void releaseContentObserver() {
            mContentObserver = null;
        }
    }

    /**
     * onChange() will happen on the provider Handler.
     *
     * @param handler The handler to run {@link #onChange} on.
     */
    public ContentObserver(Handler handler) {
        mHandler = handler;
    }

    /**
     * Gets access to the binder transport object. Not for public consumption.
     *
     * {@hide}
     */
    public IContentObserver getContentObserver() {
        synchronized(lock) {
            if (mTransport == null) {
                mTransport = new Transport(this);
            }
            return mTransport;
        }
    }

    /**
     * Gets access to the binder transport object, and unlinks the transport object
     * from the ContentObserver. Not for public consumption.
     *
     * {@hide}
     */
    public IContentObserver releaseContentObserver() {
        synchronized(lock) {
            Transport oldTransport = mTransport;
            if (oldTransport != null) {
                oldTransport.releaseContentObserver();
                mTransport = null;
            }
            return oldTransport;
        }
    }

    /**
     * Returns true if this observer is interested in notifications for changes
     * made through the cursor the observer is registered with.
     */
    public boolean deliverSelfNotifications() {
        return false;
    }

    /**
     * This method is called when a content change occurs.
     * <p>
     * Subclasses should override this method to handle content changes.
     * </p>
     *
     * @param selfChange True if this is a self-change notification.
     */
    public void onChange(boolean selfChange) {
        // Do nothing.  Subclass should override.
    }

    /**
     * This method is called when a content change occurs.
     * Includes the changed content Uri when available.
     * <p>
     * Subclasses should override this method to handle content changes.
     * To ensure correct operation on older versions of the framework that
     * did not provide a Uri argument, applications should also implement
     * the {@link #onChange(boolean)} overload of this method whenever they
     * implement the {@link #onChange(boolean, Uri)} overload.
     * </p><p>
     * Example implementation:
     * <pre><code>
     * // Implement the onChange(boolean) method to delegate the change notification to
     * // the onChange(boolean, Uri) method to ensure correct operation on older versions
     * // of the framework that did not have the onChange(boolean, Uri) method.
     * {@literal @Override}
     * public void onChange(boolean selfChange) {
     *     onChange(selfChange, null);
     * }
     *
     * // Implement the onChange(boolean, Uri) method to take advantage of the new Uri argument.
     * {@literal @Override}
     * public void onChange(boolean selfChange, Uri uri) {
     *     // Handle change.
     * }
     * </code></pre>
     * </p>
     *
     * @param selfChange True if this is a self-change notification.
     * @param uri The Uri of the changed content, or null if unknown.
     */
    public void onChange(boolean selfChange, Uri uri) {
        onChange(selfChange);
    }

    /**
     * Dispatches a change notification to the observer.
     * <p>
     * If a {@link Handler} was supplied to the {@link ContentObserver} constructor,
     * then a call to the {@link #onChange} method is posted to the handler's message queue.
     * Otherwise, the {@link #onChange} method is invoked immediately on this thread.
     * </p>
     *
     * @param selfChange True if this is a self-change notification.
     *
     * @deprecated Use {@link #dispatchChange(boolean, Uri)} instead.
     */
    @Deprecated
    public final void dispatchChange(boolean selfChange) {
        dispatchChange(selfChange, null);
    }

    /**
     * Dispatches a change notification to the observer.
     * Includes the changed content Uri when available.
     * <p>
     * If a {@link Handler} was supplied to the {@link ContentObserver} constructor,
     * then a call to the {@link #onChange} method is posted to the handler's message queue.
     * Otherwise, the {@link #onChange} method is invoked immediately on this thread.
     * </p>
     *
     * @param selfChange True if this is a self-change notification.
     * @param uri The Uri of the changed content, or null if unknown.
     */
    public final void dispatchChange(boolean selfChange, Uri uri) {
        if (mHandler == null) {
            onChange(selfChange, uri);
        } else {
            mHandler.post(new NotificationRunnable(selfChange, uri));
        }
    }
}
