package com.saggitt.omega.superg;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.Property;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.launcher3.BubbleTextView;
import com.android.launcher3.DeviceProfile;
import com.android.launcher3.ItemInfo;
import com.android.launcher3.Launcher;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.Workspace.OnStateChangeListener;
import com.android.launcher3.anim.AnimatorSetBuilder;
import com.saggitt.omega.OmegaAppKt;
import com.saggitt.omega.smartspace.OmegaSmartspaceController;
import com.saggitt.omega.smartspace.OmegaSmartspaceController.CardData;
import com.saggitt.omega.smartspace.OmegaSmartspaceController.WeatherData;
import com.saggitt.omega.smartspace.SmartspacePreferencesShortcut;
import com.saggitt.omega.util.OmegaUtilsKt;

import org.jetbrains.annotations.Nullable;

/**
 * A simple view used to show the region blocked by QSB during drag and drop.
 */
public class QsbBlockerView extends FrameLayout implements
        OnStateChangeListener, OmegaSmartspaceController.Listener,
        View.OnLongClickListener, View.OnClickListener {
    public static final Property<QsbBlockerView, Integer> QSB_BLOCKER_VIEW_ALPHA = new QsbBlockerViewAlpha(Integer.TYPE, "bgAlpha");
    private final Paint mBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private OmegaSmartspaceController mController;
    private int mState = 0;
    private View mView;
    private BubbleTextView mDummyBubbleTextView;

    public QsbBlockerView(Context context, AttributeSet attrs) {
        super(context, attrs);

        mBgPaint.setColor(Color.WHITE);
        mBgPaint.setAlpha(0);

        mController = OmegaAppKt.getOmegaApp(getContext()).getSmartspace();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mDummyBubbleTextView = findViewById(R.id.dummyBubbleTextView);
        mDummyBubbleTextView.setTag(new ItemInfo() {
            @Override
            public ComponentName getTargetComponent() {
                return new ComponentName(getContext(), "");
            }
        });
        mDummyBubbleTextView.setContentDescription("");
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (mView != null && mState == 2) {
            Launcher launcher = OmegaUtilsKt.getLauncherOrNull(getContext());
            int size;
            if (launcher != null) {
                DeviceProfile deviceProfile = launcher.getDeviceProfile();
                if (launcher.useVerticalBarLayout()) {
                    size = ((MeasureSpec.getSize(widthMeasureSpec) / deviceProfile.inv.numColumns)
                            - deviceProfile.iconSizePx) / 2;
                } else {
                    size = 0;
                }
            } else {
                size = getResources().getDimensionPixelSize(R.dimen.smartspace_preview_widget_margin);
            }
            LayoutParams layoutParams = (LayoutParams) mView.getLayoutParams();
            layoutParams.leftMargin = layoutParams.rightMargin = size;
        }

        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (mController != null)
            mController.addListener(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        if (mController != null)
            mController.removeListener(this);
    }

    @Override
    public void prepareStateChange(AnimatorSetBuilder builder) {

    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawPaint(mBgPaint);
    }

    @Override
    public void onDataUpdated(@Nullable WeatherData weather, @Nullable CardData card) {
        final int oldState = mState;
        final View oldView = mView;

        if (!Utilities.getOmegaPrefs(getContext()).getUsePillQsb()) {
            return;
        }

        if (weather == null) {
            mState = 1;
            mView = oldView != null && oldState == 1 ?
                    oldView :
                    LayoutInflater.from(getContext()).inflate(R.layout.date_widget, this, false);
        } else {
            mState = 2;
            mView = oldView != null && oldState == 2 ?
                    oldView :
                    LayoutInflater.from(getContext()).inflate(R.layout.weather_widget, this, false);
            applyWeather(mView, weather);
            mView.setOnClickListener(this);
        }

        if (oldState != mState) {
            if (oldView != null) {
                oldView.animate().setDuration(200L).alpha(0f).withEndAction(() -> removeView(oldView));
            }
            addView(mView);
            mView.setAlpha(0f);
            mView.animate().setDuration(200L).alpha(1f);
        } else if (oldView != mView) {
            if (oldView != null) {
                removeView(oldView);
            }
            addView(mView);
        }

        mView.setOnLongClickListener(this);
    }

    private void applyWeather(View view, WeatherData weather) {
        ImageView weatherIcon = view.findViewById(R.id.weather_widget_icon);
        weatherIcon.setImageBitmap(weather.getIcon());
        TextView weatherTemperature = view.findViewById(R.id.weather_widget_temperature);
        weatherTemperature.setText(weather.getTitle(
                Utilities.getOmegaPrefs(getContext()).getWeatherUnit()));
    }

    @Override
    public void setPadding(int left, int top, int right, int bottom) {
        super.setPadding(0, 0, 0, 0);
    }

    @Override
    public void onClick(View v) {
        if (mController != null)
            mController.openWeather(v);
    }

    @Override
    public boolean onLongClick(View v) {
        // TODO: move it to below the widget view
        OmegaUtilsKt.openPopupMenu(mView, null, new SmartspacePreferencesShortcut());
        return true;
    }

    static class QsbBlockerViewAlpha extends Property<QsbBlockerView, Integer> {

        public QsbBlockerViewAlpha(Class<Integer> type, String name) {
            super(type, name);
        }

        @Override
        public void set(QsbBlockerView qsbBlockerView, Integer num) {
            qsbBlockerView.mBgPaint.setAlpha(num);
            qsbBlockerView.setWillNotDraw(num == 0);
            qsbBlockerView.invalidate();
        }

        @Override
        public Integer get(QsbBlockerView obj) {
            return obj.mBgPaint.getAlpha();
        }

    }
}