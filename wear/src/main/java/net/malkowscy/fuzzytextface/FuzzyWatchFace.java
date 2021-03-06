/*
 * Copyright (C) 2014 The Android Open Source Project
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

package net.malkowscy.fuzzytextface;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Vibrator;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class FuzzyWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);
    private Engine engine;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "peer-connected".equals(intent.getAction())) {
            engine.onConnected();
        } else if (intent != null && "peer-disconnected".equals(intent.getAction())) {
            engine.onConnectionLost();
        }
        return super.onStartCommand(intent, flags, startId);
    }

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(60);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        this.engine = new Engine();
        return engine;
    }


    private static class EngineHandler extends Handler {
        private final WeakReference<FuzzyWatchFace.Engine> mWeakReference;

        public EngineHandler(FuzzyWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            FuzzyWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    enum DisplayedInfo {
        watchFace, date, connectionLost;
    }


    private class Engine extends CanvasWatchFaceService.Engine {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        TextPaint mTextPaint;
        TextPaint mDateTextPaint;
        boolean mAmbient;
        private String timeZone;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                timeZone = intent.getStringExtra("time-zone");
            }
        };

        DisplayedInfo visibleInformation = DisplayedInfo.watchFace;


        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;
        private float mXPadding;
        private float mYPadding;

        private TextPaint createTextPaint(int textColor) {
            TextPaint paint = new TextPaint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = FuzzyWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            this.mXPadding = resources.getDimension(isRound
                    ? R.dimen.digital_x_padding_round : R.dimen.digital_x_padding);
            this.mYPadding = resources.getDimension(isRound
                    ? R.dimen.digital_y_padding_round : R.dimen.digital_y_padding);

            mTextPaint.setTextSize(resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size));

            mDateTextPaint.setTextSize(resources.getDimension(isRound
                    ? R.dimen.digital_date_size_round : R.dimen.digital_date_size));

            mAlertTextPaint.setTextSize(resources.getDimension(isRound
                    ? R.dimen.digital_alert_size_round : R.dimen.digital_alert_size));
        }


        private void onConnected() {
            visibleInformation = DisplayedInfo.watchFace;
            invalidate();
        }


        private void onConnectionLost() {
            Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            long[] vibrationPattern = {0, 500, 50, 300};
            //-1 - don't repeat
            final int indexInPatternToRepeat = -1;
            vibrator.vibrate(vibrationPattern, indexInPatternToRepeat);

            handler.removeCallbacks(returnToWatchFaceTask);
            visibleInformation = DisplayedInfo.connectionLost;
            invalidate();
        }


        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(FuzzyWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = FuzzyWatchFace.this.getResources();

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTextPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mDateTextPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mAlertTextPaint = createTextPaint(resources.getColor(R.color.alert_text));
        }

        private TextPaint mAlertTextPaint;

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }


        private SpannableString dateToSpannable() {
            GregorianCalendar gc = new GregorianCalendar(timeZone == null ? TimeZone.getDefault() : TimeZone.getTimeZone(timeZone));
            int dayNumber = gc.get(Calendar.DAY_OF_MONTH);
            int year = gc.get(Calendar.YEAR);
            String dayName = getResources().getStringArray(R.array.days)[gc.get(Calendar.DAY_OF_WEEK) - 1];
            String monthName = getResources().getStringArray(R.array.months)[gc.get(Calendar.MONTH)];

            String[] t = getResources().getString(R.string.date_format, dayName, dayNumber, monthName).split(" ");
            return arrayToSpannable(t);
        }

        private SpannableString arrayToSpannable(String[] t) {
            StringBuilder sb = new StringBuilder();

            for (String x : t) {
                if (x.startsWith("*")) {
                    sb.append(x.substring(1)).append(" ");
                } else {
                    sb.append(x).append(" ");
                }
            }

            SpannableString wordtoSpan = new SpannableString(sb.toString().trim());


            int l = 0;
            for (String x : t) {
                if (x.startsWith("*")) {
                    int n = x.substring(1).length();
                    wordtoSpan.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.digital_text_bold)), l, l + n, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    wordtoSpan.setSpan(new StyleSpan(Typeface.BOLD), l, l + n, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    l += n + 1;
                } else {
                    l += x.length() + 1;
                }
            }
            return wordtoSpan;
        }

        private SpannableString timeToSpannable() {
            GregorianCalendar gc = new GregorianCalendar(timeZone == null ? TimeZone.getDefault() : TimeZone.getTimeZone(timeZone));

            int half_mins = (2 * gc.get(Calendar.MINUTE)) + (gc.get(Calendar.SECOND) / 30);
            int rel_index = ((half_mins + 5) / (2 * 5)) % 12;
            int hour_index;

            if (rel_index == 0 && gc.get(Calendar.MINUTE) > 30) {
                hour_index = (gc.get(Calendar.HOUR_OF_DAY) + 1) % 24;
            } else {
                hour_index = gc.get(Calendar.HOUR_OF_DAY) % 24;
            }


            String hour = getResources().getStringArray(R.array.hours)[hour_index];
            String next_hour = getResources().getStringArray(R.array.hours)[(hour_index + 1) % 24];
            String rel = getResources().getStringArray(R.array.rels)[rel_index];


            String[] t = String.format(rel, hour, next_hour).split(" ");
            return arrayToSpannable(t);
        }


        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            switch (visibleInformation) {
                case connectionLost:
                    drawConnectionLost(canvas, bounds);
                    break;
                case date:
                    drawDate(canvas, bounds);
                    break;
                default:
                    drawPlainWatchFace(canvas, bounds);
            }


        }

        private void drawConnectionLost(Canvas canvas, Rect bounds) {
            SpannableString wordtoSpan = arrayToSpannable(getResources().getString(R.string.connection_lost).split(" "));

            StaticLayout sl = new StaticLayout(wordtoSpan, mAlertTextPaint, (int) (bounds.width() - mXPadding * 2),
                    Layout.Alignment.ALIGN_CENTER, 1, 1, false);

            canvas.save();

            canvas.translate((canvas.getWidth() / 2) - (sl.getWidth() / 2), (canvas.getHeight() / 2) - ((sl.getHeight() / 2)));

            //draws static layout on canvas
            sl.draw(canvas);
            canvas.restore();
        }

        private void drawDate(Canvas canvas, Rect bounds) {
            SpannableString wordtoSpan = dateToSpannable();

            StaticLayout sl = new StaticLayout(wordtoSpan, mDateTextPaint, (int) (bounds.width() - mXPadding * 2),
                    Layout.Alignment.ALIGN_CENTER, 1, 1, false);

            canvas.save();

            canvas.translate((canvas.getWidth() / 2) - (sl.getWidth() / 2), (canvas.getHeight() / 2) - ((sl.getHeight() / 2)));

            //draws static layout on canvas
            sl.draw(canvas);
            canvas.restore();
        }

        private void drawPlainWatchFace(Canvas canvas, Rect bounds) {
            // Draw the background.
            SpannableString wordtoSpan = timeToSpannable();
            StaticLayout sl = new StaticLayout(wordtoSpan, mTextPaint, (int) (bounds.width() - mXPadding * 2),
                    Layout.Alignment.ALIGN_CENTER, 1, 1, false);

            canvas.save();

            canvas.translate((canvas.getWidth() / 2) - (sl.getWidth() / 2), (canvas.getHeight() / 2) - ((sl.getHeight() / 2)));

            //draws static layout on canvas
            sl.draw(canvas);
            canvas.restore();
        }


        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }


        Handler handler = new Handler();

        private final Runnable returnToWatchFaceTask = new Runnable() {
            @Override
            public void run() {
                visibleInformation = DisplayedInfo.watchFace;
                invalidate();
            }
        };

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            Resources resources = FuzzyWatchFace.this.getResources();
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.

                    switch (visibleInformation) {
                        case watchFace:
                            visibleInformation = DisplayedInfo.date;
                            handler.postDelayed(returnToWatchFaceTask, 3 * 1000);
                            break;
                        case date:
                            visibleInformation = DisplayedInfo.watchFace;
                            handler.removeCallbacks(returnToWatchFaceTask);
                            break;
                        case connectionLost:
                            visibleInformation = DisplayedInfo.watchFace;
                            break;
                    }

//                    mTapCount++;
//                    mBackgroundPaint.setColor(resources.getColor(mTapCount % 2 == 0 ?
//                            R.color.background : R.color.background2));
                    break;
            }
            invalidate();
        }


        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                timeZone = TimeZone.getDefault().getID();
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            FuzzyWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            FuzzyWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }
    }


}
