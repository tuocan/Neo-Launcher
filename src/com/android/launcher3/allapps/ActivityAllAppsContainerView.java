/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.launcher3.allapps;

import static com.android.launcher3.allapps.ActivityAllAppsContainerView.AdapterHolder.MAIN;
import static com.android.launcher3.allapps.ActivityAllAppsContainerView.AdapterHolder.SEARCH;
import static com.android.launcher3.allapps.BaseAllAppsAdapter.VIEW_TYPE_WORK_DISABLED_CARD;
import static com.android.launcher3.allapps.BaseAllAppsAdapter.VIEW_TYPE_WORK_EDU_CARD;
import static com.android.launcher3.allapps.WorkProfileManager.STATE_DISABLED;
import static com.android.launcher3.allapps.WorkProfileManager.STATE_ENABLED;
import static com.android.launcher3.config.FeatureFlags.ALL_APPS_GONE_VISIBILITY;
import static com.android.launcher3.config.FeatureFlags.ENABLE_ALL_APPS_RV_PREINFLATION;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_ALLAPPS_COUNT;
import static com.android.launcher3.model.BgDataModel.Callbacks.FLAG_QUIET_MODE_ENABLED;
import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;
import static com.android.launcher3.util.ScrollableLayoutManager.PREDICTIVE_BACK_MIN_SCALE;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Path.Direction;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.Process;
import android.os.UserManager;
import android.util.AttributeSet;
import android.util.FloatProperty;
import android.util.Log;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.Px;
import androidx.annotation.VisibleForTesting;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.RecyclerView;

import com.android.launcher3.DeviceProfile;
import com.android.launcher3.DeviceProfile.OnDeviceProfileChangeListener;
import com.android.launcher3.DragSource;
import com.android.launcher3.DropTarget.DragObject;
import com.android.launcher3.Insettable;
import com.android.launcher3.InsettableFrameLayout;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.allapps.BaseAllAppsAdapter.AdapterItem;
import com.android.launcher3.allapps.search.AllAppsSearchUiDelegate;
import com.android.launcher3.allapps.search.SearchAdapterProvider;
import com.android.launcher3.config.FeatureFlags;
import com.android.launcher3.keyboard.FocusedItemDecorator;
import com.android.launcher3.model.StringCache;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.util.ItemInfoMatcher;
import com.android.launcher3.util.Themes;
import com.android.launcher3.views.ActivityContext;
import com.android.launcher3.views.BaseDragLayer;
import com.android.launcher3.views.RecyclerViewFastScroller;
import com.android.launcher3.views.ScrimView;
import com.android.launcher3.views.SpringRelativeLayout;
import com.android.launcher3.workprofile.PersonalWorkSlidingTabStrip;
import com.saggitt.omega.allapps.AllAppsTabItem;
import com.saggitt.omega.allapps.AllAppsTabs;
import com.saggitt.omega.allapps.AllAppsTabsController;
import com.saggitt.omega.preferences.NeoPrefs;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * All apps container view with search support for use in a dragging activity.
 *
 * @param <T> Type of context inflating all apps.
 */
public class ActivityAllAppsContainerView<T extends Context & ActivityContext>
        extends SpringRelativeLayout implements DragSource, Insettable,
        OnDeviceProfileChangeListener, PersonalWorkSlidingTabStrip.OnActivePageChangedListener,
        ScrimView.ScrimDrawingController {


    public static final FloatProperty<ActivityAllAppsContainerView<?>> BOTTOM_SHEET_ALPHA =
            new FloatProperty<>("bottomSheetAlpha") {
                @Override
                public Float get(ActivityAllAppsContainerView<?> containerView) {
                    return containerView.mBottomSheetAlpha;
                }

                @Override
                public void setValue(ActivityAllAppsContainerView<?> containerView, float v) {
                    containerView.setBottomSheetAlpha(v);
                }
            };

    public static final float PULL_MULTIPLIER = .02f;
    public static final float FLING_VELOCITY_MULTIPLIER = 1200f;
    protected static final String BUNDLE_KEY_CURRENT_PAGE = "launcher.allapps.current_page";
    private static final long DEFAULT_SEARCH_TRANSITION_DURATION_MS = 300;
    // Render the header protection at all times to debug clipping issues.
    private static final boolean DEBUG_HEADER_PROTECTION = false;
    /** Context of an activity or window that is inflating this container. */
    protected final T mActivityContext;
    protected final List<ActivityAllAppsContainerView<?>.AdapterHolder> mAH;
    protected final Predicate<ItemInfo> mPersonalMatcher = ItemInfoMatcher.ofUser(
            Process.myUserHandle());
    protected final WorkProfileManager mWorkManager;
    protected final Point mFastScrollerOffset = new Point();
    protected final int mScrimColor;
    protected final float mHeaderThreshold;
    protected final AllAppsSearchUiDelegate mSearchUiDelegate;

    // Used to animate Search results out and A-Z apps in, or vice-versa.
    private final SearchTransitionController mSearchTransitionController;
    private final Paint mHeaderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect mInsets = new Rect();
    private final AllAppsStore<T> mAllAppsStore;
    private final RecyclerView.OnScrollListener mScrollListener =
            new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                    updateHeaderScroll(recyclerView.computeVerticalScrollOffset());
                }
            };
    private final Paint mNavBarScrimPaint;
    private final int mHeaderProtectionColor;
    private final Path mTmpPath = new Path();
    private final RectF mTmpRectF = new RectF();
    protected AllAppsPagedView mViewPager;
    protected FloatingHeaderView mHeader;
    protected View mBottomSheetBackground;
    protected RecyclerViewFastScroller mFastScroller;

    /**
     * View that defines the search box. Result is rendered inside {@link #mSearchRecyclerView}.
     */
    protected View mSearchContainer;
    protected SearchUiManager mSearchUiManager;
    protected boolean mUsingTabs;
    protected RecyclerViewFastScroller mTouchHandler;

    /** {@code true} when rendered view is in search state instead of the scroll state. */
    private boolean mIsSearching;
    private boolean mRebindAdaptersAfterSearchAnimation;
    private int mNavBarScrimHeight = 0;
    private SearchRecyclerView mSearchRecyclerView;
    protected SearchAdapterProvider<?> mMainAdapterProvider;
    private View mBottomSheetHandleArea;
    private boolean mHasWorkApps;
    private float[] mBottomSheetCornerRadii;
    private ScrimView mScrimView;
    private int mHeaderColor;
    private int mBottomSheetBackgroundColor;
    private float mBottomSheetAlpha = 1f;
    private boolean mForceBottomSheetVisible;
    private int mTabsProtectionAlpha;
    @Nullable private AllAppsTransitionController mAllAppsTransitionController;
    private final AllAppsTabsController mTabsController;
    private final NeoPrefs prefs;

    public ActivityAllAppsContainerView(Context context) {
        this(context, null);
    }

    public ActivityAllAppsContainerView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ActivityAllAppsContainerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mActivityContext = ActivityContext.lookupContext(context);
        mAllAppsStore = new AllAppsStore<>(mActivityContext);
        prefs = NeoPrefs.getInstance(context);

        mScrimColor = Themes.getAttrColor(context, R.attr.allAppsScrimColor);
        mHeaderThreshold = getResources().getDimensionPixelSize(
                R.dimen.dynamic_grid_cell_border_spacing);
        mHeaderProtectionColor = Themes.getAttrColor(context, R.attr.allappsHeaderProtectionColor);

        mWorkManager = new WorkProfileManager(
                mActivityContext.getSystemService(UserManager.class),
                this, mActivityContext.getStatsLogManager());
        mAH = new ArrayList<>();
        mNavBarScrimPaint = new Paint();
        mNavBarScrimPaint.setColor(Themes.getNavBarScrimColor(mActivityContext));

        AllAppsStore.OnUpdateListener onAppsUpdated = this::onAppsUpdated;
        mAllAppsStore.addUpdateListener(onAppsUpdated);

        AllAppsTabs allAppsTabs = new AllAppsTabs(context);
        mTabsController = new AllAppsTabsController(allAppsTabs, this);

        // This is a focus listener that proxies focus from a view into the list view.  This is to
        // work around the search box from getting first focus and showing the cursor.
        setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus && getActiveRecyclerView() != null) {
                getActiveRecyclerView().requestFocus();
            }
        });
        mSearchUiDelegate = createSearchUiDelegate();
        initContent();

        mSearchTransitionController = new SearchTransitionController(this);
    }

    /** Creates the delegate for initializing search. */
    protected AllAppsSearchUiDelegate createSearchUiDelegate() {
        return new AllAppsSearchUiDelegate(this);
    }

    public AllAppsSearchUiDelegate getSearchUiDelegate() {
        return mSearchUiDelegate;
    }

    /**
     * Initializes the view hierarchy and internal variables. Any initialization which actually uses
     * these members should be done in {@link #onFinishInflate()}.
     * In terms of subclass initialization, the following would be parallel order for activity:
     *   initContent -> onPreCreate
     *   constructor/init -> onCreate
     *   onFinishInflate -> onPostCreate
     */
    protected void initContent() {
        mMainAdapterProvider = mSearchUiDelegate.createMainAdapterProvider();

        createHolders();

        getLayoutInflater().inflate(R.layout.all_apps_content, this);
        mHeader = findViewById(R.id.all_apps_header);
        mBottomSheetBackground = findViewById(R.id.bottom_sheet_background);
        mBottomSheetHandleArea = findViewById(R.id.bottom_sheet_handle_area);
        mSearchRecyclerView = findViewById(R.id.search_results_list_view);
        mFastScroller = findViewById(R.id.fast_scroller);
        mFastScroller.setPopupView(findViewById(R.id.fast_scroller_popup));

        mSearchContainer = inflateSearchBar();
        if (!isSearchBarFloating()) {
            // Add the search box above everything else in this container (if the flag is enabled,
            // it's added to drag layer in onAttach instead).
            addView(mSearchContainer);
        }
        mSearchUiManager = (SearchUiManager) mSearchContainer;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mAH.get(getSearchHolderIndex()).setup(mSearchRecyclerView,
                /* Filter out A-Z apps */ itemInfo -> false);
        rebindAdapters(mUsingTabs, true);
        float cornerRadius = Themes.getDialogCornerRadius(getContext());
        mBottomSheetCornerRadii = new float[]{
                cornerRadius,
                cornerRadius, // Top left radius in px
                cornerRadius,
                cornerRadius, // Top right radius in px
                0,
                0, // Bottom right
                0,
                0 // Bottom left
        };
        final TypedValue value = new TypedValue();
        getContext().getTheme().resolveAttribute(android.R.attr.colorBackground, value, true);
        mBottomSheetBackgroundColor = value.data;
        updateBackgroundVisibility(mActivityContext.getDeviceProfile());
        mSearchUiManager.initializeSearch(this);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (isSearchBarFloating()) {
            // Note: for Taskbar this is removed in TaskbarAllAppsController#cleanUpOverlay when the
            // panel is closed. Can't do so in onDetach because we are also a child of drag layer
            // so can't remove its views during that dispatch.
            mActivityContext.getDragLayer().addView(mSearchContainer);
            mSearchUiDelegate.onInitializeSearchBar();
        }
        mActivityContext.addOnDeviceProfileChangeListener(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mActivityContext.removeOnDeviceProfileChangeListener(this);
    }

    public SearchUiManager getSearchUiManager() {
        return mSearchUiManager;
    }

    public View getBottomSheetBackground() {
        return mBottomSheetBackground;
    }

    /**
     * Temporarily force the bottom sheet to be visible on non-tablets.
     *
     * @param force {@code true} means bottom sheet will be visible on phones until {@code reset()}.
     */
    public void forceBottomSheetVisible(boolean force) {
        mForceBottomSheetVisible = force;
        updateBackgroundVisibility(mActivityContext.getDeviceProfile());
    }

    public View getSearchView() {
        return mSearchContainer;
    }

    /** Invoke when the current search session is finished. */
    public void onClearSearchResult() {
        getMainAdapterProvider().clearHighlightedItem();
        animateToSearchState(false);
        rebindAdapters(mUsingTabs);
    }

    /**
     * Sets results list for search
     */
    public void setSearchResults(ArrayList<AdapterItem> results) {
        getMainAdapterProvider().clearHighlightedItem();
        if (getSearchResultList().setSearchResults(results)) {
            getSearchRecyclerView().onSearchResultsChanged();
        }
        if (results != null) {
            animateToSearchState(true);
        }
    }

    /**
     * Sets results list for search.
     *
     * @param searchResultCode indicates if the result is final or intermediate for a given query
     *                         since we can get search results from multiple sources.
     */
    public void setSearchResults(ArrayList<AdapterItem> results, int searchResultCode) {
        setSearchResults(results);
        mSearchUiDelegate.onSearchResultsChanged(results, searchResultCode);
    }

    private void animateToSearchState(boolean goingToSearch) {
        animateToSearchState(goingToSearch, DEFAULT_SEARCH_TRANSITION_DURATION_MS);
    }

    public void setAllAppsTransitionController(
            AllAppsTransitionController allAppsTransitionController) {
        mAllAppsTransitionController = allAppsTransitionController;
    }

    public void animateToSearchState(boolean goingToSearch, long durationMs) {
        if (!mSearchTransitionController.isRunning() && goingToSearch == isSearching()) {
            return;
        }
        mFastScroller.setVisibility(goingToSearch ? INVISIBLE : VISIBLE);
        if (goingToSearch) {
            // Fade out the button to pause work apps.
            mWorkManager.onActivePageChanged(getSearchHolderIndex());
        } else if (mAllAppsTransitionController != null) {
            // If exiting search, revert predictive back scale on all apps
            mAllAppsTransitionController.animateAllAppsToNoScale();
        }
        mSearchTransitionController.animateToSearchState(goingToSearch, durationMs,
                /* onEndRunnable = */ () -> {
                    mIsSearching = goingToSearch;
                    updateSearchResultsVisibility();
                    int previousPage = getCurrentPage();
                    if (mRebindAdaptersAfterSearchAnimation) {
                        rebindAdapters(false);
                        mRebindAdaptersAfterSearchAnimation = false;
                    }
                    if (goingToSearch) {
                        mSearchUiDelegate.onAnimateToSearchStateCompleted();
                    } else {
                        setSearchResults(null);
                        if (mViewPager != null) {
                            mViewPager.setCurrentPage(previousPage);
                        }
                        onActivePageChanged(previousPage);
                    }
                });
    }

    public boolean shouldContainerScroll(MotionEvent ev) {
        BaseDragLayer dragLayer = mActivityContext.getDragLayer();
        // IF the MotionEvent is inside the search box or handle area, and the container keeps on
        // receiving touch input, container should move down.
        if (dragLayer.isEventOverView(mSearchContainer, ev)
                || dragLayer.isEventOverView(mBottomSheetHandleArea, ev)) {
            return true;
        }
        AllAppsRecyclerView rv = getActiveRecyclerView();
        if (rv == null) {
            return true;
        }
        if (rv.getScrollbar() != null
                && rv.getScrollbar().getThumbOffsetY() >= 0
                && dragLayer.isEventOverView(rv.getScrollbar(), ev)) {
            return false;
        }
        // Scroll if not within the container view (e.g. over large-screen scrim).
        if (!dragLayer.isEventOverView(getVisibleContainerView(), ev)) {
            return true;
        }
        return rv.shouldContainerScroll(ev, dragLayer);
    }

    /**
     * Resets the UI to be ready for fresh interactions in the future. Exits search and returns to
     * A-Z apps list.
     *
     * @param animate Whether to animate the header during the reset (e.g. switching profile tabs).
     */
    public void reset(boolean animate) {
        reset(animate, false);
    }

    /**
     * Resets the UI to be ready for fresh interactions in the future.
     *
     * @param animate Whether to animate the header during the reset (e.g. switching profile tabs).
     * @param exitSearch Whether to force exit the search state and return to A-Z apps list.
     */
    public void reset(boolean animate, boolean force) {
        if (force || !prefs.getDrawerSaveScrollPosition().getValue()) {
            for (int i = 0; i < mAH.size(); i++) {
                if (mAH.get(i).mRecyclerView != null) {
                    mAH.get(i).mRecyclerView.scrollToTop();
                }
            }
            if (mTouchHandler != null) {
                mTouchHandler.endFastScrolling();
            }
            if (mHeader != null && mHeader.getVisibility() == VISIBLE) {
                mHeader.reset(animate);
            }
            forceBottomSheetVisible(false);
            // Reset the base recycler view after transitioning home.
            updateHeaderScroll(0);
            if (force) { // TODO re-evaluate
                // Reset the search bar after transitioning home.
                MAIN_EXECUTOR.getHandler().post(mSearchUiManager::resetSearch);
                // Animate to A-Z with 0 time to reset the animation with proper state management.
                animateToSearchState(false, 0);
            }
        }
        if (isSearching()) {
            mWorkManager.reset();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        mSearchUiManager.preDispatchKeyEvent(event);
        return super.dispatchKeyEvent(event);
    }

    public String getDescription() {
        if (!mUsingTabs && isSearching()) {
            return getContext().getString(R.string.all_apps_search_results);
        } else {
            StringCache cache = mActivityContext.getStringCache();
            if (mUsingTabs) {
                if (cache != null) {
                    return isPersonalTab()
                            ? cache.allAppsPersonalTabAccessibility
                            : cache.allAppsWorkTabAccessibility;
                } else {
                    return isPersonalTab()
                            ? getContext().getString(R.string.all_apps_button_personal_label)
                            : getContext().getString(R.string.all_apps_button_work_label);
                }
            }
            return getContext().getString(R.string.all_apps_button_label);
        }
    }

    public boolean isSearching() {
        return mIsSearching;
    }

    public void onTabChanged(int pos) {
        pos = Utilities.boundToRange(pos, 0, mTabsController.getTabsCount() - 1);
        mHeader.setActiveRV(pos, isSearching());
        if (mAH.get(pos).mRecyclerView != null) {
            mAH.get(pos).mRecyclerView.bindFastScrollbar(mFastScroller);
            //mAH.get(pos).mRecyclerView.setScrollbarColor(prefs.getProfileAccentColor().getColor());
            mTabsController.bindButtons(findViewById(R.id.tabs), mViewPager);
        }
        reset(true, true);
    }

    @Override
    public void onActivePageChanged(int currentActivePage) {
        if (mSearchTransitionController.isRunning()) {
            // Will be called at the end of the animation.
            return;
        }
        if (currentActivePage != getSearchHolderIndex()) {
            mActivityContext.hideKeyboard();
        }
        if (mAH.get(currentActivePage).mRecyclerView != null) {
            mAH.get(currentActivePage).mRecyclerView.bindFastScrollbar(mFastScroller);
        }
        // Header keeps track of active recycler view to properly render header protection.
        mHeader.setActiveRV(currentActivePage, isSearching());
        reset(true /* animate */);

        mWorkManager.onActivePageChanged(currentActivePage);
    }

    public void reloadTabs() {
        mTabsController.reloadTabs();
        rebindAdapters(mTabsController.getShouldShowTabs(), false);
    }

    private void rebindAdapters(boolean showTabs) {
        rebindAdapters(showTabs, false);
    }

    protected void rebindAdapters(boolean showTabs, boolean force) {
        if (mSearchTransitionController.isRunning()) {
            mRebindAdaptersAfterSearchAnimation = true;
            return;
        }
        updateSearchResultsVisibility();

        int currentTab = mViewPager != null ? mViewPager.getNextPage() : 0;
        if (showTabs == mUsingTabs && !force) {
            return;
        }

        if (isSearching()) {
            mUsingTabs = showTabs;
            mWorkManager.detachWorkModeSwitch();
            return;
        }

        if (!FeatureFlags.ENABLE_SEARCH_RESULT_BACKGROUND_DRAWABLES.get()) {
            RecyclerView.ItemDecoration decoration = getMainAdapterProvider().getDecorator();
            getSearchRecyclerView().removeItemDecoration(decoration);
            getSearchRecyclerView().addItemDecoration(decoration);
        }

        // replaceAppsRVcontainer() needs to use both mUsingTabs value to remove the old view AND
        // showTabs value to create new view. Hence the mUsingTabs new value assignment MUST happen
        // after this call.
        createHolders();
        replaceAppsRVContainer(showTabs);
        mUsingTabs = showTabs;

        mTabsController.unregisterIconContainers(mAllAppsStore);
        mAllAppsStore.unregisterIconContainer(mAH.get(getSearchHolderIndex()).mRecyclerView);

        if (mTabsController.getTabs().getHasWorkApps()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                mWorkManager.attachWorkModeSwitch();
            }
        }

        if (mUsingTabs) {
            mTabsController.setup(mViewPager);
            AllAppsTabItem tabStrip = findViewById(R.id.tabs);
            tabStrip.inflateButtons(mTabsController.getTabs());
            if (currentTab == 0) {
                tabStrip.setScroll(0, 1);
            }
            onTabChanged(currentTab);

            if (FeatureFlags.ENABLE_EXPANDING_PAUSE_WORK_BUTTON.get()) {
                for (ActivityAllAppsContainerView<?>.AdapterHolder holder : mAH) {
                    if (holder.isWork()) {
                        holder.mRecyclerView.addOnScrollListener(mWorkManager.newScrollListener());
                    }
                }
            }
            //mViewPager.getPageIndicator().setActiveMarker(AdapterHolder.MAIN);
            //setDeviceManagementResources();
            if (mHeader.isSetUp()) {
                onActivePageChanged(mViewPager.getNextPage());
            }
        } else {
            mAH.get(AdapterHolder.MAIN).setup(findViewById(R.id.apps_list_view), null);
            mAH.get(AdapterHolder.WORK).mRecyclerView = null;
        }
        //setUpCustomRecyclerViewPool(mAH, mAllAppsStore.getRecyclerViewPool());
        setupHeader();

        if (isSearchBarFloating()) {
            // Keep the scroller above the search bar.
            RelativeLayout.LayoutParams scrollerLayoutParams =
                    (LayoutParams) mFastScroller.getLayoutParams();
            scrollerLayoutParams.bottomMargin = mSearchContainer.getHeight()
                    + getResources().getDimensionPixelSize(
                    R.dimen.fastscroll_bottom_margin_floating_search);
        }

        // Re-register icon containers
        mTabsController.registerIconContainers(mAllAppsStore);
        mAllAppsStore.registerIconContainer(mAH.get(getSearchHolderIndex()).mRecyclerView);
    }

    /**
     * If {@link ENABLE_ALL_APPS_RV_PREINFLATION} is enabled, wire custom
     * {@link RecyclerView.RecycledViewPool} to main and work {@link AllAppsRecyclerView}.
     *
     * Then if {@link ALL_APPS_GONE_VISIBILITY} is enabled, update max pool size. This is because
     * all apps rv's hidden visibility is changed to {@link View#GONE} from {@link View#INVISIBLE),
     * thus we cannot rely on layout pass to update pool size.
     */
    private static void setUpCustomRecyclerViewPool(
            @NonNull AllAppsRecyclerView mainRecyclerView,
            @Nullable AllAppsRecyclerView workRecyclerView,
            @NonNull RecyclerView.RecycledViewPool recycledViewPool) {
        if (!ENABLE_ALL_APPS_RV_PREINFLATION.get()) {
            return;
        }
        mainRecyclerView.setRecycledViewPool(recycledViewPool);
        if (workRecyclerView != null) {
            workRecyclerView.setRecycledViewPool(recycledViewPool);
        }
        if (ALL_APPS_GONE_VISIBILITY.get()) {
            mainRecyclerView.updatePoolSize();
        }
    }

    private static void setUpCustomRecyclerViewPool(
            @NonNull List<ActivityAllAppsContainerView<?>.AdapterHolder> ahs,
            @NonNull RecyclerView.RecycledViewPool recycledViewPool) {
        if (!ENABLE_ALL_APPS_RV_PREINFLATION.get()) {
            return;
        }
        ahs.forEach(holder -> holder.mRecyclerView.setRecycledViewPool(recycledViewPool));
        if (ALL_APPS_GONE_VISIBILITY.get()) {
            ahs.get(MAIN).mRecyclerView.updatePoolSize();
        }
    }

    protected void replaceAppsRVContainer(boolean showTabs) {
        mAH.forEach(holder -> {
                AllAppsRecyclerView rv = holder.mRecyclerView;
                if (rv != null) {
                    rv.setLayoutManager(null);
                    rv.setAdapter(null);
                }
        });
        View oldView = getAppsRecyclerViewContainer();
        int index = indexOfChild(oldView);
        removeView(oldView);
        int layout = showTabs ? R.layout.all_apps_tabs : R.layout.all_apps_rv_layout;
        final View rvContainer = getLayoutInflater().inflate(layout, this, false);
        addView(rvContainer, index);
        if (showTabs) {
            mViewPager = (AllAppsPagedView) rvContainer;
            mViewPager.addTabs(mTabsController.getTabsCount());
            mViewPager.initParentViews(this);
            mViewPager.getPageIndicator().setOnActivePageChangedListener(this);
            mViewPager.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    @Px final int bottomOffsetPx =
                            (int) (ActivityAllAppsContainerView.this.getMeasuredHeight()
                                    * PREDICTIVE_BACK_MIN_SCALE);
                    outline.setRect(
                            0,
                            0,
                            view.getMeasuredWidth(),
                            view.getMeasuredHeight() + bottomOffsetPx);
                }
            });
            if (mTabsController.getTabs().getHasWorkApps()) {
                mWorkManager.reset();
                post(() -> mAH.get(getWorkHolderIndex()).applyPadding());
            }

        } else {
            mWorkManager.detachWorkModeSwitch();
            mViewPager = null;
        }

        removeCustomRules(rvContainer);
        removeCustomRules(getSearchRecyclerView());
        if (!isSearchSupported()) {
            layoutWithoutSearchContainer(rvContainer, showTabs);
        } else if (isSearchBarFloating()) {
            alignParentTop(rvContainer, showTabs);
            alignParentTop(getSearchRecyclerView(), /* tabs= */ false);
        } else {
            layoutBelowSearchContainer(rvContainer, showTabs);
            layoutBelowSearchContainer(getSearchRecyclerView(), /* tabs= */ false);
        }

        updateSearchResultsVisibility();
    }

    void setupHeader() {
        mHeader.setVisibility(View.VISIBLE);
        boolean tabsHidden = !mUsingTabs;
        mHeader.setup(mAH,
                (SearchRecyclerView) mAH.get(getSearchHolderIndex()).mRecyclerView,
                getCurrentPage(),
                tabsHidden);

        int padding = mHeader.getMaxTranslation();
        mAH.forEach(adapterHolder -> {
            adapterHolder.mPadding.top = padding;
            adapterHolder.applyPadding();
            if (adapterHolder.mRecyclerView != null) {
                adapterHolder.mRecyclerView.scrollToTop();
            }
        });

        removeCustomRules(mHeader);
        if (!isSearchSupported()) {
            layoutWithoutSearchContainer(mHeader, false /* includeTabsMargin */);
        } else if (isSearchBarFloating()) {
            alignParentTop(mHeader, false /* includeTabsMargin */);
        } else {
            layoutBelowSearchContainer(mHeader, false /* includeTabsMargin */);
        }
    }

    protected void updateHeaderScroll(int scrolledOffset) {
        float prog1 = Utilities.boundToRange((float) scrolledOffset / mHeaderThreshold, 0f, 1f);
        int headerColor = getHeaderColor(prog1);
        int tabsAlpha = mHeader.getPeripheralProtectionHeight() == 0 ? 0
                : (int) (Utilities.boundToRange(
                        (scrolledOffset + mHeader.mSnappedScrolledY) / mHeaderThreshold, 0f, 1f)
                        * 255);
        if (headerColor != mHeaderColor || mTabsProtectionAlpha != tabsAlpha) {
            mHeaderColor = headerColor;
            mTabsProtectionAlpha = tabsAlpha;
            invalidateHeader();
        }
        if (mSearchUiManager.getEditText() == null) {
            return;
        }

        float prog = Utilities.boundToRange((float) scrolledOffset / mHeaderThreshold, 0f, 1f);
        boolean bgVisible = mSearchUiManager.getBackgroundVisibility();
        if (scrolledOffset == 0 && !isSearching()) {
            bgVisible = true;
        } else if (scrolledOffset > mHeaderThreshold) {
            bgVisible = false;
        }
        mSearchUiManager.setBackgroundVisibility(bgVisible, 1 - prog);
    }

    protected int getHeaderColor(float blendRatio) {
        return ColorUtils.setAlphaComponent(
                ColorUtils.blendARGB(mScrimColor, mHeaderProtectionColor, blendRatio),
                (int) (mSearchContainer.getAlpha() * 255));
    }

    /**
     * @return true if the search bar is floating above this container (at the bottom of the screen)
     */
    protected boolean isSearchBarFloating() {
        return mSearchUiDelegate.isSearchBarFloating();
    }

    /**
     * Whether the <em>floating</em> search bar should appear as a small pill when not focused.
     * <p>
     * Note: This method mirrors one in LauncherState. For subclasses that use Launcher, it likely
     * makes sense to use that method to derive an appropriate value for the current/target state.
     */
    public boolean shouldFloatingSearchBarBePillWhenUnfocused() {
        return false;
    }

    /**
     * How far from the bottom of the screen the <em>floating</em> search bar should rest when the
     * IME is not present.
     * <p>
     * To hide offscreen, use a negative value.
     * <p>
     * Note: if the provided value is non-negative but less than the current bottom insets, the
     * insets will be applied. As such, you can use 0 to default to this.
     * <p>
     * Note: This method mirrors one in LauncherState. For subclasses that use Launcher, it likely
     * makes sense to use that method to derive an appropriate value for the current/target state.
     */
    public int getFloatingSearchBarRestingMarginBottom() {
        return 0;
    }

    /**
     * How far from the start of the screen the <em>floating</em> search bar should rest.
     * <p>
     * To use original margin, return a negative value.
     * <p>
     * Note: This method mirrors one in LauncherState. For subclasses that use Launcher, it likely
     * makes sense to use that method to derive an appropriate value for the current/target state.
     */
    public int getFloatingSearchBarRestingMarginStart() {
        DeviceProfile dp = mActivityContext.getDeviceProfile();
        return dp.allAppsLeftRightMargin + dp.getAllAppsIconStartMargin();
    }

    /**
     * How far from the end of the screen the <em>floating</em> search bar should rest.
     * <p>
     * To use original margin, return a negative value.
     * <p>
     * Note: This method mirrors one in LauncherState. For subclasses that use Launcher, it likely
     * makes sense to use that method to derive an appropriate value for the current/target state.
     */
    public int getFloatingSearchBarRestingMarginEnd() {
        DeviceProfile dp = mActivityContext.getDeviceProfile();
        return dp.allAppsLeftRightMargin + dp.getAllAppsIconStartMargin();
    }

    private void layoutBelowSearchContainer(View v, boolean includeTabsMargin) {
        if (!(v.getLayoutParams() instanceof RelativeLayout.LayoutParams)) {
            return;
        }

        RelativeLayout.LayoutParams layoutParams = (LayoutParams) v.getLayoutParams();
        layoutParams.addRule(RelativeLayout.ALIGN_TOP, R.id.search_container_all_apps);

        int topMargin = getContext().getResources().getDimensionPixelSize(
                R.dimen.all_apps_header_top_margin);
        if (includeTabsMargin) {
            topMargin += getContext().getResources().getDimensionPixelSize(
                    R.dimen.all_apps_header_pill_height);
        }
        layoutParams.topMargin = topMargin;
    }

    private void alignParentTop(View v, boolean includeTabsMargin) {
        if (!(v.getLayoutParams() instanceof RelativeLayout.LayoutParams)) {
            return;
        }

        RelativeLayout.LayoutParams layoutParams = (LayoutParams) v.getLayoutParams();
        layoutParams.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        layoutParams.topMargin =
                includeTabsMargin
                        ? getContext().getResources().getDimensionPixelSize(
                        R.dimen.all_apps_header_pill_height)
                        : 0;
    }

    private void removeCustomRules(View v) {
        if (!(v.getLayoutParams() instanceof RelativeLayout.LayoutParams)) {
            return;
        }

        RelativeLayout.LayoutParams layoutParams = (LayoutParams) v.getLayoutParams();
        layoutParams.removeRule(RelativeLayout.ABOVE);
        layoutParams.removeRule(RelativeLayout.ALIGN_TOP);
        layoutParams.removeRule(RelativeLayout.ALIGN_PARENT_TOP);
    }

    protected AllAppsGridAdapter<T> createAdapter(AlphabeticalAppsList<T> appsList) {
        return new AllAppsGridAdapter<>(mActivityContext, getLayoutInflater(), appsList,
                mMainAdapterProvider);
    }

    // TODO(b/216683257): Remove when Taskbar All Apps supports search.
    protected boolean isSearchSupported() {
        return true;
    }

    private void layoutWithoutSearchContainer(View v, boolean includeTabsMargin) {
        if (!(v.getLayoutParams() instanceof RelativeLayout.LayoutParams)) {
            return;
        }

        RelativeLayout.LayoutParams layoutParams = (LayoutParams) v.getLayoutParams();
        layoutParams.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        layoutParams.topMargin = getContext().getResources().getDimensionPixelSize(includeTabsMargin
                ? R.dimen.all_apps_header_pill_height
                : R.dimen.all_apps_header_top_margin);
    }

    public boolean isInAllApps() {
        // TODO: Make this abstract
        return true;
    }

    /**
     * Inflates the search bar
     */
    protected View inflateSearchBar() {
        return mSearchUiDelegate.inflateSearchBar();
    }

    /** The adapter provider for the main section. */
    public final SearchAdapterProvider<?> getMainAdapterProvider() {
        return mMainAdapterProvider;
    }

    @Override
    protected void dispatchRestoreInstanceState(SparseArray<Parcelable> sparseArray) {
        try {
            // Many slice view id is not properly assigned, and hence throws null
            // pointer exception in the underneath method. Catching the exception
            // simply doesn't restore these slice views. This doesn't have any
            // user visible effect because because we query them again.
            super.dispatchRestoreInstanceState(sparseArray);
        } catch (Exception e) {
            Log.e("AllAppsContainerView", "restoreInstanceState viewId = 0", e);
        }

        Bundle state = (Bundle) sparseArray.get(R.id.work_tab_state_id, null);
        if (state != null) {
            int currentPage = state.getInt(BUNDLE_KEY_CURRENT_PAGE, 0);
            if (currentPage == getWorkHolderIndex() && mViewPager != null) {
                mViewPager.setCurrentPage(currentPage);
                rebindAdapters(mUsingTabs);
            } else {
                reset(true);
            }
        }
    }

    @Override
    protected void dispatchSaveInstanceState(SparseArray<Parcelable> container) {
        super.dispatchSaveInstanceState(container);
        Bundle state = new Bundle();
        state.putInt(BUNDLE_KEY_CURRENT_PAGE, getCurrentPage());
        container.put(R.id.work_tab_state_id, state);
    }

    private void createHolders() {
        mAH.clear();
        mAH.addAll(mTabsController.createHolders());
        mAH.add(createHolder(SEARCH));
    }

    public AllAppsStore<T> getAppsStore() {
        return mAllAppsStore;
    }

    public WorkProfileManager getWorkManager() {
        return mWorkManager;
    }

    @Override
    public void onDeviceProfileChanged(DeviceProfile dp) {
        for (ActivityAllAppsContainerView<?>.AdapterHolder holder : mAH) {
            holder.mAdapter.setAppsPerRow(dp.numShownAllAppsColumns);
            holder.mAppsList.setNumAppsPerRowAllApps(dp.numShownAllAppsColumns);
            if (holder.mRecyclerView != null) {
                // Remove all views and clear the pool, while keeping the data same. After this
                // call, all the viewHolders will be recreated.
                holder.mRecyclerView.swapAdapter(holder.mRecyclerView.getAdapter(), true);
                holder.mRecyclerView.getRecycledViewPool().clear();
            }
        }
        updateBackgroundVisibility(dp);

        int navBarScrimColor = Themes.getNavBarScrimColor(mActivityContext);
        if (mNavBarScrimPaint.getColor() != navBarScrimColor) {
            mNavBarScrimPaint.setColor(navBarScrimColor);
            invalidate();
        }
    }

    protected void updateBackgroundVisibility(DeviceProfile deviceProfile) {
        boolean visible = deviceProfile.isTablet || mForceBottomSheetVisible;
        mBottomSheetBackground.setVisibility(visible ? View.VISIBLE : View.GONE);
        // Note: For tablets, the opaque background and header protection are added in drawOnScrim.
        // For the taskbar entrypoint, the scrim is drawn by its abstract slide in view container,
        // so its header protection is derived from this scrim instead.
    }

    private void setBottomSheetAlpha(float alpha) {
        // Bottom sheet alpha is always 1 for tablets.
        mBottomSheetAlpha = mActivityContext.getDeviceProfile().isTablet ? 1f : alpha;
    }

    private void onAppsUpdated() {
        boolean force = false;
        if (prefs.getDrawerSeparateWorkApps().getValue()) {
            mHasWorkApps = Stream.of(mAllAppsStore.getApps()).anyMatch(mWorkManager.getMatcher());
            if (!isSearching()) {
                rebindAdapters(mUsingTabs);
                if (mHasWorkApps) {
                    resetWorkProfile();
                }
                AllAppsTabs allAppsTabs = mTabsController.getTabs();
                force = allAppsTabs.getHasWorkApps() != mHasWorkApps;
                allAppsTabs.setHasWorkApps(mHasWorkApps);
                if (mHasWorkApps) {
                    mWorkManager.reset();
                }
            }

            mActivityContext.getStatsLogManager().logger()
                    .withCardinality(mAllAppsStore.getApps().length)
                    .log(LAUNCHER_ALLAPPS_COUNT);
        }
        rebindAdapters(mTabsController.getShouldShowTabs(), force);
    }

    private void resetWorkProfile() {
        boolean isEnabled = !mAllAppsStore.hasModelFlag(FLAG_QUIET_MODE_ENABLED);
        for (ActivityAllAppsContainerView<?>.AdapterHolder adapterHolder : mAH) {
            if (adapterHolder.isWork()) {
                mWorkManager.updateCurrentState(isEnabled ? STATE_ENABLED : STATE_DISABLED);
                adapterHolder.applyPadding();
            }
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        // The AllAppsContainerView houses the QSB and is hence visible from the Workspace
        // Overview states. We shouldn't intercept for the scrubber in these cases.
        if (!isInAllApps()) {
            mTouchHandler = null;
            return false;
        }

        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            AllAppsRecyclerView rv = getActiveRecyclerView();
            if (rv != null && rv.getScrollbar() != null
                    && rv.getScrollbar().isHitInParent(ev.getX(), ev.getY(), mFastScrollerOffset)) {
                mTouchHandler = rv.getScrollbar();
            } else {
                mTouchHandler = null;
            }
        }
        if (mTouchHandler != null) {
            return mTouchHandler.handleTouchEvent(ev, mFastScrollerOffset);
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!isInAllApps()) {
            return false;
        }

        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            AllAppsRecyclerView rv = getActiveRecyclerView();
            if (rv != null && rv.getScrollbar() != null
                    && rv.getScrollbar().isHitInParent(ev.getX(), ev.getY(), mFastScrollerOffset)) {
                mTouchHandler = rv.getScrollbar();
            } else {
                mTouchHandler = null;
            }
        }
        if (mTouchHandler != null) {
            mTouchHandler.handleTouchEvent(ev, mFastScrollerOffset);
            return true;
        }
        // if in search state, consume touch event.
        return isSearching()
                && mActivityContext.getDragLayer().isEventOverView(getVisibleContainerView(), ev);
    }

    /** The current active recycler view (A-Z list from one of the profiles, or search results). */
    public AllAppsRecyclerView getActiveRecyclerView() {
        if (isSearching()) {
            return getSearchRecyclerView();
        }
        return getActiveAppsRecyclerView();
    }

    /** The current apps recycler view in the container. */
    private AllAppsRecyclerView getActiveAppsRecyclerView() {
        if (!mUsingTabs || isPersonalTab()) {
            return mAH.get(AdapterHolder.MAIN).mRecyclerView;
        } else {
            return mAH.get(getWorkHolderIndex()).mRecyclerView;
        }
    }

    /**
     * The container for A-Z apps (the ViewPager for main+work tabs, or main RV). This is currently
     * hidden while searching.
     **/
    public ViewGroup getAppsRecyclerViewContainer() {
        return mViewPager != null ? mViewPager : findViewById(R.id.apps_list_view);
    }

    /** The RV for search results, which is hidden while A-Z apps are visible. */
    public SearchRecyclerView getSearchRecyclerView() {
        return mSearchRecyclerView;
    }

    protected boolean isPersonalTab() {
        return mViewPager == null || mViewPager.getNextPage() == 0;
    }

    /**
     * Switches the current page to the provided {@code tab} if tabs are supported, otherwise does
     * nothing.
     */
    public void switchToTab(int tab) {
        if (mUsingTabs) {
            mViewPager.setCurrentPage(tab);
        }
    }

    public LayoutInflater getLayoutInflater() {
        return mSearchUiDelegate.getLayoutInflater();
    }

    @Override
    public void onDropCompleted(View target, DragObject d, boolean success) {}

    @Override
    public void setInsets(Rect insets) {
        mInsets.set(insets);
        DeviceProfile grid = mActivityContext.getDeviceProfile();

        applyAdapterSideAndBottomPaddings(grid);

        MarginLayoutParams mlp = (MarginLayoutParams) getLayoutParams();
        mlp.leftMargin = insets.left;
        mlp.rightMargin = insets.right;
        setLayoutParams(mlp);

        if (grid.isVerticalBarLayout()) {
            setPadding(grid.workspacePadding.left, 0, grid.workspacePadding.right, 0);
        } else {
            int topPadding = grid.allAppsTopPadding;
            if (isSearchBarFloating() && !grid.isTablet) {
                topPadding += getResources().getDimensionPixelSize(
                        R.dimen.all_apps_additional_top_padding_floating_search);
            }
            setPadding(grid.allAppsLeftRightMargin, topPadding, grid.allAppsLeftRightMargin, 0);
        }

        InsettableFrameLayout.dispatchInsets(this, insets);
    }

    /**
     * Returns a padding in case a scrim is shown on the bottom of the view and a padding is needed.
     */
    protected int computeNavBarScrimHeight(WindowInsets insets) {
        return 0;
    }

    /**
     * Returns the current height of nav bar scrim
     */
    public int getNavBarScrimHeight() {
        return mNavBarScrimHeight;
    }

    @Override
    public WindowInsets dispatchApplyWindowInsets(WindowInsets insets) {
        mNavBarScrimHeight = computeNavBarScrimHeight(insets);
        applyAdapterSideAndBottomPaddings(mActivityContext.getDeviceProfile());
        return super.dispatchApplyWindowInsets(insets);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);

        if (mNavBarScrimHeight > 0) {
            canvas.drawRect(0, getHeight() - mNavBarScrimHeight, getWidth(), getHeight(),
                    mNavBarScrimPaint);
        }
    }

    protected void updateSearchResultsVisibility() {
        if (isSearching()) {
            getSearchRecyclerView().setVisibility(VISIBLE);
            getAppsRecyclerViewContainer().setVisibility(GONE);
            mHeader.setVisibility(GONE);
        } else {
            getSearchRecyclerView().setVisibility(GONE);
            getAppsRecyclerViewContainer().setVisibility(VISIBLE);
            mHeader.setVisibility(VISIBLE);
        }
        if (mHeader.isSetUp()) {
            mHeader.setActiveRV(getCurrentPage(), isSearching());
        }
    }

    private void applyAdapterSideAndBottomPaddings(DeviceProfile grid) {
        int bottomPadding = Math.max(mInsets.bottom, mNavBarScrimHeight);
        mAH.forEach(adapterHolder -> {
            adapterHolder.mPadding.bottom = bottomPadding;
            adapterHolder.mPadding.left =
                    adapterHolder.mPadding.right = grid.allAppsLeftRightPadding;
            adapterHolder.applyPadding();
        });
    }

    private void setDeviceManagementResources() {
        if (mActivityContext.getStringCache() != null) {
            Button personalTab = findViewById(R.id.tab_personal);
            if (personalTab != null) personalTab.setText(mActivityContext.getStringCache().allAppsPersonalTab);

            Button workTab = findViewById(R.id.tab_work);
            if (workTab != null) workTab.setText(mActivityContext.getStringCache().allAppsWorkTab);
        }
    }

    /**
     * Returns true if the container has work apps.
     */
    public boolean shouldShowTabs() {
        return mHasWorkApps;
    }

    // Used by tests only
    private boolean isDescendantViewVisible(int viewId) {
        final View view = findViewById(viewId);
        if (view == null) return false;

        if (!view.isShown()) return false;

        return view.getGlobalVisibleRect(new Rect());
    }

    /** Called in Launcher#bindStringCache() to update the UI when cache is updated. */
    public void updateWorkUI() {
        setDeviceManagementResources();
        if (mWorkManager.getWorkModeSwitch() != null) {
            mWorkManager.getWorkModeSwitch().updateStringFromCache();
        }
        inflateWorkCardsIfNeeded();
    }

    private void inflateWorkCardsIfNeeded() {
        AllAppsRecyclerView workRV = mAH.get(AdapterHolder.WORK).mRecyclerView;
        if (workRV != null) {
            for (int i = 0; i < workRV.getChildCount(); i++) {
                View currentView  = workRV.getChildAt(i);
                int currentItemViewType = workRV.getChildViewHolder(currentView).getItemViewType();
                if (currentItemViewType == VIEW_TYPE_WORK_EDU_CARD) {
                    ((WorkEduCard) currentView).updateStringFromCache();
                } else if (currentItemViewType == VIEW_TYPE_WORK_DISABLED_CARD) {
                    ((WorkPausedCard) currentView).updateStringFromCache();
                }
            }
        }
    }

    @VisibleForTesting
    public boolean isPersonalTabVisible() {
        return isDescendantViewVisible(R.id.tab_personal);
    }

    @VisibleForTesting
    public boolean isWorkTabVisible() {
        return isDescendantViewVisible(R.id.tab_work);
    }

    public AlphabeticalAppsList<T> getApps() {
        return (AlphabeticalAppsList<T>) mAH.get(AdapterHolder.MAIN).mAppsList;
    }
    public AlphabeticalAppsList<T> getSearchResultList() {
        return (AlphabeticalAppsList<T>) mAH.get(getSearchHolderIndex()).mAppsList;
    }

    public FloatingHeaderView getFloatingHeaderView() {
        return mHeader;
    }

    @VisibleForTesting
    public View getContentView() {
        return isSearching() ? getSearchRecyclerView() : getAppsRecyclerViewContainer();
    }

    /** The current page visible in all apps. */
    public int getCurrentPage() {
        return isSearching()
                ? getSearchHolderIndex()
                : mViewPager == null ? AdapterHolder.MAIN : mViewPager.getNextPage();
    }

    public int getWorkHolderIndex() {
        for (int index = 0; index < mAH.size(); index++) {
            if (mAH.get(index).isWork()) {
                return index;
            }
        }
        return -1;
    }

    public int getSearchHolderIndex() {
        for (int index = 0; index < mAH.size(); index++) {
            if (mAH.get(index).mType == SEARCH) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Adds an update listener to animator that adds springs to the animation.
     */
    public void addSpringFromFlingUpdateListener(ValueAnimator animator,
            float velocity /* release velocity */,
            float progress /* portion of the distance to travel*/) {
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animator) {
                float distance = (1 - progress) * getHeight(); // px
                float settleVelocity = Math.min(0, distance
                        / (AllAppsTransitionController.INTERP_COEFF * animator.getDuration())
                        + velocity);
                absorbSwipeUpVelocity(Math.max(1000, Math.abs(
                        Math.round(settleVelocity * FLING_VELOCITY_MULTIPLIER))));
            }
        });
    }

    /** Invoked when the container is pulled. */
    public void onPull(float deltaDistance, float displacement) {
        absorbPullDeltaDistance(PULL_MULTIPLIER * deltaDistance, PULL_MULTIPLIER * displacement);
        // Current motion spec is to actually push and not pull
        // on this surface. However, until EdgeEffect.onPush (b/190612804) is
        // implemented at view level, we will simply pull
    }

    @Override
    public void getDrawingRect(Rect outRect) {
        super.getDrawingRect(outRect);
        outRect.offset(0, (int) getTranslationY());
    }

    @Override
    public void setTranslationY(float translationY) {
        super.setTranslationY(translationY);
        invalidateHeader();
    }

    public void setScrimView(ScrimView scrimView) {
        mScrimView = scrimView;
    }

    @Override
    public void drawOnScrimWithScale(Canvas canvas, float scale) {
        final View panel = mBottomSheetBackground;
        final boolean hasBottomSheet = panel.getVisibility() == VISIBLE;
        final float translationY = ((View) panel.getParent()).getTranslationY();

        final float horizontalScaleOffset = (1 - scale) * panel.getWidth() / 2;
        final float verticalScaleOffset = (1 - scale) * (panel.getHeight() - getHeight() / 2);

        final float topNoScale = panel.getTop() + translationY;
        final float topWithScale = topNoScale + verticalScaleOffset;
        final float leftWithScale = panel.getLeft() + horizontalScaleOffset;
        final float rightWithScale = panel.getRight() - horizontalScaleOffset;
        // Draw full background panel for tablets.
        if (hasBottomSheet) {
            mHeaderPaint.setColor(mBottomSheetBackgroundColor);
            mHeaderPaint.setAlpha((int) (255 * mBottomSheetAlpha));

            mTmpRectF.set(
                    leftWithScale,
                    topWithScale,
                    rightWithScale,
                    panel.getBottom());
            mTmpPath.reset();
            mTmpPath.addRoundRect(mTmpRectF, mBottomSheetCornerRadii, Direction.CW);
            canvas.drawPath(mTmpPath, mHeaderPaint);
        }

        if (DEBUG_HEADER_PROTECTION) {
            mHeaderPaint.setColor(Color.MAGENTA);
            mHeaderPaint.setAlpha(255);
        } else {
            mHeaderPaint.setColor(mHeaderColor);
            mHeaderPaint.setAlpha((int) (getAlpha() * Color.alpha(mHeaderColor)));
        }
        if (mHeaderPaint.getColor() == mScrimColor || mHeaderPaint.getColor() == 0) {
            return;
        }

        // Draw header on background panel
        final float headerBottomNoScale =
                getHeaderBottom() + getVisibleContainerView().getPaddingTop();
        final float headerHeightNoScale = headerBottomNoScale - topNoScale;
        final float headerBottomWithScaleOnTablet = topWithScale + headerHeightNoScale * scale;
        final float headerBottomOffset = (getVisibleContainerView().getHeight() * (1 - scale) / 2);
        final float headerBottomWithScaleOnPhone = headerBottomNoScale * scale + headerBottomOffset;
        final FloatingHeaderView headerView = getFloatingHeaderView();
        if (hasBottomSheet) {
            // Start adding header protection if search bar or tabs will attach to the top.
            if (!isSearchBarFloating() || mUsingTabs) {
                mTmpRectF.set(
                        leftWithScale,
                        topWithScale,
                        rightWithScale,
                        headerBottomWithScaleOnTablet);
                mTmpPath.reset();
                mTmpPath.addRoundRect(mTmpRectF, mBottomSheetCornerRadii, Direction.CW);
                canvas.drawPath(mTmpPath, mHeaderPaint);
            }
        } else {
            canvas.drawRect(0, 0, canvas.getWidth(), headerBottomWithScaleOnPhone, mHeaderPaint);
        }

        // If tab exist (such as work profile), extend header with tab height
        final int tabsHeight = headerView.getPeripheralProtectionHeight();
        if (mTabsProtectionAlpha > 0 && tabsHeight != 0) {
            if (DEBUG_HEADER_PROTECTION) {
                mHeaderPaint.setColor(Color.BLUE);
                mHeaderPaint.setAlpha(255);
            } else {
                mHeaderPaint.setAlpha((int) (getAlpha() * mTabsProtectionAlpha));
            }
            float left = 0f;
            float right = canvas.getWidth();
            if (hasBottomSheet) {
                left = mBottomSheetBackground.getLeft() + horizontalScaleOffset;
                right = mBottomSheetBackground.getRight() - horizontalScaleOffset;
            }

            final float tabTopWithScale = hasBottomSheet
                    ? headerBottomWithScaleOnTablet
                    : headerBottomWithScaleOnPhone;
            final float tabBottomWithScale = tabTopWithScale + tabsHeight * scale;

            canvas.drawRect(
                    left,
                    tabTopWithScale,
                    right,
                    tabBottomWithScale,
                    mHeaderPaint);
        }
    }

    /**
     * redraws header protection
     */
    public void invalidateHeader() {
        if (mScrimView != null) {
            mScrimView.invalidate();
        }
    }

    /** Returns the position of the bottom edge of the header */
    public int getHeaderBottom() {
        int bottom = (int) getTranslationY() + mHeader.getClipTop();
        if (isSearchBarFloating()) {
            if (mActivityContext.getDeviceProfile().isTablet) {
                return bottom + mBottomSheetBackground.getTop();
            }
            return bottom;
        }
        return bottom + mHeader.getTop();
    }

    /**
     * Returns a view that denotes the visible part of all apps container view.
     */
    public View getVisibleContainerView() {
        return mBottomSheetBackground.getVisibility() == VISIBLE ? mBottomSheetBackground : this;
    }

    protected void onInitializeRecyclerView(RecyclerView rv) {
        rv.addOnScrollListener(mScrollListener);
        mSearchUiDelegate.onInitializeRecyclerView(rv);
    }

    public AdapterHolder createHolder(int type) {
        switch (type) {
            case AdapterHolder.MAIN:
                return new AdapterHolder(AdapterHolder.MAIN,
                        new AlphabeticalAppsList<>(mActivityContext, mAllAppsStore, null));
            case AdapterHolder.WORK:
                return new AdapterHolder(AdapterHolder.WORK,
                        new AlphabeticalAppsList<>(mActivityContext, mAllAppsStore, mWorkManager));
            case AdapterHolder.SEARCH:
                return new AdapterHolder(SEARCH,
                        new AlphabeticalAppsList<>(mActivityContext, null, null));
            default:
                throw new RuntimeException("Unexpected adapter type");
        }
    }

    /** Returns the instance of @{code SearchTransitionController}. */
    public SearchTransitionController getSearchTransitionController() {
        return mSearchTransitionController;
    }

    /** Holds a {@link BaseAllAppsAdapter} and related fields. */
    public class AdapterHolder {
        public static final int MAIN = 0;
        public static final int WORK = 1;
        public static final int SEARCH = 2;

        private int mType;
        public final AllAppsGridAdapter<T> mAdapter;
        final RecyclerView.LayoutManager mLayoutManager;
        final AlphabeticalAppsList<T> mAppsList;
        public final Rect mPadding = new Rect();
        public AllAppsRecyclerView mRecyclerView;

        AdapterHolder(int type, AlphabeticalAppsList<T> appsList) {
            mType = type;
            mAppsList = appsList;
            mAdapter = createAdapter(mAppsList);
            mAppsList.setAdapter(mAdapter);
            mLayoutManager = mAdapter.getLayoutManager();
        }

        public void setup(@NonNull View rv, @Nullable Predicate<ItemInfo> matcher) {
            mAppsList.updateItemFilter(matcher);
            mRecyclerView = (AllAppsRecyclerView) rv;
            mRecyclerView.bindFastScrollbar(mFastScroller);
            mRecyclerView.setEdgeEffectFactory(createEdgeEffectFactory());
            mRecyclerView.setApps(mAppsList);
            mRecyclerView.setLayoutManager(mLayoutManager);
            mRecyclerView.setAdapter(mAdapter);
            mRecyclerView.setHasFixedSize(true);
            // No animations will occur when changes occur to the items in this RecyclerView.
            mRecyclerView.setItemAnimator(null);
            onInitializeRecyclerView(mRecyclerView);
            FocusedItemDecorator focusedItemDecorator = new FocusedItemDecorator(mRecyclerView);
            mRecyclerView.addItemDecoration(focusedItemDecorator);
            mAdapter.setIconFocusListener(focusedItemDecorator.getFocusListener());
            applyPadding();
        }

        public void applyPadding() {
            if (mRecyclerView != null) {
                int bottomOffset = 0;
                if (isWork() && mWorkManager.getWorkModeSwitch() != null) {
                    bottomOffset = mInsets.bottom + mWorkManager.getWorkModeSwitch().getHeight();
                }
                if (isSearchBarFloating()) {
                    bottomOffset += mSearchContainer.getHeight();
                }
                mRecyclerView.setPadding(mPadding.left, mPadding.top, mPadding.right,
                        mPadding.bottom + bottomOffset);
            }
        }

        public boolean isWork() {
            return mType == WORK;
        }

        public boolean isSearch() {
            return mType == SEARCH;
        }

        public void setIsWork(boolean isWork) {
            this.mType = isWork ? WORK : MAIN;
        }
    }
}
