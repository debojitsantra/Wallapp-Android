/*
 * WallApp - A lightweight WebView wrapper for the DebWallApp wallpaper website.
 * Copyright (C) 2026 Debojit Santra
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.debojit.wallapp;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AppCompatActivity;

public class SplashActivity extends AppCompatActivity {

    private static final long SPLASH_DELAY = 2000; // 2 seconds

    // FIX: Keep a reference to the handler so it can be cancelled in onDestroy,
    // preventing a leaked-window / NPE crash if the activity is finished early.
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable launchMain = () -> {
        if (!isFinishing()) {
            startActivity(new Intent(SplashActivity.this, MainActivity.class));
            finish();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);
        handler.postDelayed(launchMain, SPLASH_DELAY);
    }

    @Override
    protected void onDestroy() {
        // FIX: Cancel pending callback to avoid memory leak / stale activity reference
        handler.removeCallbacks(launchMain);
        super.onDestroy();
    }
}
