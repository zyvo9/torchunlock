package com.zyvo.torchunlock;

import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements StatusReceiver.Listener {

    private static final String STATUS_ACTION = "com.zyvo.torchunlock.STATUS";
    private static final String PREFS = "torchunlock";
    private static final String KEY_LAST_ARMED = "last_armed";

    private final Handler ui = new Handler(Looper.getMainLooper());

    private View statusDot;
    private TextView statusTitle;
    private TextView statusMeta;
    private View statusSpinner;
    private TextView layersText;

    private boolean gotPing;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusDot = findViewById(R.id.status_dot);
        statusTitle = findViewById(R.id.status_title);
        statusMeta = findViewById(R.id.status_meta);
        statusSpinner = findViewById(R.id.status_spinner);
        layersText = findViewById(R.id.layers_text);

        MaterialButton cta = findViewById(R.id.cta);
        cta.setOnClickListener(v -> openLsposed());

        showChecking();
        StatusReceiver.setListener(this);
        registerStatusReceiver();

        // SystemUI only pings when it starts, so if nothing arrives we fall
        // back to what we saw the last time the app was opened.
        ui.postDelayed(() -> {
            if (gotPing) return;
            if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_LAST_ARMED, false)) {
                showArmed(new ArrayList<>(), 0, true);
            } else {
                showNotArmed();
            }
        }, 1500);
    }

    private void registerStatusReceiver() {
        BroadcastReceiver r = new StatusReceiver();
        IntentFilter f = new IntentFilter(STATUS_ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            // SystemUI is a different app, so it must be allowed through.
            registerReceiver(r, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(r, f);
        }
    }

    private void showChecking() {
        statusSpinner.setVisibility(View.VISIBLE);
        statusDot.setVisibility(View.GONE);
        statusTitle.setText(R.string.status_checking);
        statusMeta.setText(R.string.status_checking_hint);
    }

    @Override
    public void onArmed(List<String> layers, int fakeLevel) {
        gotPing = true;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_LAST_ARMED, true).apply();
        showArmed(layers, fakeLevel, false);
    }

    private void showArmed(List<String> layers, int fakeLevel, boolean fromCache) {
        statusSpinner.setVisibility(View.GONE);
        statusDot.setVisibility(View.VISIBLE);
        statusDot.setBackgroundResource(R.drawable.dot_ok);
        statusTitle.setText(R.string.status_armed);
        statusMeta.setText(fromCache
                ? getString(R.string.status_armed_cached)
                : getString(R.string.status_armed_live, fakeLevel));

        if (layers.isEmpty()) {
            layersText.setText(R.string.layers_none);
        } else {
            StringBuilder sb = new StringBuilder();
            for (String s : layers) {
                if (sb.length() > 0) sb.append('\n');
                sb.append("• ").append(s);
            }
            layersText.setText(sb.toString());
        }
    }

    private void showNotArmed() {
        statusSpinner.setVisibility(View.GONE);
        statusDot.setVisibility(View.VISIBLE);
        statusDot.setBackgroundResource(R.drawable.dot_off);
        statusTitle.setText(R.string.status_off);
        statusMeta.setText(R.string.status_off_hint);
        layersText.setText(R.string.layers_none);
    }

    private void openLsposed() {
        for (String pkg : new String[]{"org.lsposed.manager", "com.arts.lsposed.manager"}) {
            Intent i = new Intent();
            i.setPackage(pkg);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(i);
                return;
            } catch (ActivityNotFoundException ignored) {
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.guide_title)
                .setMessage(R.string.guide_body)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }
}