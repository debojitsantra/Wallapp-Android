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
