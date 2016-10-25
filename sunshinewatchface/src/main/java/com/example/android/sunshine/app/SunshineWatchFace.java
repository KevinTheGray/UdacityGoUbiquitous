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
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
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

  /**
   * Update rate in milliseconds for interactive mode. We update once a second since seconds are
   * displayed in interactive mode.
   */
  private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

  /**
   * Handler message id for updating the time periodically in interactive mode.
   */
  private static final int MSG_UPDATE_TIME = 0;

  @Override
  public Engine onCreateEngine() {
    return new Engine();
  }

  private class Engine extends CanvasWatchFaceService.Engine implements
    GoogleApiClient.ConnectionCallbacks,
    DataApi.DataListener,
    GoogleApiClient.OnConnectionFailedListener {

    final Handler mUpdateTimeHandler = new EngineHandler(this);
    boolean mRegisteredTimeZoneReceiver = false;
    private GoogleApiClient mGoogleApiClient;
    private boolean mResolvingError = false;
    private static final int REQUEST_RESOLVE_ERROR = 1000;
    private static final String PREFERENCES_KEY = "preferences";
    private static final String PREF_TEMP_HIGH = "pref_temp_high";
    private static final String PREF_TEMP_LOW = "pref_temp_low";
    private static final String PREF_WEATHER_ICON_ID = "pref_weather_icon_id";

    Paint mBackgroundPaint;
    Paint mBackgroundDebugPaint;
    Paint mWeatherIconPaint;
    private Bitmap mWeatherIconBitmap;
    private long mWeatherIconCode;
    private String mHighTempString = "";
    private String mLowTempString = "";
    Paint mTextPaint;
    Paint mFullDateTextPaint;
    Paint mHighTempTextPaint;
    Paint mLowTempTextPaint;

    boolean mAmbient;
    final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        mCalendar.setTimeZone(TimeZone.getDefault());
        invalidate();
      }
    };
    Calendar mCalendar;
    float mXOffset;
    float mYOffset;
    float mDateXOffset;
    float mDateYOffset;
    float mDividerXOffset;
    float mDividerYOffset;
    float mDividerWidth;
    float mDividerHeight;
    float mWeatherIconXOffest;
    float mWeatherIconYOffset;
    float mTempYOffset;
    float mLowTempXOffset;
    float mHighTempXOffset;

    /**
     * Whether the display supports fewer bits for each color in ambient mode. When true, we
     * disable anti-aliasing in ambient mode.
     */
    boolean mLowBitAmbient;

    @Override
    public void onCreate(SurfaceHolder holder) {
      super.onCreate(holder);

      setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
        .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
        .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
        .setShowSystemUiTime(false)
        .build());

      SharedPreferences preferences = getSharedPreferences(PREFERENCES_KEY, MODE_PRIVATE);
      mLowTempString = preferences.getString(PREF_TEMP_LOW, "");
      mHighTempString = preferences.getString(PREF_TEMP_HIGH, "");
      mWeatherIconCode = preferences.getLong(PREF_WEATHER_ICON_ID, 0);

      mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFace.this)
        .addApi(Wearable.API)
        .addConnectionCallbacks(this)
        .addOnConnectionFailedListener(this)
        .build();

      Resources resources = SunshineWatchFace.this.getResources();
      mYOffset = resources.getDimension(R.dimen.digital_y_offset);
      mDateYOffset = resources.getDimension(R.dimen.date_y_offset);
      mDividerYOffset = resources.getDimension(R.dimen.divider_y_offset);
      mDividerHeight = resources.getDimension(R.dimen.divider_height);
      mWeatherIconYOffset = resources.getDimension(R.dimen.weather_icon_y_offset);
      mTempYOffset = resources.getDimension(R.dimen.temp_y_offset);


      mBackgroundPaint = new Paint();
      mBackgroundPaint.setColor(resources.getColor(R.color.background));

      mBackgroundDebugPaint = new Paint();
      mBackgroundDebugPaint.setColor(resources.getColor(R.color.red));

      mWeatherIconPaint =  new Paint();
      mWeatherIconBitmap = loadBitmapForWeatherID();

      mTextPaint = new Paint();
      //mTextPaint = createTextPaint(ResourcesCompat.getColor(getResources(), R.color.digital_text, null));
      mTextPaint = createTextPaint(getResources().getColor(R.color.digital_text));

      mFullDateTextPaint = new Paint();
      //mFullDateTextPaint = createFullDateTextPaint(ResourcesCompat.getColor(getResources(), R.color.light_digital_text, null));
      mFullDateTextPaint = createTextPaint(getResources().getColor(R.color.light_digital_text));

      mHighTempTextPaint = createTextPaint(getResources().getColor(R.color.digital_text));
      mLowTempTextPaint = createTextPaint(getResources().getColor(R.color.light_digital_text));

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

    private Paint createFullDateTextPaint(int textColor) {
      Paint paint = new Paint();
      paint.setColor(textColor);
      paint.setTypeface(NORMAL_TYPEFACE);
      paint.setAntiAlias(true);
      return paint;
    }

    @Override
    public void onVisibilityChanged(boolean visible) {
      super.onVisibilityChanged(visible);

      if (visible) {
        registerReceiver();

        // Update time zone in case it changed while we weren't visible.
        mCalendar.setTimeZone(TimeZone.getDefault());
        invalidate();
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
      SunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
      mGoogleApiClient.connect();
    }

    private void unregisterReceiver() {
      if (!mRegisteredTimeZoneReceiver) {
        return;
      }
      mRegisteredTimeZoneReceiver = false;
      SunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
      Wearable.DataApi.removeListener(mGoogleApiClient, this);
      mGoogleApiClient.disconnect();
    }

    @Override
    public void onApplyWindowInsets(WindowInsets insets) {
      super.onApplyWindowInsets(insets);

      // Load resources that have alternate values for round watches.
      Resources resources = SunshineWatchFace.this.getResources();
      boolean isRound = insets.isRound();
      float textSize;
      float dateTextSize;
      float tempTextSize;
      if (isRound) {
        mXOffset = resources.getDimension(R.dimen.digital_x_offset_round);
        textSize = resources.getDimension(R.dimen.digital_text_size_round);
        mDateXOffset = resources.getDimension(R.dimen.date_x_offset_round);
        dateTextSize = resources.getDimension(R.dimen.digital_date_text_size_round);
        mDividerXOffset = resources.getDimension(R.dimen.divider_x_offset_round);
        mDividerWidth = resources.getDimension(R.dimen.divider_width_round);
        tempTextSize = getResources().getDimension(R.dimen.temp_text_size_round);
        mLowTempXOffset = getResources().getDimension(R.dimen.temp_low_x_offset_round);
        mHighTempXOffset = getResources().getDimension(R.dimen.temp_high_x_offset_round);
        mWeatherIconXOffest = getResources().getDimension(R.dimen.weather_icon_x_offset_round);
      } else {
        mXOffset = resources.getDimension(R.dimen.digital_x_offset);
        textSize = resources.getDimension(R.dimen.digital_text_size);
        mDateXOffset = resources.getDimension(R.dimen.date_x_offset);
        dateTextSize = resources.getDimension(R.dimen.digital_date_text_size);
        mDividerXOffset = resources.getDimension(R.dimen.divider_x_offset);
        mDividerWidth = resources.getDimension(R.dimen.divider_width);
        mWeatherIconXOffest = getResources().getDimension(R.dimen.weather_icon_x_offset);
        mLowTempXOffset = getResources().getDimension(R.dimen.temp_low_x_offset);
        mHighTempXOffset = getResources().getDimension(R.dimen.temp_high_x_offset);
        tempTextSize = getResources().getDimension(R.dimen.temp_text_size);
      }

      mTextPaint.setTextSize(textSize);
      mFullDateTextPaint.setTextSize(dateTextSize);
      mHighTempTextPaint.setTextSize(tempTextSize);
      mLowTempTextPaint.setTextSize(tempTextSize);
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
          mTextPaint.setAntiAlias(!inAmbientMode);
        }
        invalidate();
      }

      // Whether the timer should be running depends on whether we're visible (as well as
      // whether we're in ambient mode), so we may need to start or stop the timer.
      updateTimer();
    }

    @Override
    public void onDraw(Canvas canvas, Rect bounds) {
      // Draw the background.
      if (isInAmbientMode()) {
        canvas.drawColor(Color.BLACK);
      } else {
        canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
      }

      long now = System.currentTimeMillis();
      mCalendar.setTimeInMillis(now);

      String text = String.format(Locale.getDefault(), "%02d:%02d", mCalendar.get(Calendar.HOUR_OF_DAY),
           mCalendar.get(Calendar.MINUTE));
      canvas.drawText(text, mXOffset, mYOffset, mTextPaint);

      long dateTimeMillis = System.currentTimeMillis();
      SimpleDateFormat sdf = new SimpleDateFormat("EEE, MMM dd yyyy", getResources().getConfiguration().locale);
      String formattedDate = sdf.format(new Date(dateTimeMillis)).toUpperCase();
      canvas.drawText(formattedDate, mDateXOffset, mDateYOffset, mFullDateTextPaint);

      canvas.drawRect(mDividerXOffset, mDividerYOffset, mDividerXOffset + mDividerWidth,
        mDividerYOffset+ mDividerHeight, mFullDateTextPaint);

      if (mWeatherIconBitmap != null && !isInAmbientMode()) {
        canvas.drawBitmap(mWeatherIconBitmap, mWeatherIconXOffest, mWeatherIconYOffset, mWeatherIconPaint);
      }

      canvas.drawText(formattedDate, mDateXOffset, mDateYOffset, mFullDateTextPaint);

      canvas.drawText(mHighTempString, mHighTempXOffset, mTempYOffset, mHighTempTextPaint);
      canvas.drawText(mLowTempString, mLowTempXOffset, mTempYOffset, mLowTempTextPaint);
    }

    private Bitmap loadBitmapForWeatherID() {

      int weatherIconId = 0;
      if (mWeatherIconCode >= 200 && mWeatherIconCode <= 232) {
        weatherIconId = R.drawable.ic_storm;
      } else if (mWeatherIconCode >= 300 && mWeatherIconCode <= 321) {
        weatherIconId = R.drawable.ic_light_rain;
      } else if (mWeatherIconCode >= 500 && mWeatherIconCode <= 504) {
        weatherIconId = R.drawable.ic_rain;
      } else if (mWeatherIconCode == 511) {
        weatherIconId = R.drawable.ic_snow;
      } else if (mWeatherIconCode >= 520 && mWeatherIconCode <= 531) {
        weatherIconId = R.drawable.ic_rain;
      } else if (mWeatherIconCode >= 600 && mWeatherIconCode <= 622) {
        weatherIconId = R.drawable.ic_snow;
      } else if (mWeatherIconCode >= 701 && mWeatherIconCode <= 761) {
        weatherIconId = R.drawable.ic_fog;
      } else if (mWeatherIconCode == 761 || mWeatherIconCode == 781) {
        weatherIconId = R.drawable.ic_storm;
      } else if (mWeatherIconCode == 800) {
        weatherIconId = R.drawable.ic_clear;
      } else if (mWeatherIconCode == 801) {
        weatherIconId = R.drawable.ic_light_clouds;
      } else if (mWeatherIconCode >= 802 && mWeatherIconCode <= 804) {
        weatherIconId = R.drawable.ic_cloudy;
      }
      if (mWeatherIconCode != 0) {
        Bitmap largeBitmap = BitmapFactory.decodeResource(getResources(), weatherIconId);
        return Bitmap.createScaledBitmap(largeBitmap, (int) (largeBitmap.getWidth() * 0.70f),
          (int) (largeBitmap.getHeight() * 0.70f), false);
      }
      return null;
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
        long delayMs = INTERACTIVE_UPDATE_RATE_MS
          - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
        mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
      }
    }

    @Override
    public void onConnected(Bundle bundle) {
      mResolvingError = false;
      //mConnected = true;
      Wearable.DataApi.addListener(mGoogleApiClient, this);

    }

    @Override
    public void onConnectionSuspended(int i) {
    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult result) {
      if (!mResolvingError) {
        if (result.hasResolution()) {
          // Try again i guess
          mGoogleApiClient.connect();
        } else {
          mResolvingError = false;
          Wearable.DataApi.removeListener(mGoogleApiClient, this);
        }
      }
    }

    @Override
    public void onDataChanged(DataEventBuffer dataEventBuffer) {
      for (DataEvent event : dataEventBuffer) {
        DataItem item = event.getDataItem();
        if (item.getUri().getPath().equals("/weather_update")) {
          DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
          mHighTempString = dataMap.getString("high_temp");
          mLowTempString = dataMap.getString("low_temp");
          mWeatherIconCode = dataMap.getLong("weather_id");
          mWeatherIconBitmap = loadBitmapForWeatherID();

          SharedPreferences preferences = getSharedPreferences(PREFERENCES_KEY, MODE_PRIVATE);
          SharedPreferences.Editor editor = preferences.edit();
          editor.putString(PREF_TEMP_LOW, mLowTempString);
          editor.putString(PREF_TEMP_HIGH, mHighTempString);
          editor.putLong(PREF_WEATHER_ICON_ID, mWeatherIconCode);
          editor.apply();
        }
      }
    }
  }

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
}
