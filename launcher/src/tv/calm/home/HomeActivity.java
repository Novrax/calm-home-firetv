package tv.calm.home;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.FocusFinder;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class HomeActivity extends Activity {
    static final String EXTRA_FROM_HOME_KEY = "from_home_key";
    /** After 2 minutes of inactivity on Home, slide the dock off into the live wallpaper Sleep Screen. */
    private static final long IDLE_SLEEP_MS = 120_000L;

    /** Your apps, in order. Rearrange on the TV: hold Select (or press ☰) on a tile → Move. */
    static final String[] DEFAULT_APPS = {
            "com.netflix.ninja",               // Netflix
            "com.amazon.firetv.youtube",       // YouTube
            "uk.co.bbc.iplayer",               // BBC iPlayer
            "com.amazon.firebat",              // Prime Video
            "com.crunchyroll.crunchyroid",     // Crunchyroll
            "org.smarttube.stable",            // SmartTube
            "com.stremio.one",                 // Stremio
            "com.spotify.tv.android",          // Spotify
            "com.audible.application.firetv",  // Audible
            "com.ted.android.tv",              // TED
            "com.hnf.IslamApp",                // Islam Channel
    };

    private static final int COLUMNS = 5;

    private static final long DEFAULT_IDLE_SLEEP_MS = 120_000L;
    private static final long[] SLEEP_TIMEOUT_OPTIONS = {60_000L, 120_000L, 300_000L, 600_000L, 0L};
    private static final String[] SLEEP_TIMEOUT_LABELS = {"After 1 Min", "After 2 Min", "After 5 Min", "After 10 Min", "Never"};

    private PackageManager pm;
    private SharedPreferences prefs;
    private final List<String> apps = new ArrayList<>();
    private final Map<String, Intent> launchIntents = new HashMap<>();
    private Art art;

    private FrameLayout root;
    private AmbientView ambient;
    private AerialView aerial;
    private HomeScroll scroll;
    private LinearLayout content;
    private LinearLayout status;
    private View sceneBtn, gearBtn;
    private TextView greeting, clock, ampm, date;
    private FrameLayout dockWrap;
    private View dockTray;
    private GridLayout grid;
    private LinearLayout sleepCaption;
    private TextView sleepLocation;
    private FrameLayout overlayHost;
    private View returnFocusView;
    private boolean overlayHidingDock;
    private Icons.Weather weatherIcon;
    private Icons.Wifi wifiIcon;
    private TextView weatherText;
    private View weatherSep;
    private Weather weather;
    private int tileW, tileH;
    private String lastFocused;
    private int pendingDir = -1;
    static volatile boolean isFront;
    private boolean launchedSinceHome;
    private long lastReturnFromAppMs;
    /** Set while the grid is rebuilt: focus is restored instantly, without the focus animation. */
    private boolean quietFocus;
    private String moving;
    private boolean sleeping, wakingUp;
    private ObjectAnimator wiggle;
    private ValueAnimator scrollAnim;
    private int targetScrollY = 0;
    private float targetDockAlpha = 1f;
    private long lastKeyDownMs;
    private boolean rapidScroll;

    private final OvershootInterpolator focusOvershoot = new OvershootInterpolator(1.4f);
    private final DecelerateInterpolator focusDecel = new DecelerateInterpolator();
    private final DecelerateInterpolator scrollDecel = new DecelerateInterpolator(2f);

    private final Runnable idleSleepRunnable = () -> {
        if (hasWindowFocus() && moving == null && !sleeping && !isOverlayOpen()) setSleeping(true, true);
    };

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); refreshStatus(); }
    };
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            // Fire OS (com.amazon.ceviche) sends PACKAGE_CHANGED after nearly every app launch
            // and Home press. Rebuilding then knocked focus onto the gear and replayed the tile
            // animation, so only rebuild when the tiles would actually change. (An updated app
            // gets new art automatically: Art keys its cache on the app's update time.)
            String pkg = i.getData() == null ? null : i.getData().getSchemeSpecificPart();
            boolean updated = Intent.ACTION_PACKAGE_ADDED.equals(i.getAction())
                    && i.getBooleanExtra(Intent.EXTRA_REPLACING, false) && apps.contains(pkg);
            refreshLaunchIntents();
            if (updated || !visibleApps().equals(shownApps())) rebuildGrid();
        }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        pm = getPackageManager();
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        weather = new Weather(prefs);
        loadApps();
        int reqScene = getIntent() != null ? getIntent().getIntExtra("aerial_scene", -1) : -1;
        if (reqScene >= 0) prefs.edit().putString("aerial_scene_id", AerialView.SCENES[Math.min(reqScene, AerialView.SCENES.length - 1)].id).apply();
        setContentView(buildUi());
        getWindow().setBackgroundDrawable(null);   // AmbientView covers the screen; skip a full-screen fill
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        rebuildGrid();
        updateSleepCaption();
        handleDebugExtras(getIntent());

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packageReceiver, pf);
    }

    @Override
    protected void onResume() {
        super.onResume();
        isFront = true;
        IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeReceiver, tf);
        updateClock();
        refreshStatus();
        aerial.onHomeResume();
        updateSleepCaption();
        resetIdleTimer();
        if (launchedSinceHome) {
            lastReturnFromAppMs = SystemClock.uptimeMillis();
            launchedSinceHome = false;
        }
    }

    @Override
    protected void onPause() {
        isFront = false;
        super.onPause();
        root.removeCallbacks(idleSleepRunnable);
        aerial.onHomePause();
        unregisterReceiver(timeReceiver);
        if (moving != null) endMove();
        if (isOverlayOpen()) closeOverlay(false);
        if (sleeping) setSleeping(false, false);
    }

    @Override
    protected void onDestroy() {
        isFront = false;
        unregisterReceiver(packageReceiver);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        resetIdleTimer();
        if (launchedSinceHome) {
            lastReturnFromAppMs = SystemClock.uptimeMillis();
            launchedSinceHome = false;
        }
        int reqScene = intent != null ? intent.getIntExtra("aerial_scene", -1) : -1;
        if (reqScene >= 0) {
            aerial.setSceneIndex(reqScene);
            updateSleepCaption();
        }
        if (handleDebugExtras(intent)) {
            return;
        }
        if (isOverlayOpen()) {
            closeOverlay(true);
            return;
        }
        if (sleeping) {
            setSleeping(false, true);
            return;
        }
        // Home pressed while already home: back to the first app, like Apple TV.
        // Coming back from an app keeps your place instead, ignoring follow-up preemption pulses.
        if (SystemClock.uptimeMillis() - lastReturnFromAppMs > 800
                && intent != null && intent.getBooleanExtra(EXTRA_FROM_HOME_KEY, false)) {
            if (moving != null) endMove();
            if (grid.getChildCount() > 0) grid.getChildAt(0).requestFocus();
        }
    }

    private boolean handleDebugExtras(Intent intent) {
        if (intent == null) return false;
        if (intent.getBooleanExtra("open_aerials", false)) {
            if (sleeping) setSleeping(false, false);
            root.post(this::showScenePicker);
            return true;
        }
        if (intent.getBooleanExtra("open_settings", false)) {
            if (sleeping) setSleeping(false, false);
            root.post(this::showHomeSettings);
            return true;
        }
        if (intent.getBooleanExtra("open_apps", false)) {
            if (sleeping) setSleeping(false, false);
            root.post(this::showAppPicker);
            return true;
        }
        return false;
    }

    @Override
    public void onBackPressed() {
        if (isOverlayOpen()) { closeOverlay(true); return; }
        if (moving != null) { endMove(); return; }
        if (sleeping) { setSleeping(false, true); return; }
        View f = getCurrentFocus();
        if (f != null && grid.indexOfChild(f) >= COLUMNS && grid.getChildCount() > 0) {
            grid.getChildAt(0).requestFocus();   // Back from lower rows jumps to the top, like tvOS.
            root.playSoundEffect(SoundEffectConstants.NAVIGATION_UP);
        } else {
            setSleeping(true, true);             // Back from top dock slides the deck off into sleep screen.
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        resetIdleTimer();
        int code = e.getKeyCode();
        boolean isVolume = code == KeyEvent.KEYCODE_VOLUME_UP
                || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE;
        if (!isVolume) {
            if (sleeping) {
                if (e.getAction() == KeyEvent.ACTION_DOWN) {
                    wakingUp = true;
                    setSleeping(false, true);
                }
                return true;
            }
            if (wakingUp) {
                if (e.getAction() == KeyEvent.ACTION_UP) wakingUp = false;
                return true;
            }
        }
        int dir = direction(code);
        if (isOverlayOpen()) {
            if (code == KeyEvent.KEYCODE_BACK) {
                if (e.getAction() == KeyEvent.ACTION_UP) closeOverlay(true);
                return true;
            }
            if (dir != -1 && e.getAction() == KeyEvent.ACTION_DOWN) {
                View before = getCurrentFocus();
                boolean handled = super.dispatchKeyEvent(e);
                if (handled && getCurrentFocus() != before) {
                    root.playSoundEffect(SoundEffectConstants.getContantForFocusDirection(dir));
                }
                return handled;
            }
            return super.dispatchKeyEvent(e);
        }
        if (moving != null) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (dir != -1) moveBy(dir);
                else if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                        || code == KeyEvent.KEYCODE_BUTTON_A) {
                    root.playSoundEffect(SoundEffectConstants.CLICK);
                    endMove();
                } else if (code == KeyEvent.KEYCODE_BACK) endMove();
            }
            return true;
        }
        if (dir != -1 && e.getAction() == KeyEvent.ACTION_DOWN) {
            long now = SystemClock.uptimeMillis();
            rapidScroll = (e.getRepeatCount() > 0) || (now - lastKeyDownMs < 110);
            lastKeyDownMs = now;
            View f = getCurrentFocus();
            if (f != null && f.getParent() == grid) {
                View next = FocusFinder.getInstance().findNextFocus((ViewGroup) getWindow().getDecorView(), f, dir);
                if (next == null && dir == View.FOCUS_UP && grid.indexOfChild(f) < COLUMNS && sceneBtn != null) {
                    sceneBtn.requestFocus();
                    sceneBtn.playSoundEffect(SoundEffectConstants.NAVIGATION_UP);
                    return true;
                }
                if (next == null || next == f) {
                    nudge(f, dir);                  // edge: tvOS-style bump, focus stays
                    return true;
                }
                if (!rapidScroll) tilt(f, dir);
            }
            pendingDir = dir;
        }
        return super.dispatchKeyEvent(e);
    }

    // ---------------------------------------------------------------- UI

    private View buildUi() {
        root = new FrameLayout(this);
        ambient = new AmbientView(this);
        aerial = new AerialView(this, ambient, prefs);
        aerial.setOnSceneChangedListener(this::updateSleepCaption);
        root.addView(aerial, match());

        scroll = new HomeScroll(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        root.addView(scroll, match());

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        content.setPadding(dp(56), dp(32), dp(56), dp(120));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        // Header: greeting + clock on the left, glass pills on the right.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setClipChildren(false);
        content.addView(header, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout timeCol = new LinearLayout(this);
        timeCol.setOrientation(LinearLayout.VERTICAL);
        header.addView(timeCol, new LinearLayout.LayoutParams(0, -2, 1f));

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

        // Top-right glass pills: Aerial scene pill, weather + Wi-Fi capsule, Settings pill.
        status = new LinearLayout(this);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setClipChildren(false);
        header.addView(status, new LinearLayout.LayoutParams(-2, -2));

        sceneBtn = sceneButton();
        status.addView(sceneBtn, new LinearLayout.LayoutParams(-2, dp(36)));
        status.addView(new View(this), new LinearLayout.LayoutParams(dp(10), 1));

        LinearLayout capsule = new LinearLayout(this);
        capsule.setGravity(Gravity.CENTER_VERTICAL);
        capsule.setPadding(dp(14), 0, dp(14), 0);
        capsule.setBackground(glass(dp(18)));
        weatherIcon = new Icons.Weather(this);
        weatherIcon.setVisibility(View.GONE);
        capsule.addView(weatherIcon, new LinearLayout.LayoutParams(dp(19), dp(19)));
        weatherText = text(12.5f, 0xF2FFFFFF, "sans-serif-medium");
        weatherText.setPadding(dp(7), 0, 0, 0);
        weatherText.setVisibility(View.GONE);
        capsule.addView(weatherText);
        weatherSep = new View(this);
        weatherSep.setBackgroundColor(0x38FFFFFF);
        weatherSep.setVisibility(View.GONE);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(1), dp(15));
        sp.leftMargin = dp(11);
        sp.rightMargin = dp(11);
        capsule.addView(weatherSep, sp);
        wifiIcon = new Icons.Wifi(this);
        capsule.addView(wifiIcon, new LinearLayout.LayoutParams(dp(17), dp(17)));
        status.addView(capsule, new LinearLayout.LayoutParams(-2, dp(36)));
        status.addView(new View(this), new LinearLayout.LayoutParams(dp(10), 1));
        gearBtn = gearButton();
        status.addView(gearBtn, new LinearLayout.LayoutParams(-2, dp(36)));

        // Wrap the grid in a container that holds the frosted-glass dock tray behind Row 1.
        dockWrap = new FrameLayout(this);
        dockWrap.setClipChildren(false);
        dockWrap.setClipToPadding(false);
        content.addView(dockWrap, new LinearLayout.LayoutParams(-1, -2));

        dockTray = new View(this) {
            @Override public boolean hasOverlappingRendering() { return false; }
        };
        GradientDrawable trayBg = new GradientDrawable();
        trayBg.setCornerRadius(dp(22));
        trayBg.setColor(0x28FFFFFF);
        trayBg.setStroke(1, 0x3DFFFFFF);
        dockTray.setBackground(trayBg);
        dockWrap.addView(dockTray, new FrameLayout.LayoutParams(-1, dp(100)));

        grid = new GridLayout(this);
        grid.setColumnCount(COLUMNS);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        dockWrap.addView(grid, new FrameLayout.LayoutParams(-1, -2));

        // Bottom-left wallpaper location shown when the dock slides away in Sleep Screen mode.
        sleepCaption = new LinearLayout(this);
        sleepCaption.setOrientation(LinearLayout.VERTICAL);
        sleepCaption.setPadding(dp(56), 0, dp(56), dp(40));
        sleepCaption.setAlpha(0f);
        sleepCaption.setTranslationY(dp(16));

        sleepLocation = text(12, 0xD9FFFFFF, "sans-serif-medium");
        sleepLocation.setLetterSpacing(0.20f);
        sleepLocation.setShadowLayer(dp(8), 0, dp(2), 0xB0000000);
        sleepCaption.addView(sleepLocation);

        FrameLayout.LayoutParams scp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        root.addView(sleepCaption, scp);

        overlayHost = new FrameLayout(this);
        overlayHost.setClipChildren(false);
        overlayHost.setClipToPadding(false);
        overlayHost.setVisibility(View.GONE);
        root.addView(overlayHost, match());

        return root;
    }

    private void rebuildGrid() {
        refreshLaunchIntents();
        String keepFocus = moving != null ? moving : lastFocused;
        // Removing the focused tile makes Android hand focus to the first focusable view (the
        // gear), which animated and scrolled to the top. Block that while the tiles are swapped.
        boolean refocus = grid.hasFocus() || scroll.findFocus() == null;
        int focusability = scroll.getDescendantFocusability();
        scroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        grid.removeAllViews();
        int gap = dp(20);
        int pad = dp(16);
        tileW = (getResources().getDisplayMetrics().widthPixels - dp(112) - gap * (COLUMNS - 1)) / COLUMNS;
        tileH = tileW * 9 / 16;
        LinearLayout.LayoutParams dwlp = (LinearLayout.LayoutParams) dockWrap.getLayoutParams();
        dwlp.leftMargin = -pad;
        dwlp.rightMargin = -pad;
        dwlp.topMargin = dp(290) - pad;
        dockWrap.setLayoutParams(dwlp);
        FrameLayout.LayoutParams tlp = (FrameLayout.LayoutParams) dockTray.getLayoutParams();
        tlp.height = tileH + pad * 2;
        tlp.leftMargin = 0;
        tlp.rightMargin = 0;
        tlp.topMargin = 0;
        dockTray.setLayoutParams(tlp);
        grid.setPadding(pad, pad, pad, 0);
        // Art is made 1.15x tile size, so focused tiles (scaled 1.14x) show it about 1:1.
        if (art == null || art.width() != Math.round(tileW * 1.15f)) {
            art = new Art(this, Math.round(tileW * 1.15f), Math.round(tileH * 1.15f), dp(12) * 1.15f);
        }
        View focusTarget = null;
        int n = 0;
        for (String pkg : apps) {
            if (!isLaunchable(pkg)) continue;
            View tile = tile(pkg);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = tileW;
            lp.height = tileH;
            lp.rightMargin = (n % COLUMNS == COLUMNS - 1) ? 0 : gap;
            lp.bottomMargin = n < COLUMNS ? dp(54) : dp(38);
            grid.addView(tile, lp);
            if (focusTarget == null || pkg.equals(keepFocus)) focusTarget = tile;
            n++;
        }
        View add = addTile();
        GridLayout.LayoutParams alp = new GridLayout.LayoutParams();
        alp.width = tileW;
        alp.height = tileH;
        alp.bottomMargin = dp(44);
        grid.addView(add, alp);
        if (focusTarget == null) focusTarget = add;
        scroll.setDescendantFocusability(focusability);
        if (refocus) {
            quietFocus = true;
            focusTarget.requestFocus();
            quietFocus = false;
            if (moving != null) startWiggle(focusTarget);
        }
    }

    private List<String> visibleApps() {
        List<String> out = new ArrayList<>();
        for (String pkg : apps) if (isLaunchable(pkg)) out.add(pkg);
        return out;
    }

    private List<String> shownApps() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < grid.getChildCount(); i++) {
            Object tag = grid.getChildAt(i).getTag();
            if (tag instanceof String) out.add((String) tag);
        }
        return out;
    }

    private View tile(String pkg) {
        TileView tile = new TileView(this, dp(12));
        tile.setTag(pkg);
        tile.setCameraDistance(9000 * getResources().getDisplayMetrics().density);
        tile.setArt(art.get(pkg));

        tile.setOnFocusChangeListener((v, has) -> {
            v.animate().cancel();
            if (has && quietFocus) {
                pendingDir = -1;
                v.setScaleX(1.14f);
                v.setScaleY(1.14f);
                v.setTranslationZ(dp(14));
                v.setRotationX(0);
                v.setRotationY(0);
                tile.focusFx(true, false);
                onTileFocused(pkg, v, true);
            } else if (has) {
                // Arrive with a slight lean from the direction we came from, then settle.
                if (!rapidScroll) {
                    if (pendingDir == View.FOCUS_RIGHT) v.setRotationY(-6f);
                    else if (pendingDir == View.FOCUS_LEFT) v.setRotationY(6f);
                    else if (pendingDir == View.FOCUS_DOWN) v.setRotationX(6f);
                    else if (pendingDir == View.FOCUS_UP) v.setRotationX(-6f);
                }
                pendingDir = -1;
                v.animate().scaleX(1.14f).scaleY(1.14f).translationZ(dp(14)).rotationX(0).rotationY(0)
                        .setInterpolator(focusOvershoot).setDuration(260).start();
                tile.focusFx(true, true);
                onTileFocused(pkg, v, false);
            } else {
                v.animate().scaleX(1f).scaleY(1f).translationZ(0).rotationX(0).rotationY(0).translationX(0)
                        .setInterpolator(focusDecel).setDuration(180).start();
                tile.focusFx(false, true);
            }
        });
        tile.setOnClickListener(v -> launchApp(pkg, v));
        tile.setOnLongClickListener(v -> { showTileMenu(pkg); return true; });
        tile.setOnKeyListener((v, code, ev) -> {
            if (code != KeyEvent.KEYCODE_MENU) return false;
            if (ev.getAction() == KeyEvent.ACTION_UP) showTileMenu(pkg);
            return true;
        });
        return tile;
    }

    private void onTileFocused(String pkg, View tile, boolean quiet) {
        lastFocused = pkg;
        int row = grid.indexOfChild(tile) / COLUMNS;
        if (!aerial.isLive()) {
            int[] pal = art.palette(pkg);
            ambient.setColors(pal[0], pal[1]);
        }

        // tvOS layout: the first row sits in the bottom dock; lower rows slide up.
        ambient.setDim(row > 0 ? 1f : 0f);
        setDockAlpha(row > 0 ? 0f : 1f, quiet, 220);
        scrollTo(rowScroll(row), quiet);
    }

    private void setDockAlpha(float a, boolean instant, long duration) {
        if (instant) {
            targetDockAlpha = a;
            dockTray.animate().cancel();
            dockTray.setAlpha(a);
            dockTray.setVisibility(a > 0.004f ? View.VISIBLE : View.INVISIBLE);
            return;
        }
        if (Math.abs(targetDockAlpha - a) < 0.001f) return;
        targetDockAlpha = a;
        dockTray.animate().cancel();
        if (a > 0.004f) dockTray.setVisibility(View.VISIBLE);
        dockTray.animate().alpha(a).setDuration(duration).withEndAction(() -> {
            if (targetDockAlpha <= 0.004f) dockTray.setVisibility(View.INVISIBLE);
        }).start();
    }

    /** Scroll position that puts {@code row} into view (computed, so it works before layout). */
    private int rowScroll(int row) {
        return row > 0 ? ((View) grid.getParent()).getTop() + row * (tileH + dp(44)) - dp(190) : 0;
    }

    private void scrollTo(int y, boolean instant) {
        y = Math.max(0, y);
        if (instant) {
            if (scrollAnim != null) scrollAnim.cancel();
            targetScrollY = y;
            scroll.scrollTo(0, y);
            return;
        }
        if (targetScrollY == y && ((scrollAnim != null && scrollAnim.isRunning()) || scroll.getScrollY() == y)) {
            return;
        }
        targetScrollY = y;
        if (scrollAnim != null) scrollAnim.cancel();
        int startY = scroll.getScrollY();
        if (startY == y) return;
        scrollAnim = ValueAnimator.ofInt(startY, y).setDuration(260);
        scrollAnim.setInterpolator(scrollDecel);
        scrollAnim.addUpdateListener(a -> scroll.scrollTo(0, (int) a.getAnimatedValue()));
        scrollAnim.start();
    }

    private void tilt(View v, int dir) {
        float ry = dir == View.FOCUS_RIGHT ? 7f : dir == View.FOCUS_LEFT ? -7f : 0f;
        float rx = dir == View.FOCUS_DOWN ? -7f : dir == View.FOCUS_UP ? 7f : 0f;
        v.setRotationY(ry);
        v.setRotationX(rx);
    }

    /** Pressing towards an edge: lean and bump that way, then spring back. */
    private void nudge(View v, int dir) {
        float dx = dir == View.FOCUS_RIGHT ? dp(9) : dir == View.FOCUS_LEFT ? -dp(9) : 0;
        float dy = dir == View.FOCUS_DOWN ? dp(7) : dir == View.FOCUS_UP ? -dp(7) : 0;
        float ry = dx > 0 ? 9f : dx < 0 ? -9f : 0;
        float rx = dy > 0 ? -9f : dy < 0 ? 9f : 0;
        v.animate().cancel();
        v.animate().translationX(dx).translationY(dy).rotationY(ry).rotationX(rx).setDuration(90)
                .setInterpolator(focusDecel)
                .withEndAction(() -> v.animate().translationX(0).translationY(0).rotationX(0).rotationY(0)
                        .setInterpolator(new OvershootInterpolator(2.2f)).setDuration(320).start())
                .start();
    }

    private static GradientDrawable glass(float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(radius);
        g.setColor(0x28FFFFFF);
        g.setStroke(1, 0x3DFFFFFF);
        return g;
    }

    private View sceneButton() {
        LinearLayout b = new LinearLayout(this);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(dp(12), 0, dp(13), 0);
        b.setFocusable(true);
        b.setClickable(true);
        GradientDrawable bg = glass(dp(18));
        b.setBackground(bg);
        Icons.Scene icon = new Icons.Scene(this);
        b.addView(icon, new LinearLayout.LayoutParams(dp(16), dp(16)));
        TextView lbl = text(12.5f, 0xF2FFFFFF, "sans-serif-medium");
        lbl.setText("Aerials");
        lbl.setPadding(dp(6), 0, 0, 0);
        b.addView(lbl);
        b.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xFFF5F5F7 : 0x28FFFFFF);
            bg.setStroke(1, has ? 0xFFFFFFFF : 0x3DFFFFFF);
            icon.setColor(has ? 0xFF0B0B0F : 0xF2FFFFFF);
            lbl.setTextColor(has ? 0xFF0B0B0F : 0xF2FFFFFF);
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).translationZ(has ? dp(8) : 0)
                    .setInterpolator(new OvershootInterpolator(1.6f)).setDuration(220).start();
            if (has && !sleeping) {
                ambient.setDim(0f);
                setDockAlpha(1f, false, 180);
                scrollTo(0, false);
            }
        });
        b.setOnClickListener(v -> showScenePicker());
        return b;
    }

    private View gearButton() {
        LinearLayout b = new LinearLayout(this);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(dp(12), 0, dp(13), 0);
        b.setFocusable(true);
        b.setClickable(true);
        GradientDrawable bg = glass(dp(18));
        b.setBackground(bg);
        Icons.Gear gear = new Icons.Gear(this);
        b.addView(gear, new LinearLayout.LayoutParams(dp(16), dp(16)));
        TextView lbl = text(12.5f, 0xF2FFFFFF, "sans-serif-medium");
        lbl.setText("Settings");
        lbl.setPadding(dp(6), 0, 0, 0);
        b.addView(lbl);
        b.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xFFF5F5F7 : 0x28FFFFFF);
            bg.setStroke(1, has ? 0xFFFFFFFF : 0x3DFFFFFF);
            gear.setColor(has ? 0xFF0B0B0F : 0xF2FFFFFF);
            lbl.setTextColor(has ? 0xFF0B0B0F : 0xF2FFFFFF);
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).translationZ(has ? dp(8) : 0)
                    .setInterpolator(new OvershootInterpolator(1.6f)).setDuration(220).start();
            gear.animate().rotation(has ? 45f : 0f).setDuration(380).setInterpolator(focusDecel).start();
            if (has && !sleeping) {
                ambient.setDim(0f);
                setDockAlpha(1f, false, 180);
                scrollTo(0, false);
            }
        });
        b.setOnClickListener(v -> showHomeSettings());
        return b;
    }

    private boolean isOverlayOpen() {
        return overlayHost != null && overlayHost.getVisibility() == View.VISIBLE;
    }

    private void openOverlay(View panel, View initialFocus, boolean hideDock) {
        if (overlayHost == null) return;
        View cur = getCurrentFocus();
        if (cur != null && !isOverlayOpen()) returnFocusView = cur;
        scroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        overlayHost.removeAllViews();
        overlayHost.setVisibility(View.VISIBLE);
        overlayHost.addView(panel);

        DecelerateInterpolator decel = new DecelerateInterpolator(1.8f);
        if (hideDock) {
            ambient.setDim(0f);
            scrollTo(0, true);
            setDockAlpha(1f, true, 0);
            status.animate().alpha(0f).translationY(-dp(12)).setDuration(320).setInterpolator(decel).withLayer().start();
            dockWrap.animate().alpha(0f).translationY(dp(240)).setDuration(420).setInterpolator(decel).withLayer().start();
        } else {
            if (overlayHidingDock) {
                setDockAlpha(1f, true, 0);
                status.animate().alpha(1f).translationY(0f).setDuration(320).setInterpolator(decel).withLayer().start();
                dockWrap.animate().alpha(1f).translationY(0f).setDuration(420).setInterpolator(decel).withLayer().start();
            }
            ambient.setDim(0.45f);
        }
        overlayHidingDock = hideDock;

        panel.setAlpha(0f);
        panel.setTranslationY(dp(28));
        panel.animate().alpha(1f).translationY(0f).setDuration(300).setInterpolator(decel).withLayer().start();
        if (initialFocus != null) panel.post(initialFocus::requestFocus);
    }

    private void closeOverlay(boolean animate) {
        if (!isOverlayOpen()) return;
        overlayHost.removeAllViews();
        overlayHost.setVisibility(View.GONE);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);

        DecelerateInterpolator decel = new DecelerateInterpolator(1.8f);
        if (overlayHidingDock) {
            overlayHidingDock = false;
            setDockAlpha(1f, true, 0);
            if (animate) {
                status.animate().alpha(1f).translationY(0f).setDuration(340).setInterpolator(decel).withLayer().start();
                dockWrap.animate().alpha(1f).translationY(0f).setDuration(420).setInterpolator(decel).withLayer().start();
            } else {
                status.setAlpha(1f); status.setTranslationY(0f);
                dockWrap.setAlpha(1f); dockWrap.setTranslationY(0f);
            }
        } else {
            ambient.setDim(0f);
        }

        if (returnFocusView != null && returnFocusView.isAttachedToWindow()) {
            quietFocus = true;
            returnFocusView.requestFocus();
            quietFocus = false;
        } else if (grid.getChildCount() > 0) {
            quietFocus = true;
            grid.getChildAt(0).requestFocus();
            quietFocus = false;
        }
        returnFocusView = null;
    }

    private static GradientDrawable sheetBg(float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(radius);
        g.setColor(0xE812141C);
        g.setStroke(1, 0x42FFFFFF);
        return g;
    }

    private View tvosPillButton(String labelText, Runnable onClick) {
        LinearLayout b = new LinearLayout(this);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), 0, dp(16), 0);
        b.setFocusable(true);
        b.setClickable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(17));
        bg.setColor(0x28FFFFFF);
        bg.setStroke(1, 0x3DFFFFFF);
        b.setBackground(bg);
        TextView t = text(12f, 0xF2FFFFFF, "sans-serif-medium");
        t.setText(labelText);
        b.addView(t);
        b.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xFFF5F5F7 : 0x28FFFFFF);
            bg.setStroke(1, has ? 0xFFFFFFFF : 0x3DFFFFFF);
            t.setTextColor(has ? 0xFF0B0B0F : 0xF2FFFFFF);
            v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f)
                    .setInterpolator(new OvershootInterpolator(1.5f)).setDuration(200).start();
        });
        b.setOnClickListener(v -> onClick.run());
        return b;
    }

    /**
     * tvOS-inspired Aerials Studio:
     * Slides the app dock down so the live wallpaper fills the screen behind a frosted-glass
     * bottom sheet of 16:9 scene cards.
     */
    private void showScenePicker() {
        FrameLayout wrap = new FrameLayout(this);
        wrap.setClipChildren(false);
        wrap.setClipToPadding(false);

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setClipChildren(false);
        sheet.setClipToPadding(false);
        sheet.setBackground(sheetBg(dp(26)));
        sheet.setPadding(dp(28), dp(22), dp(28), dp(24));

        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        slp.leftMargin = dp(48);
        slp.rightMargin = dp(48);
        slp.bottomMargin = dp(26);
        wrap.addView(sheet, slp);

        // Header row: Title + subtitle on left, Playback Mode + Sleep Screen + Done pills on right.
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setClipChildren(false);
        sheet.addView(head, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        head.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView hTitle = text(19, Color.WHITE, "sans-serif-medium");
        hTitle.setText("Apple TV Aerials");
        titleCol.addView(hTitle);

        TextView hSub = text(11.5f, 0x99FFFFFF, "sans-serif");
        hSub.setText("Select a live 1080p HEVC wallpaper · Smooth dissolve at end of clip");
        hSub.setPadding(0, dp(2), 0, 0);
        titleCol.addView(hSub);

        final TextView[] modeTextRef = new TextView[1];
        View modePill = tvosPillButton("End of Clip: " + AerialView.END_MODE_LABELS[AerialView.endMode(prefs)], () -> {
            int next = AerialView.nextEndMode(prefs);
            if (modeTextRef[0] != null) {
                modeTextRef[0].setText("End of Clip: " + AerialView.END_MODE_LABELS[next]);
            }
        });
        modeTextRef[0] = (TextView) ((ViewGroup) modePill).getChildAt(0);
        head.addView(modePill, new LinearLayout.LayoutParams(-2, dp(34)));
        head.addView(new View(this), new LinearLayout.LayoutParams(dp(10), 1));

        View sleepPill = tvosPillButton("Sleep Screen", () -> {
            closeOverlay(false);
            setSleeping(true, true);
        });
        head.addView(sleepPill, new LinearLayout.LayoutParams(-2, dp(34)));
        head.addView(new View(this), new LinearLayout.LayoutParams(dp(10), 1));

        View donePill = tvosPillButton("Done", () -> closeOverlay(true));
        head.addView(donePill, new LinearLayout.LayoutParams(-2, dp(34)));

        // 5-column grid of 16:9 tvOS Scene Cards, scrolling once there are more than ~3.5 rows.
        int cols = 5;
        int gap = dp(12);
        int cardH = dp(68);
        int edge = dp(8); // room for the focused card's 1.06x scale inside the clipped scroll area
        int availW = getResources().getDisplayMetrics().widthPixels - dp(96) - dp(56) - edge * 2;
        int cardW = (availW - gap * (cols - 1)) / cols;
        int rows = (AerialView.SCENES.length + cols - 1) / cols;

        ScrollView gridScroll = new ScrollView(this);
        gridScroll.setVerticalScrollBarEnabled(false);
        gridScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        gridScroll.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRect(0, 0, view.getWidth(), view.getHeight());
            }
        });
        gridScroll.setClipToOutline(true);
        int fullH = rows * cardH + (rows - 1) * gap + edge * 2;
        int peekH = 3 * (cardH + gap) + cardH / 2 + edge;
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, Math.min(fullH, peekH));
        glp.topMargin = dp(8);
        sheet.addView(gridScroll, glp);

        GridLayout sg = new GridLayout(this);
        sg.setColumnCount(cols);
        sg.setClipChildren(false);
        sg.setClipToPadding(false);
        sg.setPadding(edge, edge, edge, edge);
        gridScroll.addView(sg, new ScrollView.LayoutParams(-1, -2));

        final TextView[] badges = new TextView[AerialView.SCENES.length];
        View initialFocus = null;
        int activeIdx = aerial.sceneIndex();

        for (int i = 0; i < AerialView.SCENES.length; i++) {
            final int idx = i;
            AerialView.Scene s = AerialView.SCENES[i];

            FrameLayout card = new FrameLayout(this);
            card.setFocusable(true);
            card.setClickable(true);
            card.setPadding(dp(12), dp(9), dp(12), dp(9));

            GradientDrawable cardBg = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    new int[]{s.colorA, s.colorB});
            cardBg.setCornerRadius(dp(14));
            cardBg.setStroke(idx == activeIdx ? dp(2) : 1, idx == activeIdx ? 0xCCFFFFFF : 0x36FFFFFF);
            card.setBackground(cardBg);

            // Top-right badge (ACTIVE / CACHED / LIVE / GLOW)
            TextView badge = text(9f, 0xE6FFFFFF, "sans-serif-medium");
            badge.setLetterSpacing(0.10f);
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setCornerRadius(dp(8));
            badgeBg.setColor(idx == activeIdx ? 0xFFF5F5F7 : 0x45000000);
            badge.setBackground(badgeBg);
            badge.setTextColor(idx == activeIdx ? 0xFF0B0B0F : 0xD9FFFFFF);
            badge.setPadding(dp(6), dp(2), dp(6), dp(2));
            badge.setText(sceneBadgeText(idx));
            badges[idx] = badge;
            card.addView(badge, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END));

            // Bottom-left title + location subtitle
            LinearLayout meta = new LinearLayout(this);
            meta.setOrientation(LinearLayout.VERTICAL);
            String shortTitle = s.title.contains(" — ") ? s.title.substring(0, s.title.indexOf(" — ")) : s.title;
            TextView cTitle = text(12.5f, Color.WHITE, "sans-serif-medium");
            cTitle.setSingleLine(true);
            cTitle.setEllipsize(TextUtils.TruncateAt.END);
            cTitle.setShadowLayer(dp(4), 0, dp(1), 0x99000000);
            cTitle.setText(shortTitle);
            meta.addView(cTitle);

            TextView cSub = text(10f, 0xC8FFFFFF, "sans-serif");
            cSub.setSingleLine(true);
            cSub.setEllipsize(TextUtils.TruncateAt.END);
            cSub.setText(s.subtitle);
            meta.addView(cSub);

            card.addView(meta, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.START));

            card.setOnFocusChangeListener((v, has) -> {
                boolean isSel = (idx == aerial.sceneIndex());
                cardBg.setStroke(has ? dp(3) : (isSel ? dp(2) : 1),
                        has ? 0xFFFFFFFF : (isSel ? 0xCCFFFFFF : 0x36FFFFFF));
                v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).translationZ(has ? dp(10) : 0)
                        .setInterpolator(new OvershootInterpolator(1.5f)).setDuration(200).start();
                if (has) hSub.setText(s.title + "  ·  " + s.location);
            });

            card.setOnClickListener(v -> {
                aerial.setSceneIndex(idx);
                updateSleepCaption();
                for (int j = 0; j < badges.length; j++) {
                    boolean sel = (j == idx);
                    TextView bView = badges[j];
                    bView.setText(sceneBadgeText(j));
                    GradientDrawable bBg = (GradientDrawable) bView.getBackground();
                    bBg.setColor(sel ? 0xFFF5F5F7 : 0x45000000);
                    bView.setTextColor(sel ? 0xFF0B0B0F : 0xD9FFFFFF);
                }
            });

            GridLayout.LayoutParams clp = new GridLayout.LayoutParams();
            clp.width = cardW;
            clp.height = cardH;
            clp.rightMargin = (i % cols == cols - 1) ? 0 : gap;
            clp.bottomMargin = i / cols < (AerialView.SCENES.length - 1) / cols ? gap : 0;
            sg.addView(card, clp);

            if (i == activeIdx || initialFocus == null) initialFocus = card;
        }

        openOverlay(wrap, initialFocus, true);
    }

    private String sceneBadgeText(int idx) {
        if (idx == aerial.sceneIndex()) return "ACTIVE";
        if (AerialView.SCENES[idx].url == null) return "GLOW";
        return aerial.isCached(idx) ? "CACHED" : "LIVE";
    }

    private long idleSleepTimeoutMs() {
        return prefs != null ? prefs.getLong("sleep_timeout_ms", DEFAULT_IDLE_SLEEP_MS) : DEFAULT_IDLE_SLEEP_MS;
    }

    private void resetIdleTimer() {
        if (root == null) return;
        root.removeCallbacks(idleSleepRunnable);
        long timeout = idleSleepTimeoutMs();
        if (!sleeping && timeout > 0) root.postDelayed(idleSleepRunnable, timeout);
    }

    private void updateSleepCaption() {
        if (sleepLocation == null || aerial == null) return;
        sleepLocation.setText(aerial.scene().location);
    }

    private void setSleeping(boolean on, boolean animate) {
        if (sleeping == on) return;
        sleeping = on;
        root.removeCallbacks(idleSleepRunnable);
        updateSleepCaption();

        if (on) {
            View f = getCurrentFocus();
            if (f == null || f.getParent() != grid || grid.indexOfChild(f) >= COLUMNS) {
                if (grid.getChildCount() > 0) {
                    quietFocus = true;
                    grid.getChildAt(0).requestFocus();
                    quietFocus = false;
                }
            }
            ambient.setDim(0f);
            scrollTo(0, !animate);
        } else {
            resetIdleTimer();
        }

        status.animate().cancel();
        dockWrap.animate().cancel();
        setDockAlpha(1f, true, 0);
        sleepCaption.animate().cancel();

        float slideY = dp(240);
        if (!animate) {
            status.setAlpha(on ? 0f : 1f);
            status.setTranslationY(on ? -dp(12) : 0f);
            dockWrap.setAlpha(on ? 0f : 1f);
            dockWrap.setTranslationY(on ? slideY : 0f);
            sleepCaption.setAlpha(on ? 1f : 0f);
            sleepCaption.setTranslationY(on ? 0f : dp(16));
            return;
        }

        DecelerateInterpolator decel = new DecelerateInterpolator(1.8f);
        if (on) {
            status.animate().alpha(0f).translationY(-dp(12)).setDuration(420).setInterpolator(decel).withLayer().start();
            dockWrap.animate().alpha(0f).translationY(slideY).setDuration(600).setInterpolator(decel).withLayer().start();
            sleepCaption.animate().alpha(1f).translationY(0f).setStartDelay(140).setDuration(560).setInterpolator(decel).withLayer().start();
        } else {
            scrollTo(0, true);
            sleepCaption.animate().alpha(0f).translationY(dp(16)).setStartDelay(0).setDuration(240).setInterpolator(decel).withLayer().start();
            status.animate().alpha(1f).translationY(0f).setDuration(420).setInterpolator(decel).withLayer().start();
            dockWrap.animate().alpha(1f).translationY(0f).setDuration(520).setInterpolator(decel).withLayer().start();
        }
    }

    /** Glass "+" tile after the apps, like Monet: add or remove apps from Home. */
    private View addTile() {
        FrameLayout t = new FrameLayout(this);
        t.setFocusable(true);
        t.setClickable(true);
        GradientDrawable bg = glass(dp(12));
        t.setBackground(bg);
        TextView plus = text(34, 0xD9FFFFFF, "sans-serif-thin");
        plus.setText("+");
        plus.setGravity(Gravity.CENTER);
        t.addView(plus, match());
        t.setOnFocusChangeListener((v, has) -> {
            v.animate().cancel();
            bg.setColor(has ? 0x40FFFFFF : 0x28FFFFFF);
            bg.setStroke(has ? dp(2) : 1, has ? 0xF2FFFFFF : 0x3DFFFFFF);
            if (has && quietFocus) {
                v.setScaleX(1.14f);
                v.setScaleY(1.14f);
                v.setTranslationZ(dp(14));
            } else {
                v.animate().scaleX(has ? 1.14f : 1f).scaleY(has ? 1.14f : 1f).translationZ(has ? dp(14) : 0)
                        .rotationX(0).rotationY(0)
                        .setInterpolator(has ? focusOvershoot : focusDecel)
                        .setDuration(has ? 260 : 180).start();
            }
            if (has) {
                pendingDir = -1;
                lastFocused = null;
                if (!aerial.isLive()) ambient.setColors(0xFF34364A, 0xFF1A1C2C);
                int row = grid.indexOfChild(v) / COLUMNS;
                ambient.setDim(row > 0 ? 1f : 0f);
                setDockAlpha(row > 0 ? 0f : 1f, quietFocus, 220);
                scrollTo(rowScroll(row), quietFocus);
            }
        });
        t.setOnClickListener(v -> showAppPicker());
        return t;
    }

    private void refreshStatus() {
        weather.get((temp, wmo, day) -> {
            weatherIcon.set(Icons.kindFor(wmo), day);
            weatherText.setText(temp + "°  " + Icons.describe(wmo));
            weatherIcon.setVisibility(View.VISIBLE);
            weatherText.setVisibility(View.VISIBLE);
            weatherSep.setVisibility(View.VISIBLE);
        });
        int level = -1;
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        NetworkCapabilities caps = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            WifiManager wm = getApplicationContext().getSystemService(WifiManager.class);
            level = wm == null ? 3 : WifiManager.calculateSignalLevel(wm.getConnectionInfo().getRssi(), 4);
            level = Math.max(1, level);
        } else if (caps != null) {
            level = 3;   // wired / other transport
        }
        wifiIcon.setLevel(level);
    }

    // ---------------------------------------------------------------- move mode (tvOS jiggle)

    private void startMove(String pkg) {
        moving = pkg;
        View t = grid.findViewWithTag(pkg);
        if (t != null) startWiggle(t);
    }

    private void startWiggle(View t) {
        if (wiggle != null) wiggle.cancel();
        wiggle = ObjectAnimator.ofFloat(t, "rotation", -1.6f, 1.6f);
        wiggle.setDuration(140);
        wiggle.setRepeatMode(ValueAnimator.REVERSE);
        wiggle.setRepeatCount(ValueAnimator.INFINITE);
        wiggle.start();
    }

    private void moveBy(int dir) {
        int i = apps.indexOf(moving);
        int j = i + (dir == View.FOCUS_LEFT ? -1 : dir == View.FOCUS_RIGHT ? 1
                : dir == View.FOCUS_UP ? -COLUMNS : COLUMNS);
        if (i < 0 || j < 0 || j >= apps.size()) return;
        apps.remove(i);
        apps.add(j, moving);
        saveApps();
        rebuildGrid();
        root.playSoundEffect(SoundEffectConstants.getContantForFocusDirection(dir));
    }

    private void endMove() {
        if (wiggle != null) wiggle.cancel();
        View t = moving == null ? null : grid.findViewWithTag(moving);
        if (t != null) t.setRotation(0);
        moving = null;
    }

    // ---------------------------------------------------------------- behaviour

    private void launchApp(String pkg, View from) {
        Intent i = launchIntents.get(pkg);
        if (i == null) {
            i = resolveLaunchIntent(pkg);
            if (i != null) launchIntents.put(pkg, i);
        }
        if (i != null) launch(i, from);
    }

    /**
     * Fire OS's ACTION_SETTINGS goes through a trampoline that briefly shows Amazon's home
     * (which HomeGuard would bounce), so open the Settings screen itself (LEANBACK_SETTINGS).
     */
    private void openSettings(View from) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.LEANBACK_SETTINGS");
        if (i.resolveActivity(pm) == null) i = new Intent(Settings.ACTION_SETTINGS);
        launch(i, from);
    }

    private void launch(Intent i, View from) {
        if (aerial != null) aerial.prepareForAppLaunch();
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            startActivity(i);
            overridePendingTransition(0, 0);
            launchedSinceHome = true;
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * tvOS Control Center / Home Settings blade on the right side of the screen.
     */
    private void showHomeSettings() {
        FrameLayout wrap = new FrameLayout(this);
        wrap.setClipChildren(false);
        wrap.setClipToPadding(false);

        LinearLayout blade = new LinearLayout(this);
        blade.setOrientation(LinearLayout.VERTICAL);
        blade.setClipChildren(false);
        blade.setClipToPadding(false);
        blade.setBackground(sheetBg(dp(26)));
        blade.setPadding(dp(26), dp(24), dp(26), dp(24));

        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(420), -2, Gravity.END | Gravity.CENTER_VERTICAL);
        blp.rightMargin = dp(48);
        wrap.addView(blade, blp);

        TextView hTitle = text(20, Color.WHITE, "sans-serif-medium");
        hTitle.setText("Calm Home");
        blade.addView(hTitle);

        TextView hSub = text(11.5f, 0x99FFFFFF, "sans-serif");
        hSub.setText("Wallpapers, Sleep Screen & Home customization");
        hSub.setPadding(0, dp(2), 0, dp(16));
        blade.addView(hSub);

        AerialView.Scene curScene = aerial.scene();
        String shortScene = curScene.title.contains(" — ")
                ? curScene.title.substring(0, curScene.title.indexOf(" — "))
                : curScene.title;

        View rowAerials = settingsRow("Aerial Wallpaper", shortScene, null, () -> {
            returnFocusView = gearBtn;
            showScenePicker();
        });
        blade.addView(rowAerials, rowLp());

        final TextView[] endValRef = new TextView[1];
        View rowEndMode = settingsRow("When Video Ends", AerialView.END_MODE_LABELS[AerialView.endMode(prefs)], endValRef, () -> {
            int next = AerialView.nextEndMode(prefs);
            if (endValRef[0] != null) endValRef[0].setText(AerialView.END_MODE_LABELS[next]);
        });
        blade.addView(rowEndMode, rowLp());

        final TextView[] sleepValRef = new TextView[1];
        View rowTimeout = settingsRow("Sleep Timeout", sleepTimeoutLabel(idleSleepTimeoutMs()), sleepValRef, () -> {
            long cur = idleSleepTimeoutMs();
            int idx = 0;
            for (int i = 0; i < SLEEP_TIMEOUT_OPTIONS.length; i++) {
                if (SLEEP_TIMEOUT_OPTIONS[i] == cur) { idx = i; break; }
            }
            int nextIdx = (idx + 1) % SLEEP_TIMEOUT_OPTIONS.length;
            long nextMs = SLEEP_TIMEOUT_OPTIONS[nextIdx];
            prefs.edit().putLong("sleep_timeout_ms", nextMs).apply();
            resetIdleTimer();
            if (sleepValRef[0] != null) sleepValRef[0].setText(SLEEP_TIMEOUT_LABELS[nextIdx]);
        });
        blade.addView(rowTimeout, rowLp());

        View rowSleepNow = settingsRow("Start Sleep Screen", "Now", null, () -> {
            closeOverlay(false);
            setSleeping(true, true);
        });
        blade.addView(rowSleepNow, rowLp());

        View rowApps = settingsRow("Apps on Home", visibleApps().size() + " shown", null, this::showAppPicker);
        blade.addView(rowApps, rowLp());

        View sep = new View(this);
        sep.setBackgroundColor(0x24FFFFFF);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, 1);
        slp.topMargin = dp(6);
        slp.bottomMargin = dp(12);
        blade.addView(sep, slp);

        View rowFireOs = settingsRow("Fire TV System Settings", "Network, Display & Audio", null, () -> {
            closeOverlay(false);
            openSettings(gearBtn);
        });
        blade.addView(rowFireOs, rowLp());

        openOverlay(wrap, rowAerials, false);
    }

    private String sleepTimeoutLabel(long ms) {
        for (int i = 0; i < SLEEP_TIMEOUT_OPTIONS.length; i++) {
            if (SLEEP_TIMEOUT_OPTIONS[i] == ms) return SLEEP_TIMEOUT_LABELS[i];
        }
        return "After 2 Min";
    }

    private LinearLayout.LayoutParams rowLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(48));
        lp.bottomMargin = dp(8);
        return lp;
    }

    private View settingsRow(String titleStr, String valueStr, TextView[] valOut, Runnable onClick) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), 0, dp(16), 0);
        r.setFocusable(true);
        r.setClickable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(0x1FFFFFFF);
        bg.setStroke(1, 0x2EFFFFFF);
        r.setBackground(bg);

        TextView t = text(13.5f, 0xF2FFFFFF, "sans-serif-medium");
        t.setText(titleStr);
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView vText = text(12.5f, 0xA6FFFFFF, "sans-serif");
        vText.setText(valueStr != null ? valueStr : "");
        if (valOut != null && valOut.length > 0) valOut[0] = vText;
        r.addView(vText, new LinearLayout.LayoutParams(-2, -2));

        r.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xFFF5F5F7 : 0x1FFFFFFF);
            bg.setStroke(1, has ? 0xFFFFFFFF : 0x2EFFFFFF);
            t.setTextColor(has ? 0xFF0B0B0F : 0xF2FFFFFF);
            vText.setTextColor(has ? 0xB30B0B0F : 0xA6FFFFFF);
            v.animate().scaleX(has ? 1.03f : 1f).scaleY(has ? 1.03f : 1f)
                    .setInterpolator(focusOvershoot).setDuration(180).start();
        });
        r.setOnClickListener(v -> onClick.run());
        return r;
    }

    private void showTileMenu(String pkg) {
        FrameLayout wrap = new FrameLayout(this);
        wrap.setClipChildren(false);
        wrap.setClipToPadding(false);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClipChildren(false);
        card.setClipToPadding(false);
        card.setBackground(sheetBg(dp(24)));
        card.setPadding(dp(26), dp(22), dp(26), dp(22));

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dp(360), -2, Gravity.CENTER);
        wrap.addView(card, clp);

        TextView hTitle = text(18, Color.WHITE, "sans-serif-medium");
        hTitle.setText(label(pkg));
        card.addView(hTitle);

        TextView hSub = text(11.5f, 0x99FFFFFF, "sans-serif");
        hSub.setText("App Options");
        hSub.setPadding(0, dp(2), 0, dp(16));
        card.addView(hSub);

        View moveRow = settingsRow("Rearrange on Home", "Move", null, () -> {
            closeOverlay(false);
            startMove(pkg);
        });
        card.addView(moveRow, rowLp());

        View remRow = settingsRow("Remove from Home", "Hide", null, () -> {
            closeOverlay(false);
            apps.remove(pkg);
            lastFocused = null;
            saveApps();
            rebuildGrid();
        });
        card.addView(remRow, rowLp());

        View cancelRow = settingsRow("Cancel", "", null, () -> closeOverlay(true));
        card.addView(cancelRow, rowLp());

        openOverlay(wrap, moveRow, false);
    }

    private void showAppPicker() {
        List<ResolveInfo> found = new ArrayList<>();
        found.addAll(pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0));
        found.addAll(pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0));
        List<String> pkgs = new ArrayList<>();
        for (ResolveInfo r : found) {
            String p = r.activityInfo.packageName;
            if (!pkgs.contains(p) && !p.equals(getPackageName())) pkgs.add(p);
        }
        Collections.sort(pkgs, (a, b) -> label(a).compareToIgnoreCase(label(b)));

        FrameLayout wrap = new FrameLayout(this);
        wrap.setClipChildren(false);
        wrap.setClipToPadding(false);

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setClipChildren(true);
        sheet.setClipToPadding(true);
        sheet.setBackground(sheetBg(dp(26)));
        sheet.setPadding(dp(28), dp(22), dp(28), dp(22));

        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(dp(520), dp(460), Gravity.CENTER);
        wrap.addView(sheet, slp);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setClipChildren(false);
        sheet.addView(head, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout tCol = new LinearLayout(this);
        tCol.setOrientation(LinearLayout.VERTICAL);
        head.addView(tCol, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView hTitle = text(19, Color.WHITE, "sans-serif-medium");
        hTitle.setText("Apps on Home");
        tCol.addView(hTitle);

        TextView hSub = text(11.5f, 0x99FFFFFF, "sans-serif");
        hSub.setText("Select to toggle visibility · Hold Select on Home to rearrange");
        hSub.setPadding(0, dp(2), 0, 0);
        tCol.addView(hSub);

        View doneBtn = tvosPillButton("Done", () -> closeOverlay(true));
        head.addView(doneBtn, new LinearLayout.LayoutParams(-2, dp(34)));

        ScrollView listScroll = new ScrollView(this);
        listScroll.setVerticalScrollBarEnabled(false);
        listScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        listScroll.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRect(0, 0, view.getWidth(), view.getHeight());
            }
        });
        listScroll.setClipToOutline(true);
        LinearLayout.LayoutParams lslp = new LinearLayout.LayoutParams(-1, 0, 1f);
        lslp.topMargin = dp(14);
        sheet.addView(listScroll, lslp);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        list.setClipToPadding(false);
        list.setPadding(dp(10), dp(6), dp(10), dp(6));
        listScroll.addView(list, new ScrollView.LayoutParams(-1, -2));

        View firstRow = null;
        for (String p : pkgs) {
            final TextView[] stateRef = new TextView[1];
            boolean onInit = apps.contains(p);
            View row = settingsRow(label(p), onInit ? "Shown ✓" : "Hidden", stateRef, () -> {
                if (apps.contains(p)) {
                    apps.remove(p);
                    if (stateRef[0] != null) stateRef[0].setText("Hidden");
                } else {
                    apps.add(p);
                    if (stateRef[0] != null) stateRef[0].setText("Shown ✓");
                }
                saveApps();
                rebuildGrid();
            });
            list.addView(row, rowLp());
            if (firstRow == null) firstRow = row;
        }

        openOverlay(wrap, firstRow != null ? firstRow : doneBtn, false);
    }

    private void updateClock() {
        Calendar now = Calendar.getInstance();
        int h = now.get(Calendar.HOUR_OF_DAY);
        greeting.setText(h < 5 ? "GOOD NIGHT" : h < 12 ? "GOOD MORNING" : h < 18 ? "GOOD AFTERNOON" : "GOOD EVENING");
        boolean is24 = DateFormat.is24HourFormat(this);
        clock.setText(DateFormat.format(is24 ? "HH:mm" : "h:mm", now));
        ampm.setText(is24 ? "" : DateFormat.format("a", now).toString().toUpperCase(Locale.getDefault()));
        date.setText(DateFormat.format("EEEE d MMMM", now).toString().toUpperCase(Locale.getDefault()));
    }

    // ---------------------------------------------------------------- apps + storage

    private void loadApps() {
        apps.clear();
        String saved = prefs.getString("apps", null);
        if (saved == null) apps.addAll(Arrays.asList(DEFAULT_APPS));
        else if (!saved.isEmpty()) apps.addAll(Arrays.asList(saved.split(",")));
    }

    private void saveApps() {
        prefs.edit().putString("apps", TextUtils.join(",", apps)).apply();
    }

    private void refreshLaunchIntents() {
        launchIntents.clear();
        for (String pkg : apps) {
            Intent i = resolveLaunchIntent(pkg);
            if (i != null) launchIntents.put(pkg, i);
        }
    }

    private Intent resolveLaunchIntent(String pkg) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            if (!ai.enabled) return null;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        }
        return i;
    }

    private boolean isLaunchable(String pkg) {
        return launchIntents.containsKey(pkg) || resolveLaunchIntent(pkg) != null;
    }

    private String label(String pkg) {
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    // ---------------------------------------------------------------- helpers

    private static int direction(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT: return View.FOCUS_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return View.FOCUS_RIGHT;
            case KeyEvent.KEYCODE_DPAD_UP: return View.FOCUS_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return View.FOCUS_DOWN;
            default: return -1;
        }
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

    private static ViewGroup.LayoutParams match() {
        return new FrameLayout.LayoutParams(-1, -1);
    }

    /** ScrollView that leaves all scrolling and key focus navigation to us and ViewRootImpl. */
    static class HomeScroll extends ScrollView {
        HomeScroll(Context c) { super(c); }

        @Override
        public boolean executeKeyEvent(KeyEvent event) {
            return false;
        }

        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect rect) {
            return 0;
        }
    }
}
