package com.example.android.sunshine.app;


import android.content.res.Resources;
import android.util.Log;

import java.util.Calendar;

public class SunshineWatchFaceUtil {

    // Codes: http://openweathermap.org/weather-conditions

    public static int getWeatherConditionImage(int conditionCode) {

        if ((conditionCode >= 200 && conditionCode <= 232) || conditionCode == 761 || conditionCode == 781) {
            return R.drawable.ic_storm;
        } else if (conditionCode >= 300 && conditionCode <= 321) {
            return R.drawable.ic_light_rain;
        } else if ((conditionCode >= 500 && conditionCode <= 504) || (conditionCode >= 520 && conditionCode <= 531)) {
            return R.drawable.ic_rain;
        } else if (conditionCode == 511 || (conditionCode >= 600 && conditionCode <= 622)) {
            return R.drawable.ic_snow;
        } else if (conditionCode >= 701 && conditionCode <= 761) {
            return R.drawable.ic_fog;
        } else if (conditionCode == 800) {
            return R.drawable.ic_clear;
        } else if (conditionCode == 801) {
            return R.drawable.ic_light_clouds;
        } else if (conditionCode >= 802 && conditionCode <= 804) {
            return R.drawable.ic_cloudy;
        }
        return 0;
    }

}
