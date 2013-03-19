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

package android.view;

import android.graphics.Matrix;

/**
 * A display lists records a series of graphics related operation and can replay
 * them later. Display lists are usually built by recording operations on a
 * {@link android.graphics.Canvas}. Replaying the operations from a display list
 * avoids executing views drawing code on every frame, and is thus much more
 * efficient.
 *
 * @hide 
 */
public abstract class DisplayList {
    /**
     * Flag used when calling
     * {@link HardwareCanvas#drawDisplayList(DisplayList, android.graphics.Rect, int)} 
     * When this flag is set, draw operations lying outside of the bounds of the
     * display list will be culled early. It is recommeneded to always set this
     * flag.
     */
    public static final int FLAG_CLIP_CHILDREN = 0x1;

    // NOTE: The STATUS_* values *must* match the enum in DrawGlInfo.h

    /**
     * Indicates that the display list is done drawing.
     * 
     * @see HardwareCanvas#drawDisplayList(DisplayList, android.graphics.Rect, int)  
     */
    public static final int STATUS_DONE = 0x0;

    /**
     * Indicates that the display list needs another drawing pass.
     * 
     * @see HardwareCanvas#drawDisplayList(DisplayList, android.graphics.Rect, int)
     */
    public static final int STATUS_DRAW = 0x1;

    /**
     * Indicates that the display list needs to re-execute its GL functors.
     * 
     * @see HardwareCanvas#drawDisplayList(DisplayList, android.graphics.Rect, int)
     * @see HardwareCanvas#callDrawGLFunction(int)
     */
    public static final int STATUS_INVOKE = 0x2;

    /**
     * Indicates that the display list performed GL drawing operations.
     *
     * @see HardwareCanvas#drawDisplayList(DisplayList, android.graphics.Rect, int)
     */
    public static final int STATUS_DREW = 0x4;
    /**
     * Starts recording the display list. All operations performed on the
     * returned canvas are recorded and stored in this display list.
     * 
     * @return A canvas to record drawing operations.
     */
    abstract HardwareCanvas start();

    /**
     * Ends the recording for this display list. A display list cannot be
     * replayed if recording is not finished. 
     */
    abstract void end();

    /**
     * Invalidates the display list, indicating that it should be repopulated
     * with new drawing commands prior to being used again. Calling this method
     * causes calls to {@link #isValid()} to return <code>false</code>.
     */
    abstract void invalidate();

    /**
     * Returns whether the display list is currently usable. If this returns false,
     * the display list should be re-recorded prior to replaying it.
     *
     * @return boolean true if the display list is able to be replayed, false otherwise.
     */
    abstract boolean isValid();

    /**
     * Return the amount of memory used by this display list.
     * 
     * @return The size of this display list in bytes
     */
    abstract int getSize();

    ///////////////////////////////////////////////////////////////////////////
    // DisplayList Property Setters
    ///////////////////////////////////////////////////////////////////////////

    /**
     * Set the Animation matrix on the DisplayList. This matrix exists if an Animation is
     * currently playing on a View, and is set on the DisplayList during at draw() time. When
     * the Animation finishes, the matrix should be cleared by sending <code>null</code>
     * for the matrix parameter.
     *
     * @param matrix The matrix, null indicates that the matrix should be cleared.
     */
    public abstract void setAnimationMatrix(Matrix matrix);

    /**
     * Sets the alpha value for the DisplayList
     *
     * @param alpha The translucency of the DisplayList
     * @see View#setAlpha(float)
     */
    public abstract void setAlpha(float alpha);
}
