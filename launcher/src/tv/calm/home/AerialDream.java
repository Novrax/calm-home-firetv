package tv.calm.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.service.dreams.DreamService;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Calendar;
import java.util.Locale;

/**
 * System-wide DreamService (screensaver) so that when another app (YouTube, SmartTube,
 * BBC iPlayer, Spotify, Settings, etc.) goes idle, the live Apple TV Aerial wallpaper
 * plays with the top-left clock and bottom-left location overlay.
 *
 * Because setInteractive(false) is used, pressing any button on the remote immediately
 * dismisses this overlay and carries right on inside the app that was open.
 */
public class AerialDream extends DreamService {
    private AerialView aerial;
    private TextView greeting, clock, ampm, date, sleepLocation;
    private boolean registered;

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);

        SharedPreferences prefs = getSharedPreferences("home", MODE_PRIVATE);
        FrameLayout root = new FrameLayout(this);
        AmbientView ambient = new AmbientView(this);
        aerial = new AerialView(this, ambient, prefs);
        root.addView(aerial, new FrameLayout.LayoutParams(-1, -1));

        // Top-left clock & greeting (identical position and typography to HomeActivity).
        LinearLayout timeCol = new LinearLayout(this);
        timeCol.setOrientation(LinearLayout.VERTICAL);
        timeCol.setPadding(dp(56), dp(32), dp(56), 0);

        greeting = text(11, 0xCCFFFFFF, "sans-serif-medium");
        greeting.setLetterSpacing(0.24f);
        greeting.setShadowLayer(dp(6), 0, dp(1), 0x80000000);
        timeCol.addView(greeting);

        LinearLayout clockRow = new LinearLayout(this);
        clockRow.setGravity(Gravity.BOTTOM);
        clock = text(48, Color.WHITE, "sans-serif-thin");
        clock.setIncludeFontPadding(false);
        clock.setLetterSpacing(-0.02f);
        clock.setShadowLayer(dp(10), 0, dp(2), 0x70000000);
        ampm = text(14, 0xCCFFFFFF, "sans-serif");
        ampm.setPadding(dp(6), 0, 0, dp(7));
        ampm.setShadowLayer(dp(6), 0, dp(1), 0x80000000);
        clockRow.addView(clock);
        clockRow.addView(ampm);
        timeCol.addView(clockRow);

        date = text(11, 0xB3FFFFFF, "sans-serif-medium");
        date.setLetterSpacing(0.18f);
        date.setShadowLayer(dp(6), 0, dp(1), 0x80000000);
        timeCol.addView(date);

        root.addView(timeCol, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));

        // Bottom-left location only (no description).
        sleepLocation = text(12, 0xD9FFFFFF, "sans-serif-medium");
        sleepLocation.setLetterSpacing(0.20f);
        sleepLocation.setShadowLayer(dp(8), 0, dp(2), 0xB0000000);
        sleepLocation.setPadding(dp(56), 0, dp(56), dp(40));
        root.addView(sleepLocation, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START));

        aerial.setOnSceneChangedListener(this::updateLocation);
        updateClock();
        updateLocation();
        setContentView(root);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeReceiver, tf);
        registered = true;
        updateClock();
        updateLocation();
        if (aerial != null) aerial.onHomeResume();
    }

    @Override
    public void onDreamingStopped() {
        if (aerial != null) aerial.onHomePause();
        if (registered) {
            unregisterReceiver(timeReceiver);
            registered = false;
        }
        super.onDreamingStopped();
    }

    private void updateLocation() {
        if (sleepLocation != null && aerial != null) {
            sleepLocation.setText(aerial.scene().location);
        }
    }

    private void updateClock() {
        if (clock == null) return;
        Calendar now = Calendar.getInstance();
        int h = now.get(Calendar.HOUR_OF_DAY);
        greeting.setText(h < 5 ? "GOOD NIGHT" : h < 12 ? "GOOD MORNING" : h < 18 ? "GOOD AFTERNOON" : "GOOD EVENING");
        boolean is24 = DateFormat.is24HourFormat(this);
        clock.setText(DateFormat.format(is24 ? "HH:mm" : "h:mm", now));
        ampm.setText(is24 ? "" : DateFormat.format("a", now).toString().toUpperCase(Locale.getDefault()));
        date.setText(DateFormat.format("EEEE d MMMM", now).toString().toUpperCase(Locale.getDefault()));
    }

    private TextView text(float sp, int color, String family) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        return t;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
