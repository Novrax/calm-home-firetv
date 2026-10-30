package tv.calm.home;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Current conditions from Open-Meteo (free, no account or API key). Only the coordinates
 * below are sent. The last reading is cached so the widget shows instantly after a reboot.
 */
final class Weather {
    static final double LAT = 51.51, LON = -0.13;   // London. Set to your own town during setup.
    private static final long REFRESH_MS = 30 * 60 * 1000L;
    private static final String URL_FMT =
            "https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s"
                    + "&current=temperature_2m,weather_code,is_day&timezone=auto";

    interface Listener { void onWeather(int tempC, int wmo, boolean day); }

    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean inFlight;

    Weather(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    /** Calls back immediately with the cached reading (if any), then again if a refresh lands. */
    void get(Listener l) {
        if (prefs.contains("w_time")) {
            l.onWeather(prefs.getInt("w_temp", 0), prefs.getInt("w_code", 3), prefs.getBoolean("w_day", true));
        }
        if (inFlight || System.currentTimeMillis() - prefs.getLong("w_time", 0) < REFRESH_MS) return;
        inFlight = true;
        new Thread(() -> {
            try {
                JSONObject cur = new JSONObject(fetch(String.format(java.util.Locale.ROOT, URL_FMT, LAT, LON)))
                        .getJSONObject("current");
                int temp = (int) Math.round(cur.getDouble("temperature_2m"));
                int code = cur.getInt("weather_code");
                boolean day = cur.optInt("is_day", 1) == 1;
                prefs.edit().putInt("w_temp", temp).putInt("w_code", code).putBoolean("w_day", day)
                        .putLong("w_time", System.currentTimeMillis()).apply();
                main.post(() -> l.onWeather(temp, code, day));
            } catch (Exception ignored) {
                // Offline or blocked: keep showing the cached reading, try again next resume.
            } finally {
                main.post(() -> inFlight = false);
            }
        }, "weather").start();
    }

    private static String fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(6000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
