package tv.calm.home;

import android.accessibilityservice.AccessibilityService;
import android.content.ContentResolver;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Fire OS marks com.amazon.tv.launcher as a protected package: it cannot be disabled,
 * hidden, suspended, force-stopped or replaced as the HOME activity, and the Home key is
 * never delivered to accessibility services.
 *
 * To ensure the original Amazon homepage is NEVER seen in general use, HomeGuard uses a
 * two-layer defense:
 * 1. Preemptive logcat hook (with READ_LOGS / gid 1007): detects FireTVKeyPolicyManager's
 *    sendHomePressedIntent (~50ms BEFORE ActivityTaskManager even starts Amazon's
 *    HomeActivity_vNext) as well as ActivityTaskManager / wm_resume_activity events,
 *    firing our HomeActivity immediately so Amazon's launcher is paused in ~12ms before it
 *    can ever reach onStart() or draw a single frame.
 * 2. Accessibility window-state & active-window verification fallback: catches any window
 *    state change to Amazon's home screen or the remote's Apps Grid trampoline, with
 *    trailing verification so no rapid/double press is ever dropped.
 */
public class HomeGuard extends AccessibilityService {
    static final String TAG = "HomeGuard";
    static final String AMAZON_LAUNCHER = "com.amazon.tv.launcher";
    static final String AMAZON_HOME = AMAZON_LAUNCHER + ".ui.HomeActivity";
    static final String AMAZON_VENEZIA = "com.amazon.venezia";
    static final String VENEZIA_APPS_GRID = "com.amazon.venezia.grid.AppsGridLauncherActivity";
    static final String AMAZON_SETTINGS = "com.amazon.tv.settings.v2";
    static final String FIRETV_IME = "com.amazon.tv.ime/.FireTVIME";
    /** How long after Fire OS opens its network screen we'll still close it once online. */
    static final long AUTO_NETWORK_WINDOW = 5 * 60_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastFireUptime;
    private long lastHomeKeyPulseUptime;
    private boolean pendingTrailingHomeKey;
    private volatile boolean running;
    private Process logProc;
    private DnsWatch dnsWatch;
    private long autoNetworkUptime = -AUTO_NETWORK_WINDOW;
    private int autoNetworkBacks;
    /** Last activity Amazon's launcher package showed: its home, or e.g. ui.MainSettingsActivity. */
    private String amazonScreen;
    private boolean imeLocalesReady;
    private int imeRefreshAttempts;
    private long lastImeRefreshUptime;

    private final Runnable trailingFireRunnable = () -> {
        boolean fromKey = pendingTrailingHomeKey;
        pendingTrailingHomeKey = false;
        fireHomeIntent(fromKey, "trailing");
    };

    private final Runnable pulse35msRunnable = () -> {
        if (!HomeActivity.isFront) fireHomeIntent(false, "key_35ms");
    };
    private final Runnable pulse75msRunnable = () -> {
        if (!HomeActivity.isFront) fireHomeIntent(false, "key_75ms");
    };
    private final Runnable checkFrontRunnable = this::checkFront;
    private final Runnable closeNetworkRunnable = this::closeAutoNetworkScreen;
    private final Runnable refreshImeRunnable = this::refreshImeLocales;

    @Override
    protected void onServiceConnected() {
        Log.i(TAG, "connected");
        running = true;
        imeLocalesReady = false;
        imeRefreshAttempts = 0;
        ensureImeRestored();
        startLogWatch();
        if (dnsWatch == null) {
            dnsWatch = new DnsWatch(this, handler);
            dnsWatch.start();
        }
        handler.postDelayed(checkFrontRunnable, 200);
        handler.postDelayed(checkFrontRunnable, 1200);
        handler.postDelayed(checkFrontRunnable, 2500);
        // At boot, com.amazon.tv.ime queries com.amazon.vizzini.locale before VizziniApplication
        // finishes initializing, caching a buggy 7-locale default list that omits en_GB/en_US
        // and disables voice-to-text in app text boxes. Rebind FireTVIME once Vizzini is up.
        scheduleImeRefresh(6000);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        running = false;
        handler.removeCallbacks(refreshImeRunnable);
        ensureImeRestored();
        stopLogWatch();
        stopDnsWatch();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacks(refreshImeRunnable);
        ensureImeRestored();
        stopLogWatch();
        stopDnsWatch();
        super.onDestroy();
    }

    private void stopDnsWatch() {
        if (dnsWatch != null) {
            dnsWatch.stop();
            dnsWatch = null;
        }
    }

    private void stopLogWatch() {
        Process p = logProc;
        logProc = null;
        if (p != null) {
            try { p.destroy(); } catch (Exception ignored) {}
        }
    }

    private void scheduleImeRefresh(long delayMs) {
        if (!running || imeLocalesReady || imeRefreshAttempts >= 6) return;
        long elapsed = SystemClock.uptimeMillis() - lastImeRefreshUptime;
        long wait = Math.max(delayMs, elapsed < 4000 ? 4000 - elapsed : 0);
        handler.removeCallbacks(refreshImeRunnable);
        handler.postDelayed(refreshImeRunnable, wait);
    }

    private void refreshImeLocales() {
        if (!running || imeLocalesReady || imeRefreshAttempts >= 6) return;
        imeRefreshAttempts++;
        lastImeRefreshUptime = SystemClock.uptimeMillis();
        Log.i(TAG, "refreshing FireTVIME locale cache (attempt " + imeRefreshAttempts + ")");
        try {
            ContentResolver cr = getContentResolver();
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_INPUT_METHODS, "");
            Settings.Secure.putString(cr, Settings.Secure.DEFAULT_INPUT_METHOD, "");
        } catch (Exception e) {
            Log.w(TAG, "IME unbind failed", e);
            return;
        }
        handler.postDelayed(() -> {
            ensureImeRestored();
            handler.postDelayed(this::pulseImeWindowFocus, 300);
        }, 450);
    }

    private void ensureImeRestored() {
        try {
            ContentResolver cr = getContentResolver();
            String def = Settings.Secure.getString(cr, Settings.Secure.DEFAULT_INPUT_METHOD);
            if (def == null || def.isEmpty()) {
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_INPUT_METHODS, FIRETV_IME);
                Settings.Secure.putString(cr, Settings.Secure.DEFAULT_INPUT_METHOD, FIRETV_IME);
            }
        } catch (Exception e) {
            Log.w(TAG, "IME restore failed", e);
        }
    }

    private void pulseImeWindowFocus() {
        if (!running) return;
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (wm == null) return;
            View v = new View(this);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    1, 1,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSPARENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            wm.addView(v, lp);
            handler.postDelayed(() -> {
                try { wm.removeView(v); } catch (Exception ignored) {}
            }, 80);
        } catch (Exception e) {
            Log.w(TAG, "IME focus pulse failed", e);
        }
    }

    /**
     * Watches system, events & filtered main logcat buffers for:
     * - FireTVKeyPolicyManager.sendHomePressedIntent (~50ms before HomeActivity_vNext starts)
     * - ActivityTaskManager / wm_set_resumed_activity starting HomeActivity_vNext
     * - Remote "Apps" button deep link (amzn://apps/android?a_lib / AppsGridLauncherActivity)
     * - FireTVIME / AlexaSupportedLocaleMan locale cache initialization
     */
    private void startLogWatch() {
        stopLogWatch();
        Thread t = new Thread(() -> {
            while (running) {
                Process p = null;
                try {
                    p = new ProcessBuilder(
                            "logcat", "-b", "system", "-b", "events", "-b", "main", "-v", "raw", "-T", "1",
                            "ActivityTaskManager:I", "ContextImpl:W",
                            "wm_set_resumed_activity:I", "wm_resume_activity:I",
                            "AlexaSupportedLocaleMan:I", "IME-FireTVIME:I", "*:S"
                    ).redirectErrorStream(true).start();
                    logProc = p;
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()), 4096)) {
                        String line;
                        while (running && (line = br.readLine()) != null) {
                            if (line.contains("sendHomePressedIntent")) {
                                amazonScreen = AMAZON_HOME;
                                handler.post(this::preemptHomeKey);
                            } else if (line.contains("com.amazon.tv.launcher/.ui.HomeActivity")
                                    || line.contains("com.amazon.tv.launcher.ui.HomeActivity")) {
                                if (!line.contains("pause") && !line.contains("stop") && !line.contains("destroy")) {
                                    amazonScreen = AMAZON_HOME;
                                    handler.post(() -> {
                                        boolean firstKey = (SystemClock.uptimeMillis() - lastHomeKeyPulseUptime) > 450;
                                        if (firstKey) lastHomeKeyPulseUptime = SystemClock.uptimeMillis();
                                        goHome("log_home", firstKey);
                                    });
                                }
                            } else if (line.contains("amzn://apps/android?a_lib")
                                    || line.contains("AppsGridLauncherActivity")
                                    || line.contains("NavMenuScreenDeepLinkActivityDI")
                                    || line.contains("ChannelLandingPageDeepLinkActivityDI")
                                    || line.contains("RecentDeepLinkActivityDI")) {
                                amazonScreen = AMAZON_HOME;
                                handler.post(() -> goHome("log_intercept", false));
                            } else if (line.contains("act=android.settings.WIRELESS_SETTINGS")
                                    && line.contains("from uid 1000")) {
                                // Fire OS opening its network screen by itself, not someone pressing a button.
                                handler.post(this::onAutoNetworkScreen);
                            } else if (line.contains("tv.calm.home/.HomeActivity")) {
                                handler.removeCallbacks(pulse35msRunnable);
                                handler.removeCallbacks(pulse75msRunnable);
                            } else if (line.contains("Initial value: AlexaSupportedLocaleInfo(")) {
                                if (line.contains("en_GB")) {
                                    Log.i(TAG, "FireTVIME loaded full Alexa locale list");
                                    handler.post(() -> {
                                        imeLocalesReady = true;
                                        handler.removeCallbacks(refreshImeRunnable);
                                    });
                                } else {
                                    Log.w(TAG, "FireTVIME got incomplete default locale list; scheduling refresh");
                                    handler.post(() -> {
                                        imeLocalesReady = false;
                                        scheduleImeRefresh(5000);
                                    });
                                }
                            } else if (line.contains("Voice input is disabled since user's locale")) {
                                Log.w(TAG, "FireTVIME voice input disabled by stale locale cache; refreshing");
                                handler.post(() -> {
                                    imeLocalesReady = false;
                                    scheduleImeRefresh(500);
                                });
                            }
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    if (p != null) {
                        try { p.destroy(); } catch (Exception ignored) {}
                    }
                }
                if (running) {
                    SystemClock.sleep(1500);
                }
            }
        }, "home-guard-log");
        t.setPriority(Thread.MAX_PRIORITY);
        t.setDaemon(true);
        t.start();
    }

    /**
     * Fire OS opens its network settings by itself when a connection check fails, e.g. during
     * the few seconds after a cold boot before DnsWatch has the clock synced. Nobody in the
     * house needs that screen, so once the internet is working again, close it.
     */
    private void onAutoNetworkScreen() {
        Log.i(TAG, "Fire OS opened the network screen by itself");
        autoNetworkUptime = SystemClock.uptimeMillis();
        autoNetworkBacks = 0;
        handler.removeCallbacks(closeNetworkRunnable);
        handler.postDelayed(closeNetworkRunnable, 1000);
    }

    private void closeAutoNetworkScreen() {
        if (SystemClock.uptimeMillis() - autoNetworkUptime > AUTO_NETWORK_WINDOW) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        CharSequence pkg = root != null ? root.getPackageName() : null;
        if (root != null) root.recycle();
        boolean showing = pkg != null && AMAZON_SETTINGS.contentEquals(pkg);
        if (autoNetworkBacks > 0 && !showing) {
            Log.i(TAG, "closed the network screen, internet is back");
            return;
        }
        if (showing && dnsWatch != null && dnsWatch.online()) {
            if (autoNetworkBacks < 2) {
                // Back returns to whatever was on screen before, e.g. the app they were in.
                performGlobalAction(GLOBAL_ACTION_BACK);
            } else {
                goHome("auto_network", false);
            }
            autoNetworkBacks++;
        }
        handler.postDelayed(closeNetworkRunnable, 1000);
    }

    private void preemptHomeKey() {
        long now = SystemClock.uptimeMillis();
        boolean firstKey = (now - lastHomeKeyPulseUptime) > 450;
        lastHomeKeyPulseUptime = now;
        fireHomeIntent(firstKey, "key_0ms");
        handler.removeCallbacks(pulse35msRunnable);
        handler.removeCallbacks(pulse75msRunnable);
        handler.postDelayed(pulse35msRunnable, 35);
        handler.postDelayed(pulse75msRunnable, 75);
        handler.removeCallbacks(checkFrontRunnable);
        handler.postDelayed(checkFrontRunnable, 180);
    }

    private void checkFront() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        CharSequence pkg = root.getPackageName();
        root.recycle();
        if (pkg != null && AMAZON_LAUNCHER.contentEquals(pkg)
                && (amazonScreen == null || isBlockedAmazonScreen(amazonScreen))) {
            goHome("checkFront", false);
        }
    }

    private static boolean isBlockedAmazonScreen(String cls) {
        if (cls == null) return false;
        return cls.startsWith(AMAZON_HOME)
                || cls.endsWith(".NavMenuScreenDeepLinkActivityDI")
                || cls.endsWith(".ChannelLandingPageDeepLinkActivityDI")
                || cls.endsWith(".RecentDeepLinkActivityDI")
                || cls.contains(".primefreetrial.");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = e.getPackageName();
        if (pkg == null) return;
        CharSequence clsSeq = e.getClassName();
        String cls = clsSeq != null ? clsSeq.toString() : null;

        if (AMAZON_LAUNCHER.contentEquals(pkg)) {
            // Track real activity names: Settings, Alexa search etc. also live in this package.
            if (cls != null && cls.startsWith(AMAZON_LAUNCHER + ".")) {
                amazonScreen = cls;
            }
            if (isBlockedAmazonScreen(cls)) {
                if (HomeActivity.isFront && (SystemClock.uptimeMillis() - lastFireUptime) < 450) {
                    // Already preempted and Calm Home is already in front; just schedule a safety check.
                    handler.removeCallbacks(checkFrontRunnable);
                    handler.postDelayed(checkFrontRunnable, 120);
                    return;
                }
                boolean firstKey = (SystemClock.uptimeMillis() - lastHomeKeyPulseUptime) > 450;
                if (firstKey) lastHomeKeyPulseUptime = SystemClock.uptimeMillis();
                goHome("window", firstKey);
            }
        } else if (AMAZON_VENEZIA.contentEquals(pkg)) {
            if (VENEZIA_APPS_GRID.equals(cls)) {
                goHome("venezia_grid", false);
            }
        }
    }

    private void goHome(String why, boolean fromHomeKey) {
        fireHomeIntent(fromHomeKey, why);
        handler.removeCallbacks(checkFrontRunnable);
        handler.postDelayed(checkFrontRunnable, 140);
    }

    private void fireHomeIntent(boolean fromHomeKey, String why) {
        long now = SystemClock.uptimeMillis();
        long elapsed = now - lastFireUptime;
        if (elapsed < 25) {
            pendingTrailingHomeKey |= fromHomeKey;
            handler.removeCallbacks(trailingFireRunnable);
            handler.postDelayed(trailingFireRunnable, 25 - elapsed);
            return;
        }
        lastFireUptime = now;
        handler.removeCallbacks(trailingFireRunnable);
        Intent i = new Intent(this, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        if (fromHomeKey) {
            i.putExtra(HomeActivity.EXTRA_FROM_HOME_KEY, true);
        }
        try {
            startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "redirect failed (" + why + ")", e);
        }
    }

    @Override
    public void onInterrupt() {
    }
}
