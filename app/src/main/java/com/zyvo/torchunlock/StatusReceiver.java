package com.zyvo.torchunlock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.ArrayList;
import java.util.List;

/** Receives the "I'm armed inside SystemUI" ping from {@link TorchHook}. */
public class StatusReceiver extends BroadcastReceiver {

    public interface Listener {
        void onArmed(List<String> layers, int fakeLevel);
    }

    private static Listener listener;

    public static void setListener(Listener l) {
        listener = l;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || listener == null) return;
        ArrayList<String> layers = intent.getStringArrayListExtra("layers");
        int fake = intent.getIntExtra("fakeLevel", 0);
        listener.onArmed(layers == null ? new ArrayList<String>() : layers, fake);
    }
}