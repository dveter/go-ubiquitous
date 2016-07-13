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

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    private String TAG = "SunshineWatchFace";


    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFace.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener{
        final Handler mUpdateTimeHandler = new EngineHandler(this);

        private static final String WEATHER_PATH = "/weather";
        private static final String WEATHER_INFO_PATH = "/weather/info";
		private static final String KEY_WEATHER_ID = "weatherId";
        private static final String KEY_TEMP_HIGH = "high";
        private static final String KEY_TEMP_LOW = "low";
        private static final String KEY_TIMESTAMP = "timestamp";

        boolean mRegisteredTimeZoneReceiver = false;

        Paint mBackgroundPaint;
        Paint mTextPaintTime;
        Paint mTextPaintDate;
        Paint mTextPaintHighTemp;
        Paint mTextPaintLowTemp;

        Bitmap mWeatherImage;

        String mHighTemp;
        String mLowTemp;

        boolean mAmbient;

        Time mTime;
        Calendar mCalendar;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };

        float mXOffsetTime;
        float mYOffsetTime;
        float mXOffsetTimeNoSecs;

        float mXOffsetDate;
        float mYOffsetDate;

        float mYOffsetWeather;


        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;


        GoogleApiClient mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFace.this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());

            Resources resources = SunshineWatchFace.this.getResources();

            mYOffsetTime = resources.getDimension(R.dimen.digital_y_offset);
            mYOffsetDate = resources.getDimension(R.dimen.digital_date_y_offset);
            mYOffsetWeather = resources.getDimension(R.dimen.digital_weather_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTextPaintTime = new Paint();

            mTextPaintTime = createTextPaint(resources.getColor(R.color.digital_text));

            mTextPaintDate= new Paint();
            mTextPaintDate = createTextPaint(resources.getColor(R.color.digital_secondary_text));

            mTextPaintHighTemp = createBoldTextPaint(Color.WHITE);
            mTextPaintLowTemp = createTextPaint(resources.getColor(R.color.digital_secondary_text));

            mTime = new Time();
            mCalendar = Calendar.getInstance();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        private Paint createBoldTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(BOLD_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();

                mGoogleApiClient.connect();

            } else {
                unregisterReceiver();

                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
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
            SunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffsetTime = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            mXOffsetDate = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);

            mXOffsetTimeNoSecs = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float timeTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_time_text_size_round : R.dimen.digital_time_text_size);
            float dateTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_date_text_size_round : R.dimen.digital_date_text_size);
            float tempTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_temp_text_size_round : R.dimen.digital_temp_text_size);



            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            mTextPaintTime.setTextSize(timeTextSize);
            mTextPaintDate.setTextSize(dateTextSize);
            mTextPaintHighTemp.setTextSize(tempTextSize);
            mTextPaintLowTemp.setTextSize(tempTextSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextPaintTime.setAntiAlias(!inAmbientMode);
                    mTextPaintDate.setAntiAlias(!inAmbientMode);
                    mTextPaintHighTemp.setAntiAlias(!inAmbientMode);
                    mTextPaintLowTemp.setAntiAlias(!inAmbientMode);

                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and refreshes weather info
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            Resources resources = SunshineWatchFace.this.getResources();
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.

                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture. Let's refresh weather data

                    getWeatherData();

                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {

            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            mTime.setToNow();
            String text = mAmbient
                    ? String.format(Locale.US, "%d:%02d", mTime.hour, mTime.minute)
                    : String.format(Locale.US, "%d:%02d:%02d", mTime.hour, mTime.minute, mTime.second);

            if (mTextPaintTime != null) {
                float timeTextLen = mTextPaintTime.measureText(text);
                canvas.drawText(text, bounds.centerX() - timeTextLen / 2, mYOffsetTime, mTextPaintTime);
            }
            //canvas.drawText(text, mXOffsetTime, mYOffsetTime, mTextPaintTime);

            if ( ! mAmbient) {
                Paint datePaint = mTextPaintDate;

                SimpleDateFormat sdf = new SimpleDateFormat("EEE, MMM dd yyyy", Locale.US);

                String dateText = sdf.format(mCalendar.getTime()).toUpperCase();

                float xOffsetDate = datePaint.measureText(dateText) / 2;
                canvas.drawText(dateText, bounds.centerX() - xOffsetDate, mYOffsetDate, datePaint);
            }

            if ( ! mAmbient) {
                if (mHighTemp != null && mLowTemp != null) {
                    canvas.drawLine(bounds.centerX() - 30, mYOffsetDate + 20, bounds.centerX() + 30, mYOffsetDate + 20, mTextPaintLowTemp);
                    float highTextLen = mTextPaintHighTemp.measureText(mHighTemp);
                    if (mWeatherImage != null) {
                        float iconXOffset = bounds.centerX() - ((highTextLen / 2) + mWeatherImage.getWidth() + 20);
                        canvas.drawBitmap(mWeatherImage, iconXOffset, mYOffsetWeather - mWeatherImage.getHeight() + 5, null);
                    }
                    float xOffset = bounds.centerX() - (highTextLen / 2);
                    canvas.drawText(mHighTemp, xOffset, mYOffsetWeather, mTextPaintHighTemp);
                    canvas.drawText(mLowTemp, bounds.centerX() + (highTextLen / 2) + 20, mYOffsetWeather, mTextPaintLowTemp);

                }
            }
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

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Log.d(TAG, "onConnected called!");

            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);
            getWeatherData();
        }

        @Override
        public void onConnectionSuspended(int i) {

        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.d(TAG, "onDataChanged called!");
            for (DataEvent dataEvent : dataEventBuffer) {
                if (dataEvent.getType() == DataEvent.TYPE_CHANGED) {
                    DataMap dataMap = DataMapItem.fromDataItem(dataEvent.getDataItem()).getDataMap();
                    String path = dataEvent.getDataItem().getUri().getPath();
                    if (path.equals(WEATHER_INFO_PATH)) {

                        if (dataMap.containsKey(KEY_WEATHER_ID)) {
                            int weatherId = dataMap.getInt(KEY_WEATHER_ID);
                            Drawable b = getResources().getDrawable(SunshineWatchFaceUtil.getWeatherConditionImage(weatherId), null);
                            Bitmap icon = b != null ? ((BitmapDrawable) b).getBitmap() : null;

                            float desiredSize = mTextPaintHighTemp.getTextSize() / icon.getHeight() * icon.getWidth() + 10;

                            mWeatherImage = Bitmap.createScaledBitmap(icon, (int)desiredSize, (int)desiredSize, true);
                        }

                        if (dataMap.containsKey(KEY_TEMP_HIGH)) {
                            mHighTemp = dataMap.getString(KEY_TEMP_HIGH);
                            Log.d(TAG, "Setting high temperature to: " + mHighTemp);
                        }

                        if (dataMap.containsKey(KEY_TEMP_LOW)) {
                            mLowTemp = dataMap.getString(KEY_TEMP_LOW);
                            Log.d(TAG, "Setting high temperature to: " + mLowTemp);
                        }

                        invalidate();
                    }
                }
            }
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.e(TAG, "Failed connecting to phone : " + connectionResult.getErrorCode());
        }


        public void getWeatherData() {
            PutDataMapRequest putDataMapRequest = PutDataMapRequest.create(WEATHER_PATH);
            putDataMapRequest.getDataMap().putString(KEY_TIMESTAMP, String.valueOf(System.currentTimeMillis()));
            PutDataRequest request = putDataMapRequest.asPutDataRequest();

            Log.d(TAG, "Querying phone...");

            Wearable.DataApi.putDataItem(mGoogleApiClient, request)
                    .setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                        @Override
                        public void onResult(@NonNull DataApi.DataItemResult dataItemResult) {
                            if (!dataItemResult.getStatus().isSuccess()) {
                                Log.d(TAG, "Querying phone failed. Weather data won't be available.");
                            } else {
                                if (DataMapItem.fromDataItem(dataItemResult.getDataItem()).getDataMap().size() > 2)
                                    Log.d(TAG, "Received weather data from the phone :-)");
                            }
                        }
                    });
        }
    }
}
