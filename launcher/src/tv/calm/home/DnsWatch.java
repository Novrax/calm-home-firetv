package tv.calm.home;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.net.InetSocketAddress;
import java.net.Socket;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Keeps AdGuard Private DNS from ever taking the stick offline.
 *
 * Strict Private DNS (DoT) fails whenever the TLS handshake fails: an AdGuard outage, or the
 * clock being wrong. After a power cut the stick has no clock and Android starts it at the
 * firmware build date (ro.build.date), which is older than AdGuard's certificate. Amazon's
 * TimeService syncs from ftv-smp.ntp-fireos.com by hostname, so the clock can't fix itself
 * without DNS: a deadlock (CHANGES.md D168–D170), and Fire OS pops its network screen.
 *
 * So: if the clock is behind the last time we saw it working (a cold boot), switch to
 * opportunistic mode (router DNS) at once, before Wi-Fi even validates. Separately, if the
 * default network stays unvalidated with AdGuard set, fall back the same way. Either way,
 * AdGuard only goes back on after a test TLS handshake with it succeeds from here, so a
 * retry never takes the internet down; failed tests back off. Needs WRITE_SECURE_SETTINGS,
 * granted via adb.
 */
final class DnsWatch {
    static final String TAG = "DnsWatch";
    static final String HOST = "dns.adguard-dns.com";
    static final String MODE = "private_dns_mode", SPEC = "private_dns_specifier";
    // Fire OS opens its network screen ~9 s into an unvalidated connection: fall back before that.
    static final long GRACE = 5_000, FIRST_RETRY = 90_000, MAX_RETRY = 30 * 60_000L;
    static final long CLOCK_POLL = 2_000, CLOCK_WAIT = 5 * 60_000;

    private final Handler handler;
    private final ContentResolver cr;
    private final ConnectivityManager cm;
    private final SharedPreferences prefs;
    private volatile boolean validated;
    private boolean probing;
    private long clockWaitStart;
    /** elapsedRealtime of the last fallback from a failed strict attempt; 0 if none this boot. */
    private long fellBackAt;

    private final Runnable fallback = this::fallBack;
    private final Runnable restore = this::restore;
    private final Runnable clockPoll = this::clockPoll;

    private final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
        @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities caps) {
            validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            evaluate();
        }
        @Override public void onLost(Network n) {
            validated = false;
            handler.removeCallbacks(fallback);
            handler.removeCallbacks(restore);
        }
    };

    DnsWatch(Context ctx, Handler handler) {
        this.handler = handler;
        cr = ctx.getContentResolver();
        cm = ctx.getSystemService(ConnectivityManager.class);
        prefs = ctx.getSharedPreferences("dnswatch", Context.MODE_PRIVATE);
    }

    void start() {
        if (adguardSet() && clockBehind()) {
            // Cold boot: strict DoT can't work until the clock syncs, so don't even try.
            if (put(MODE, "opportunistic")) {
                Log.i(TAG, "clock not set yet, using router DNS until it syncs");
                prefs.edit().putBoolean("fellBack", true).putLong("retry", FIRST_RETRY).apply();
            }
        }
        if (prefs.getBoolean("fellBack", false)) waitForClock();
        try {
            cm.registerDefaultNetworkCallback(callback, handler);
        } catch (Exception e) {
            Log.w(TAG, "can't watch network", e);
        }
    }

    void stop() {
        try { cm.unregisterNetworkCallback(callback); } catch (Exception ignored) {}
        handler.removeCallbacks(fallback);
        handler.removeCallbacks(restore);
        handler.removeCallbacks(clockPoll);
    }

    /** True while the default network has internet (Android's VALIDATED check passed). */
    boolean online() {
        return validated;
    }

    private boolean adguardSet() {
        return "hostname".equals(Settings.Global.getString(cr, MODE))
                && HOST.equals(Settings.Global.getString(cr, SPEC));
    }

    /** Our own fallback is in place: opportunistic mode with AdGuard still named as the host. */
    private boolean fallbackSet() {
        return "opportunistic".equals(Settings.Global.getString(cr, MODE))
                && HOST.equals(Settings.Global.getString(cr, SPEC));
    }

    /** The clock is earlier than a time already seen working, or still at the firmware build date. */
    private boolean clockBehind() {
        long now = System.currentTimeMillis();
        return now < prefs.getLong("lastGood", 0)
                || now - Build.TIME < SystemClock.elapsedRealtime() + 3600_000L;
    }

    /** If the clock is what broke AdGuard, go back to it as soon as the clock syncs. */
    private void waitForClock() {
        if (!clockBehind() || handler.hasCallbacks(clockPoll)) return;
        clockWaitStart = SystemClock.elapsedRealtime();
        handler.removeCallbacks(restore);
        handler.postDelayed(clockPoll, CLOCK_POLL);
    }

    private void clockPoll() {
        if (SystemClock.elapsedRealtime() - clockWaitStart > CLOCK_WAIT) {
            // Clock sync is stuck for some other reason: hand over to the timed retry/backoff.
            Log.i(TAG, "clock still not synced, retrying AdGuard on the normal schedule");
            evaluate();
        } else if (clockBehind() || !validated) {
            handler.postDelayed(clockPoll, CLOCK_POLL);
        } else {
            // After a strict-mode failure netd holds off revalidating AdGuard for a while, and
            // switching back inside that window fails again. A cold boot never tried, so no wait.
            long wait = fellBackAt == 0 ? 0 : fellBackAt + FIRST_RETRY - SystemClock.elapsedRealtime();
            Log.i(TAG, "clock synced; AdGuard back in " + Math.max(wait, 0) / 1000 + "s");
            handler.removeCallbacks(restore);
            handler.postDelayed(restore, Math.max(wait, 0));
        }
    }

    private void evaluate() {
        boolean fellBack = prefs.getBoolean("fellBack", false);
        // Strict DoT validating proves the clock is right, even if a bad lastGood says otherwise.
        if (validated && (adguardSet() || !clockBehind())) {
            prefs.edit().putLong("lastGood", System.currentTimeMillis()).apply();
        }
        if (adguardSet()) {
            if (validated) {
                handler.removeCallbacks(fallback);
                if (fellBack) {
                    Log.i(TAG, "AdGuard validated again");
                    prefs.edit().putBoolean("fellBack", false).putLong("retry", FIRST_RETRY).apply();
                }
            } else if (!handler.hasCallbacks(fallback)) {
                handler.postDelayed(fallback, GRACE);
            }
        } else if (fellBack && validated && !probing && !handler.hasCallbacks(restore)
                && !handler.hasCallbacks(clockPoll)) {
            handler.postDelayed(restore, prefs.getLong("retry", FIRST_RETRY));
        }
    }

    private void fallBack() {
        if (validated || !adguardSet()) return;
        boolean retrying = prefs.getBoolean("fellBack", false);
        long retry = retrying ? nextRetry() : FIRST_RETRY;
        if (put(MODE, "opportunistic")) {
            Log.i(TAG, "AdGuard DNS unreachable, using router DNS; retry in " + retry / 1000 + "s");
            prefs.edit().putBoolean("fellBack", true).putLong("retry", retry).apply();
            fellBackAt = SystemClock.elapsedRealtime();
            waitForClock();
        }
    }

    private long nextRetry() {
        return Math.min(prefs.getLong("retry", FIRST_RETRY) * 2, MAX_RETRY);
    }

    private void restore() {
        // Only undo our own fallback: if someone changed DNS since, leave their choice alone.
        if (!fallbackSet()) {
            prefs.edit().putBoolean("fellBack", false).apply();
            return;
        }
        if (probing) return;
        probing = true;
        new Thread(() -> {
            boolean ok = adguardReachable();
            handler.post(() -> afterProbe(ok));
        }, "dnswatch-probe").start();
    }

    private void afterProbe(boolean ok) {
        probing = false;
        if (!fallbackSet()) {
            prefs.edit().putBoolean("fellBack", false).apply();
        } else if (ok) {
            Log.i(TAG, "AdGuard reachable, switching back to it");
            // Validation may stay on without a callback if it succeeds, so re-check once it has had time.
            if (put(MODE, "hostname")) handler.postDelayed(this::evaluate, GRACE + 5_000);
        } else {
            long retry = nextRetry();
            Log.i(TAG, "AdGuard still unreachable, staying on router DNS; retry in " + retry / 1000 + "s");
            prefs.edit().putLong("retry", retry).apply();
            handler.removeCallbacks(restore);
            handler.postDelayed(restore, retry);
        }
    }

    /** The same check strict mode makes: a verified TLS handshake with AdGuard's DoT port. */
    private static boolean adguardReachable() {
        try (Socket raw = new Socket()) {
            raw.connect(new InetSocketAddress(HOST, 853), 5_000);
            raw.setSoTimeout(5_000);
            // Layering on a connected socket keeps the hostname for SNI and verification.
            try (SSLSocket s = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(raw, HOST, 853, false)) {
                s.startHandshake();
                return HttpsURLConnection.getDefaultHostnameVerifier().verify(HOST, s.getSession());
            }
        } catch (Exception e) {
            Log.i(TAG, "AdGuard probe failed: " + e);
            return false;
        }
    }

    private boolean put(String key, String value) {
        try {
            return Settings.Global.putString(cr, key, value);
        } catch (SecurityException e) {
            Log.w(TAG, "WRITE_SECURE_SETTINGS not granted");
            return false;
        }
    }
}
