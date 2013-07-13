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

package com.android.systemui.recent;

import android.animation.Animator;
import android.animation.LayoutTransition;
import android.animation.TimeInterpolator;
import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Shader.TileMode;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.BaseAdapter;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;
import android.widget.PopupMenu;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.statusbar.BaseStatusBar;
import com.android.systemui.statusbar.phone.PhoneStatusBar;
import com.android.systemui.statusbar.tablet.StatusBarPanel;
import com.android.systemui.statusbar.tablet.TabletStatusBar;

import java.util.ArrayList;

public class RecentsPanelView extends RelativeLayout implements OnItemClickListener, RecentsCallback,
        StatusBarPanel, Animator.AnimatorListener, View.OnTouchListener {
    static final String TAG = "RecentsPanelView";
    static final boolean DEBUG = TabletStatusBar.DEBUG || PhoneStatusBar.DEBUG || false;
    private PopupMenu mPopup;
    private Context mContext;
    private BaseStatusBar mBar;
    private View mRecentsScrim;
    private View mRecentsNoApps;
    private ViewGroup mRecentsContainer;

    private boolean mShowing;
    private boolean mWaitingToShow;
    private int mNumItemsWaitingForThumbnailsAndIcons;
    private Choreographer mChoreo;
    private View mRecentsDismissButton;
    private ViewHolder mItemToAnimateInWhenWindowAnimationIsFinished;
    private boolean mWaitingForWindowAnimation;

    private RecentTasksLoader mRecentTasksLoader;
    private ArrayList<TaskDescription> mRecentTaskDescriptions;
    private Runnable mPreloadTasksRunnable;
    private boolean mRecentTasksDirty = true;
    private TaskDescriptionAdapter mListAdapter;
    private int mThumbnailWidth;
    private boolean mFitThumbnailToXY;
    private boolean mHighEndGfx;
    private ImageView mClearRecents;

    public static interface RecentsScrollView {
        public int numItemsInOneScreenful();
        public void setAdapter(TaskDescriptionAdapter adapter);
        public void setCallback(RecentsCallback callback);
        public void setMinSwipeAlpha(float minAlpha);
        public View findViewForTask(int persistentTaskId);
    }

    private final class OnLongClickDelegate implements View.OnLongClickListener {
        View mOtherView;
        OnLongClickDelegate(View other) {
            mOtherView = other;
        }
        public boolean onLongClick(View v) {
            return mOtherView.performLongClick();
        }
    }

    /* package */ final static class ViewHolder {
        View thumbnailView;
        ImageView thumbnailViewImage;
        Bitmap thumbnailViewImageBitmap;
        ImageView iconView;
        TextView labelView;
        TextView descriptionView;
        View calloutLine;
        TaskDescription taskDescription;
    }

    /* package */ final class TaskDescriptionAdapter extends BaseAdapter {
        private LayoutInflater mInflater;

        public TaskDescriptionAdapter(Context context) {
            mInflater = LayoutInflater.from(context);
        }

        public int getCount() {
            return mRecentTaskDescriptions != null ? mRecentTaskDescriptions.size() : 0;
        }

        public Object getItem(int position) {
            return position; // we only need the index
        }

        public long getItemId(int position) {
            return position; // we just need something unique for this position
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                convertView = mInflater.inflate(R.layout.status_bar_recent_item, parent, false);
                holder = new ViewHolder();
                holder.thumbnailView = convertView.findViewById(R.id.app_thumbnail);
                holder.thumbnailViewImage = (ImageView) convertView.findViewById(
                        R.id.app_thumbnail_image);
                // If we set the default thumbnail now, we avoid an onLayout when we update
                // the thumbnail later (if they both have the same dimensions)
                updateThumbnail(holder, mRecentTasksLoader.getDefaultThumbnail(), false, false);

                holder.iconView = (ImageView) convertView.findViewById(R.id.app_icon);
                holder.labelView = (TextView) convertView.findViewById(R.id.app_label);
                holder.descriptionView = (TextView) convertView.findViewById(R.id.app_description);

                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            // index is reverse since most recent appears at the bottom...
            final int index = mRecentTaskDescriptions.size() - position - 1;

            final TaskDescription td = mRecentTaskDescriptions.get(index);
            holder.iconView.setImageDrawable(td.getIcon());
            holder.labelView.setText(td.getLabel());
            holder.thumbnailView.setContentDescription(td.getLabel());
            updateThumbnail(holder, td.getThumbnail(), true, false);

            holder.thumbnailView.setTag(td);
            holder.thumbnailView.setOnLongClickListener(new OnLongClickDelegate(convertView));
            holder.taskDescription = td;

            return convertView;
        }
    }

    public int numItemsInOneScreenful() {
        if (mRecentsContainer instanceof RecentsScrollView){
            RecentsScrollView scrollView
                    = (RecentsScrollView) mRecentsContainer;
            return scrollView.numItemsInOneScreenful();
        }  else {
            throw new IllegalArgumentException("missing Recents[Horizontal]ScrollView");
        }
    }

    public boolean isInContentArea(int x, int y) {
        // use mRecentsContainer's exact bounds to determine horizontal position
        final int l = mRecentsContainer.getLeft();
        final int r = mRecentsContainer.getRight();
        final int t = mRecentsContainer.getTop();
        final int b = mRecentsContainer.getBottom();
        return x >= l && x < r && y >= t && y < b;
    }

    public void show(boolean show) {
        show(show, null, false, false);
    }

    public void show(boolean show, ArrayList<TaskDescription> recentTaskDescriptions,
            boolean firstScreenful, boolean waitingForWindowAnimation) {
        mWaitingForWindowAnimation = waitingForWindowAnimation;
        if (show) {
            mWaitingToShow = true;
            refreshRecentTasksList(recentTaskDescriptions, firstScreenful);
            showIfReady();
        } else {
            showImpl(false);
        }
    }

    private void showIfReady() {
        // mWaitingToShow => there was a touch up on the recents button
        // mRecentTaskDescriptions != null => we've created views for the first screenful of items
        if (mWaitingToShow && mRecentTaskDescriptions != null) {
            showImpl(true);
        }
    }

    static void sendCloseSystemWindows(Context context, String reason) {
        if (ActivityManagerNative.isSystemReady()) {
            try {
                ActivityManagerNative.getDefault().closeSystemDialogs(reason);
            } catch (RemoteException e) {
            }
        }
    }

    private void showImpl(boolean show) {
        sendCloseSystemWindows(mContext, BaseStatusBar.SYSTEM_DIALOG_REASON_RECENT_APPS);

        mShowing = show;

        if (show) {
            // if there are no apps, bring up a "No recent apps" message
            boolean noApps = mRecentTaskDescriptions != null
                    && (mRecentTaskDescriptions.size() == 0);
            mRecentsNoApps.setAlpha(1f);
            mRecentsNoApps.setVisibility(noApps ? View.VISIBLE : View.INVISIBLE);
            mClearRecents.setVisibility(noApps ? View.GONE : View.VISIBLE);
            onAnimationEnd(null);
            setFocusable(true);
            setFocusableInTouchMode(true);
            requestFocus();
        } else {
            mWaitingToShow = false;
            // call onAnimationEnd() and clearRecentTasksList() in onUiHidden()
            if (mPopup != null) {
                mPopup.dismiss();
            }
        }
    }

    public void onUiHidden() {
        if (!mShowing && mRecentTaskDescriptions != null) {
            onAnimationEnd(null);
            clearRecentTasksList();
        }
    }

    public void dismiss() {
        hide(true);
    }

    public void hide(boolean animate) {
        if (!animate) {
            setVisibility(View.GONE);
        }
        if (mBar != null) {
            show(false);
        }
    }

    public void handleShowBackground(boolean show) {
        if (show) {
            mRecentsScrim.setBackgroundResource(R.drawable.status_bar_recents_background_solid);
        } else {
            mRecentsScrim.setBackgroundDrawable(null);
        }
    }

    public boolean isRecentsVisible() {
        return getVisibility() == VISIBLE;
    }

    public void onAnimationCancel(Animator animation) {
    }

    public void onAnimationEnd(Animator animation) {
        if (mShowing) {
            final LayoutTransition transitioner = new LayoutTransition();
            ((ViewGroup)mRecentsContainer).setLayoutTransition(transitioner);
            createCustomAnimations(transitioner);
        } else {
            ((ViewGroup)mRecentsContainer).setLayoutTransition(null);
            clearRecentTasksList();
        }
    }

    public void onAnimationRepeat(Animator animation) {
    }

    public void onAnimationStart(Animator animation) {
    }


    /**
     * We need to be aligned at the bottom.  LinearLayout can't do this, so instead,
     * let LinearLayout do all the hard work, and then shift everything down to the bottom.
     */
    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        mChoreo.setPanelHeight(mRecentsContainer.getHeight());
    }

    @Override
    public boolean dispatchHoverEvent(MotionEvent event) {
        // Ignore hover events outside of this panel bounds since such events
        // generate spurious accessibility events with the panel content when
        // tapping outside of it, thus confusing the user.
        final int x = (int) event.getX();
        final int y = (int) event.getY();
        if (x >= 0 && x < getWidth() && y >= 0 && y < getHeight()) {
            return super.dispatchHoverEvent(event);
        }
        return true;
    }

    /**
     * Whether the panel is showing, or, if it's animating, whether it will be
     * when the animation is done.
     */
    public boolean isShowing() {
        return mShowing;
    }

    public void setRecentTasksLoader(RecentTasksLoader loader) {
        mRecentTasksLoader = loader;
    }

    public RecentsPanelView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RecentsPanelView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mContext = context;
        updateValuesFromResources();
    }

    public void updateValuesFromResources() {
        final Resources res = mContext.getResources();
        mThumbnailWidth = Math.round(res.getDimension(R.dimen.status_bar_recents_thumbnail_width));
        mFitThumbnailToXY = res.getBoolean(R.bool.config_recents_thumbnail_image_fits_to_xy);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mRecentsContainer = (ViewGroup) findViewById(R.id.recents_container);
        mListAdapter = new TaskDescriptionAdapter(mContext);
        if (mRecentsContainer instanceof RecentsHorizontalScrollView){
            RecentsHorizontalScrollView scrollView
                    = (RecentsHorizontalScrollView) mRecentsContainer;
            scrollView.setAdapter(mListAdapter);
            scrollView.setCallback(this);
        } else if (mRecentsContainer instanceof RecentsVerticalScrollView){
            RecentsVerticalScrollView scrollView
                    = (RecentsVerticalScrollView) mRecentsContainer;
            scrollView.setAdapter(mListAdapter);
            scrollView.setCallback(this);
        }
        else {
            throw new IllegalArgumentException("missing Recents[Horizontal]ScrollView");
        }

        mRecentsScrim = findViewById(R.id.recents_bg_protect);
        mRecentsNoApps = findViewById(R.id.recents_no_apps);

        mClearRecents = (ImageView) findViewById(R.id.recents_clear);
        if (mClearRecents != null){
            mClearRecents.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    mRecentsContainer.removeAllViewsInLayout();
                }
            });
        }

        if (mRecentsScrim != null) {
            mHighEndGfx = ActivityManager.isHighEndGfx();
            if (!mHighEndGfx) {
                mRecentsScrim.setBackground(null);
            } else if (mRecentsScrim.getBackground() instanceof BitmapDrawable) {
                // In order to save space, we make the background texture repeat in the Y direction
                ((BitmapDrawable) mRecentsScrim.getBackground()).setTileModeY(TileMode.REPEAT);
            }
        }
    }

    private void createCustomAnimations(LayoutTransition transitioner) {
        transitioner.setDuration(200);
        transitioner.setStartDelay(LayoutTransition.CHANGE_DISAPPEARING, 0);
        transitioner.setAnimator(LayoutTransition.DISAPPEARING, null);
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (DEBUG) Log.v(TAG, "onVisibilityChanged(" + changedView + ", " + visibility + ")");

        if (mRecentsContainer instanceof RecentsHorizontalScrollView) {
            ((RecentsHorizontalScrollView) mRecentsContainer).onRecentsVisibilityChanged();
        } else if (mRecentsContainer instanceof RecentsVerticalScrollView) {
            ((RecentsVerticalScrollView) mRecentsContainer).onRecentsVisibilityChanged();
        } else {
            throw new IllegalArgumentException("missing Recents[Horizontal]ScrollView");
        }
    }

    public void setMinSwipeAlpha(float minAlpha) {
        if (mRecentsContainer instanceof RecentsScrollView){
            RecentsScrollView scrollView
                = (RecentsScrollView) mRecentsContainer;
            scrollView.setMinSwipeAlpha(minAlpha);
        }
    }

    private void updateThumbnail(ViewHolder h, Bitmap thumbnail, boolean show, boolean anim) {
        if (thumbnail != null) {
            // Should remove the default image in the frame
            // that this now covers, to improve scrolling speed.
            // That can't be done until the anim is complete though.
            h.thumbnailViewImage.setImageBitmap(thumbnail);

            // scale the image to fill the full width of the ImageView. do this only if
            // we haven't set a bitmap before, or if the bitmap size has changed
            if (h.thumbnailViewImageBitmap == null ||
                h.thumbnailViewImageBitmap.getWidth() != thumbnail.getWidth() ||
                h.thumbnailViewImageBitmap.getHeight() != thumbnail.getHeight()) {
                if (mFitThumbnailToXY) {
                    h.thumbnailViewImage.setScaleType(ScaleType.FIT_XY);
                } else {
                    Matrix scaleMatrix = new Matrix();
                    float scale = mThumbnailWidth / (float) thumbnail.getWidth();
                    scaleMatrix.setScale(scale, scale);
                    h.thumbnailViewImage.setScaleType(ScaleType.MATRIX);
                    h.thumbnailViewImage.setImageMatrix(scaleMatrix);
                }
            }
            if (show && h.thumbnailView.getVisibility() != View.VISIBLE) {
                if (anim) {
                    h.thumbnailView.setAnimation(
                            AnimationUtils.loadAnimation(mContext, R.anim.recent_appear));
                }
                h.thumbnailView.setVisibility(View.VISIBLE);
            }
            h.thumbnailViewImageBitmap = thumbnail;
        }
    }

    void onTaskThumbnailLoaded(TaskDescription ad) {
        synchronized (ad) {
            if (mRecentsContainer != null) {
                ViewGroup container = mRecentsContainer;
                if (container instanceof HorizontalScrollView
                        || container instanceof ScrollView) {
                    container = (ViewGroup)container.findViewById(
                            R.id.recents_linear_layout);
                }
                // Look for a view showing this thumbnail, to update.
                for (int i=0; i<container.getChildCount(); i++) {
                    View v = container.getChildAt(i);
                    if (v.getTag() instanceof ViewHolder) {
                        ViewHolder h = (ViewHolder)v.getTag();
                        if (h.taskDescription == ad) {
                            // only fade in the thumbnail if recents is already visible-- we
                            // show it immediately otherwise
                            boolean animateShow = mShowing &&
                                mRecentsContainer.getAlpha() > ViewConfiguration.ALPHA_THRESHOLD;
                            updateThumbnail(h, ad.getThumbnail(), true, animateShow);
                        }
                    }
                }
            }
        }
    }

    // additional optimization when we have sofware system buttons - start loading the recent
    // tasks on touch down
    @Override
    public boolean onTouch(View v, MotionEvent ev) {
        /* When recent panel button pressed rapidly with 5+ apps open,
         * deadlock happen between view not visible and mShowing
         * remains true.
         * Make sure mShowing is false when view is not visible.*/
        if (getVisibility() != VISIBLE)
            mShowing = false;

        if (!mShowing) {
            int action = ev.getAction() & MotionEvent.ACTION_MASK;
            if (action == MotionEvent.ACTION_DOWN) {
                // If we set our visibility to INVISIBLE here, we avoid an extra call to
                // onLayout later when we become visible (because onLayout is always called
                // when going from GONE)
                post(mPreloadTasksRunnable);
            } else if (action == MotionEvent.ACTION_CANCEL) {
                setVisibility(GONE);
                clearRecentTasksList();
                // Remove the preloader if we haven't called it yet
                removeCallbacks(mPreloadTasksRunnable);
            } else if (action == MotionEvent.ACTION_UP) {
                // Remove the preloader if we haven't called it yet
                removeCallbacks(mPreloadTasksRunnable);
                if (!v.isPressed()) {
                    setVisibility(GONE);
                    clearRecentTasksList();
                }
            }
        }
        return false;
    }

    public void onWindowAnimationStart() {
        if (mItemToAnimateInWhenWindowAnimationIsFinished != null) {
            final int startDelay = 150;
            final int duration = 250;
            final ViewHolder holder = mItemToAnimateInWhenWindowAnimationIsFinished;
            final TimeInterpolator cubic = new DecelerateInterpolator(1.5f);
            for (View v :
                new View[] { holder.iconView, holder.labelView, holder.calloutLine }) {
                if (v != null) {
                    v.animate().translationX(0).translationY(0).alpha(1f).setStartDelay(startDelay)
                            .setDuration(duration).setInterpolator(cubic);
                }
            }
            mItemToAnimateInWhenWindowAnimationIsFinished = null;
            mWaitingForWindowAnimation = false;
        }
    }

    public void clearRecentTasksList() {
        // Clear memory used by screenshots
        if (mRecentTaskDescriptions != null) {
            mRecentTasksLoader.cancelLoadingThumbnailsAndIcons(this);
            mRecentTaskDescriptions.clear();
            mListAdapter.notifyDataSetInvalidated();
            mRecentTasksDirty = true;
        }
    }

    public void onTaskLoadingCancelled() {
        // Gets called by RecentTasksLoader when it's cancelled
        if (mRecentTaskDescriptions != null) {
            mRecentTaskDescriptions = null;
            mListAdapter.notifyDataSetInvalidated();
        }
    }

    public void refreshViews() {
        mListAdapter.notifyDataSetInvalidated();
        updateUiElements();
        showIfReady();
    }    

    public void refreshRecentTasksList() {
        refreshRecentTasksList(null, false);
    }

    private void refreshRecentTasksList(
            ArrayList<TaskDescription> recentTasksList, boolean firstScreenful) {
        if (mRecentTaskDescriptions == null && recentTasksList != null) {
            onTasksLoaded(recentTasksList, firstScreenful);
        } else {
            mRecentTasksLoader.loadTasksInBackground();
        }
    }

    public void onTasksLoaded(ArrayList<TaskDescription> tasks, boolean firstScreenful) {
        mNumItemsWaitingForThumbnailsAndIcons = firstScreenful
                ? tasks.size() : mRecentTaskDescriptions == null
                        ? 0 : mRecentTaskDescriptions.size();
        if (mRecentTaskDescriptions == null) {
            mRecentTaskDescriptions = new ArrayList<TaskDescription>(tasks);
        } else {
            mRecentTaskDescriptions.addAll(tasks);
        }
        if (((RecentsActivity) mContext).isActivityShowing()) {
            refreshViews();
        }
    }

    public ArrayList<TaskDescription> getRecentTasksList() {
        return mRecentTaskDescriptions;
    }

    private void updateUiElements() {
        final int items = mRecentTaskDescriptions != null
                ? mRecentTaskDescriptions.size() : 0;

        mRecentsContainer.setVisibility(items > 0 ? View.VISIBLE : View.GONE);

        // Set description for accessibility
        int numRecentApps = mRecentTaskDescriptions != null
                ? mRecentTaskDescriptions.size() : 0;
        String recentAppsAccessibilityDescription;
        if (numRecentApps == 0) {
            recentAppsAccessibilityDescription =
                getResources().getString(R.string.status_bar_no_recent_apps);
        } else {
            recentAppsAccessibilityDescription = getResources().getQuantityString(
                R.plurals.status_bar_accessibility_recent_apps, numRecentApps, numRecentApps);
        }
        setContentDescription(recentAppsAccessibilityDescription);
    }

    public boolean simulateClick(int persistentTaskId) {
        if (mRecentsContainer instanceof RecentsScrollView){
            RecentsScrollView scrollView
                = (RecentsScrollView) mRecentsContainer;
            View v = scrollView.findViewForTask(persistentTaskId);
            if (v != null) {
                handleOnClick(v);
                return true;
            }
        }
        return false;
    }

    public void handleOnClick(View view) {
        TaskDescription ad = ((ViewHolder) view.getTag()).taskDescription;
        final Context context = view.getContext();
        final ActivityManager am = (ActivityManager)
                context.getSystemService(Context.ACTIVITY_SERVICE);
        if (ad.taskId >= 0) {
            // This is an active task; it should just go to the foreground.
            am.moveTaskToFront(ad.taskId, ActivityManager.MOVE_TASK_WITH_HOME);
        } else {
            Intent intent = ad.intent;
            intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY
                    | Intent.FLAG_ACTIVITY_TASK_ON_HOME
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            if (DEBUG) Log.v(TAG, "Starting activity " + intent);
            context.startActivity(intent);
        }
        hide(true);
    }

    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        handleOnClick(view);
    }

    public void handleSwipe(View view) {
        TaskDescription ad = ((ViewHolder) view.getTag()).taskDescription;
        if (DEBUG) Log.v(TAG, "Jettison " + ad.getLabel());
        mRecentTaskDescriptions.remove(ad);

        // Handled by widget containers to enable LayoutTransitions properly
        // mListAdapter.notifyDataSetChanged();

        if (mRecentTaskDescriptions.size() == 0) {
            hide(false);
        }

        // Currently, either direction means the same thing, so ignore direction and remove
        // the task.
        final ActivityManager am = (ActivityManager)
                mContext.getSystemService(Context.ACTIVITY_SERVICE);
        am.removeTask(ad.persistentTaskId, ActivityManager.REMOVE_TASK_KILL_PROCESS);

        // Accessibility feedback
        setContentDescription(
                mContext.getString(R.string.accessibility_recents_item_dismissed, ad.getLabel()));
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED);
        setContentDescription(null);
    }

    private void startApplicationDetailsActivity(String packageName) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(intent);
    }

    public void handleLongPress(
            final View selectedView, final View anchorView, final View thumbnailView) {
        thumbnailView.setSelected(true);
        PopupMenu popup = new PopupMenu(mContext, anchorView == null ? selectedView : anchorView);
        popup.getMenuInflater().inflate(R.menu.recent_popup_menu, popup.getMenu());
        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            public boolean onMenuItemClick(MenuItem item) {
                if (item.getItemId() == R.id.recent_remove_item) {
                    mRecentsContainer.removeViewInLayout(selectedView);
                } else if (item.getItemId() == R.id.recent_inspect_item) {
                    ViewHolder viewHolder = (ViewHolder) selectedView.getTag();
                    if (viewHolder != null) {
                        final TaskDescription ad = viewHolder.taskDescription;
                        startApplicationDetailsActivity(ad.packageName);
                        show(false);
                    } else {
                        throw new IllegalStateException("Oops, no tag on view " + selectedView);
                    }
                } else {
                    return false;
                }
                return true;
            }
        });
        popup.setOnDismissListener(new PopupMenu.OnDismissListener() {
            public void onDismiss(PopupMenu menu) {
                thumbnailView.setSelected(false);
            }
        });
        popup.show();
    }
}
