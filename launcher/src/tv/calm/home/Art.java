package tv.calm.home;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * One tile-sized picture per app, made once and cached on disk (keyed by the app's update
 * time), so full-size banners are never kept in memory or on the GPU.
 *
 * Banners are not drawn as-is: their logos range from tiny (iPlayer, Channel 4) to edge-to-edge
 * (Netflix, TED). Where a banner is a logo on a plain or smoothly shaded background, the logo
 * is found and rescaled so every app's logo covers about the same share of its tile.
 */
final class Art {
    private static final int VERSION = 4;

    // Background model / logo detection (colour distance = sum of |dA|+|dR|+|dG|+|dB|).
    private static final int BG_TOL = 32, CONTENT_TOL = 60, CLEAR_ALPHA = 24;
    private static final float UNIFORM_SHARE = 0.88f, FULL_SHARE = 0.90f;
    // Logo size on the tile.
    private static final float LOGO_AREA = 0.19f, MAX_W = 0.72f, MAX_H = 0.52f, MAX_UPSCALE = 2.5f;

    private final Context ctx;
    private final PackageManager pm;
    private final int w, h;
    private final float radius;
    private final File dir;
    private final Map<String, Bitmap> mem = new HashMap<>();
    private final Map<String, int[]> palettes = new HashMap<>();

    Art(Context ctx, int w, int h, float radius) {
        this.ctx = ctx;
        this.pm = ctx.getPackageManager();
        this.w = w;
        this.h = h;
        this.radius = radius;
        File base = ctx.getExternalCacheDir();
        dir = new File(base != null ? base : ctx.getCacheDir(), "art");
        dir.mkdirs();
    }

    int width() { return w; }
    int height() { return h; }

    /** The app's tile picture (rounded corners baked in). Null only if the app is gone. */
    Bitmap get(String pkg) {
        String key = key(pkg);
        if (key == null) return null;
        Bitmap b = mem.get(key);
        if (b != null) return b;
        File f = new File(dir, key + ".png");
        if (f.exists()) b = BitmapFactory.decodeFile(f.getPath());
        if (b == null) {
            b = make(pkg);
            save(pkg, f, b);
        }
        mem.put(key, b);
        return b;
    }

    /** Two ambient colours for the app, taken from its tile. */
    int[] palette(String pkg) {
        String key = key(pkg);
        int[] p = key == null ? null : palettes.get(key);
        if (p != null) return p;
        Bitmap b = get(pkg);
        p = b == null ? new int[]{0xFF34364A, 0xFF1A1C2C} : palette(b);
        if (key != null) palettes.put(key, p);
        return p;
    }

    private String key(String pkg) {
        try {
            long t = pm.getPackageInfo(pkg, 0).lastUpdateTime;
            return pkg + "-" + Long.toHexString(t) + "-" + w + "x" + h + "-v" + VERSION;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private void save(String pkg, File f, Bitmap b) {
        File[] old = dir.listFiles((d, n) -> n.startsWith(pkg + "-"));
        if (old != null) for (File o : old) o.delete();
        try (FileOutputStream out = new FileOutputStream(f)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- making a tile

    private Bitmap make(String pkg) {
        Bitmap tile = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(tile);
        int customRes = customWhiteLogoRes(pkg);
        if (customRes != 0) {
            c.drawColor(Color.WHITE);
            Bitmap src = BitmapFactory.decodeResource(ctx.getResources(), customRes);
            if (src != null) {
                Analysis a = analyse(src, 0);
                if (a.hasContent()) place(c, src, a, null);
                else cover(c, src);
                src.recycle();
            }
            return round(tile);
        }
        Drawable banner = banner(pkg);
        if (banner != null) {
            Bitmap src = render(banner, true);
            Analysis a = analyse(src, 0);
            if (a.transparent && a.hasContent() && !a.full()) {
                card(c, palette(src));
                place(c, src, a, null);
            } else if (a.uniform && a.hasContent() && !a.full()) {
                gradient(c, a);
                place(c, src, a, null);
            } else {
                cover(c, src);                              // photo / busy art: fill the tile
            }
            src.recycle();
        } else {
            Bitmap src = render(icon(pkg), false);
            Analysis a = analyse(src, 0);
            if ((a.transparent || a.uniform) && a.full()) {
                // A filled (often rounded) square: look inside it for the logo, e.g. Audible's
                // wordmark on white, and make the tile that colour.
                Analysis in = analyse(src, Math.round(src.getWidth() * 0.14f));
                if (in.uniform && !in.transparent && in.hasContent() && !in.full()) a = in;
            }
            if (a.inset > 0) {
                gradient(c, a);
                place(c, src, a, a);
            } else if (a.uniform && !a.transparent && a.hasContent() && !a.full()) {
                gradient(c, a);
                place(c, src, a, null);
            } else {
                // Logo with its own shape: show it on a card tinted from its colours.
                int[] pal = palette(src);
                card(c, pal);
                if (a.transparent && a.hasContent() && !a.full()) {
                    place(c, src, a, null);
                } else {
                    int s = Math.round(h * 0.64f);
                    RectF r = new RectF((w - s) / 2f, (h - s) / 2f, (w + s) / 2f, (h + s) / 2f);
                    roundedImage(c, src, r, s * 0.16f);
                }
            }
            src.recycle();
        }
        return round(tile);
    }

    /** Draws the drawable into a bitmap: banners at their own size (capped), icons at 256². */
    private static Bitmap render(Drawable d, boolean banner) {
        int bw, bh;
        if (banner) {
            bw = d.getIntrinsicWidth();
            bh = d.getIntrinsicHeight();
            if (bw <= 0 || bh <= 0) { bw = 640; bh = 360; }
            if (bw > 960) { bh = Math.max(1, bh * 960 / bw); bw = 960; }
        } else {
            bw = bh = 256;
        }
        Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, bw, bh);
        d.draw(new Canvas(b));
        return b;
    }

    /** What the image looks like: background model from its four corners, and the logo box. */
    private static final class Analysis {
        int inset, rw, rh;                 // analysed region (inset from each edge) and its size
        int c00, c10, c01, c11;            // corner colours: top-left, top-right, bottom-left, bottom-right
        boolean transparent, uniform;
        int left, top, right, bottom = -1; // logo box in source pixels (inclusive)

        boolean hasContent() { return bottom >= top && right >= left; }
        boolean full() {
            return (right - left + 1) >= FULL_SHARE * rw && (bottom - top + 1) >= FULL_SHARE * rh;
        }
        int model(int x, int y) {
            float tx = rw <= 1 ? 0f : (float) x / (rw - 1);
            float ty = rh <= 1 ? 0f : (float) y / (rh - 1);
            return lerp(lerp(c00, c10, tx), lerp(c01, c11, tx), ty);
        }
    }

    private static Analysis analyse(Bitmap src, int inset) {
        Analysis a = new Analysis();
        int sw = src.getWidth(), sh = src.getHeight();
        a.inset = inset;
        int rw = a.rw = sw - 2 * inset, rh = a.rh = sh - 2 * inset;
        int[] px = new int[rw * rh];
        src.getPixels(px, 0, rw, inset, inset, rw, rh);

        int patch = Math.max(2, Math.min(rw, rh) / 20);
        a.c00 = patchAvg(px, rw, 1, 1, patch);
        a.c10 = patchAvg(px, rw, rw - 1 - patch, 1, patch);
        a.c01 = patchAvg(px, rw, 1, rh - 1 - patch, patch);
        a.c11 = patchAvg(px, rw, rw - 1 - patch, rh - 1 - patch, patch);
        int minA = Math.min(Math.min(Color.alpha(a.c00), Color.alpha(a.c10)),
                Math.min(Color.alpha(a.c01), Color.alpha(a.c11)));
        a.transparent = minA < CLEAR_ALPHA;

        // Is the border ring close to the model? (Plain or smoothly shaded background.)
        int ring = Math.max(2, Math.min(rw, rh) / 40), near = 0, total = 0;
        for (int y = 1; y < rh - 1; y++) {
            boolean edgeRow = y <= ring || y >= rh - 1 - ring;
            for (int x = 1; x < rw - 1; x++) {
                if (!edgeRow && x > ring && x < rw - 1 - ring) { x = rw - 2 - ring; continue; }
                total++;
                if (dist(px[y * rw + x], a.model(x, y)) <= BG_TOL) near++;
            }
        }
        // Also check a frame 20% inside: radial/vignette backgrounds (e.g. Prime Video) match
        // at the border but are brighter inside, which would leave a visible box if scaled down.
        int fx = Math.max(2, rw / 5), fy = Math.max(2, rh / 5), fNear = 0, fTotal = 0;
        for (int x = fx; x < rw - fx; x += 2) {
            fTotal += 2;
            if (dist(px[fy * rw + x], a.model(x, fy)) <= 18) fNear++;
            if (dist(px[(rh - 1 - fy) * rw + x], a.model(x, rh - 1 - fy)) <= 18) fNear++;
        }
        a.uniform = !a.transparent && total > 0 && near >= UNIFORM_SHARE * total
                && (fTotal == 0 || fNear >= 0.85f * fTotal);

        // Logo box: rows/columns with at least 2 pixels that differ from the background.
        int[] rows = new int[rh], cols = new int[rw];
        for (int y = 1; y < rh - 1; y++) {
            for (int x = 1; x < rw - 1; x++) {
                int p = px[y * rw + x];
                boolean on = a.transparent ? Color.alpha(p) > 40 : dist(p, a.model(x, y)) > CONTENT_TOL;
                if (on) { rows[y]++; cols[x]++; }
            }
        }
        a.top = first(rows); a.bottom = last(rows);
        a.left = first(cols); a.right = last(cols);
        if (a.top < 0 || a.left < 0) { a.top = 0; a.bottom = -1; a.left = 0; a.right = -1; }
        return a;
    }

    private static int first(int[] counts) {
        for (int i = 0; i < counts.length; i++) if (counts[i] >= 2) return i;
        return -1;
    }

    private static int last(int[] counts) {
        for (int i = counts.length - 1; i >= 0; i--) if (counts[i] >= 2) return i;
        return -1;
    }

    private static int patchAvg(int[] px, int stride, int x0, int y0, int n) {
        long a = 0, r = 0, g = 0, b = 0;
        int count = 0;
        for (int y = y0; y < y0 + n; y++) {
            for (int x = x0; x < x0 + n; x++) {
                int p = px[y * stride + x];
                a += Color.alpha(p); r += Color.red(p); g += Color.green(p); b += Color.blue(p);
                count++;
            }
        }
        return Color.argb((int) (a / count), (int) (r / count), (int) (g / count), (int) (b / count));
    }

    private static int dist(int p, int q) {
        return Math.abs(Color.alpha(p) - Color.alpha(q)) + Math.abs(Color.red(p) - Color.red(q))
                + Math.abs(Color.green(p) - Color.green(q)) + Math.abs(Color.blue(p) - Color.blue(q));
    }

    // Bilinear blend of the four corner colours: the background we expect at (x, y).
    private static int lerp(int p, int q, float t) {
        return Color.argb(
                Math.round(Color.alpha(p) + (Color.alpha(q) - Color.alpha(p)) * t),
                Math.round(Color.red(p) + (Color.red(q) - Color.red(p)) * t),
                Math.round(Color.green(p) + (Color.green(q) - Color.green(p)) * t),
                Math.round(Color.blue(p) + (Color.blue(q) - Color.blue(p)) * t));
    }

    // ---------------------------------------------------------------- drawing

    /** Background: the corner model stretched across the tile (a 2×2 bitmap, filtered). */
    private void gradient(Canvas c, Analysis a) {
        Bitmap corners = Bitmap.createBitmap(new int[]{a.c00, a.c10, a.c01, a.c11}, 2, 2, Bitmap.Config.ARGB_8888);
        BitmapShader s = new BitmapShader(corners, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setTranslate(-0.5f, -0.5f);    // pixel centres land exactly on the tile corners
        m.postScale(w, h);
        s.setLocalMatrix(m);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        p.setShader(s);
        c.drawRect(0, 0, w, h, p);
        corners.recycle();
    }

    private void card(Canvas c, int[] pal) {
        Paint p = new Paint(Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(0, 0, w, h, pal[0], darken(pal[1], 0.6f), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
    }

    /**
     * Scales the image so its logo covers LOGO_AREA of the tile (within limits) and centres
     * the logo. The whole image is drawn, not a crop, so shaded backgrounds have no seams;
     * {@code clip} limits it to the analysed region when that was an inner part of an icon.
     */
    private void place(Canvas c, Bitmap src, Analysis a, Analysis clip) {
        float cw = a.right - a.left + 1, ch = a.bottom - a.top + 1;
        float k = (float) Math.sqrt(LOGO_AREA * w * h / (cw * ch));
        k = Math.min(k, Math.min(MAX_W * w / cw, MAX_H * h / ch));
        k = Math.min(k, MAX_UPSCALE);
        float cx = a.inset + (a.left + a.right + 1) / 2f, cy = a.inset + (a.top + a.bottom + 1) / 2f;
        Matrix m = new Matrix();
        m.setTranslate(-cx, -cy);
        m.postScale(k, k);
        m.postTranslate(w / 2f, h / 2f);
        c.save();
        if (clip != null) {
            RectF r = new RectF(clip.inset, clip.inset, clip.inset + clip.rw, clip.inset + clip.rh);
            m.mapRect(r);
            c.clipRect(r);
        }
        drawScaled(c, src, m, k);
        c.restore();
    }

    /** Fills the tile, cropping the middle (the old behaviour, for photos and busy art). */
    private void cover(Canvas c, Bitmap src) {
        float k = Math.max((float) w / src.getWidth(), (float) h / src.getHeight());
        Matrix m = new Matrix();
        m.setTranslate(-src.getWidth() / 2f, -src.getHeight() / 2f);
        m.postScale(k, k);
        m.postTranslate(w / 2f, h / 2f);
        drawScaled(c, src, m, k);
    }

    /** Bilinear filtering aliases below half size, so halve the source first if needed. */
    private static void drawScaled(Canvas c, Bitmap src, Matrix m, float k) {
        Bitmap s = src;
        while (k * src.getWidth() / s.getWidth() < 0.5f && s.getWidth() >= 4 && s.getHeight() >= 4) {
            Bitmap half = Bitmap.createScaledBitmap(s, s.getWidth() / 2, s.getHeight() / 2, true);
            if (s != src) s.recycle();
            s = half;
        }
        Matrix mm = new Matrix(m);
        if (s != src) mm.preScale((float) src.getWidth() / s.getWidth(), (float) src.getHeight() / s.getHeight());
        c.drawBitmap(s, mm, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        if (s != src) s.recycle();
    }

    private static void roundedImage(Canvas c, Bitmap src, RectF r, float rad) {
        BitmapShader s = new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setRectToRect(new RectF(0, 0, src.getWidth(), src.getHeight()), r, Matrix.ScaleToFit.FILL);
        s.setLocalMatrix(m);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setShader(s);
        c.drawRoundRect(r, rad, rad, p);
    }

    private Bitmap round(Bitmap square) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new BitmapShader(square, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        new Canvas(out).drawRoundRect(new RectF(0, 0, w, h), radius, radius, p);
        square.recycle();
        return out;
    }

    // ---------------------------------------------------------------- sources

    private static int customWhiteLogoRes(String pkg) {
        if ("org.smarttube.stable".equals(pkg) || "com.liskovsoft.smarttubetv.beta".equals(pkg)
                || "com.liskovsoft.smarttubetv".equals(pkg)) {
            return R.drawable.logo_smarttube;
        }
        if ("com.audible.application.firetv".equals(pkg) || "com.audible.application".equals(pkg)) {
            return R.drawable.logo_audible;
        }
        return 0;
    }

    private Drawable banner(String pkg) {
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        try {
            if (i != null) {
                Drawable d = pm.getActivityBanner(i);
                if (d != null) return d;
            }
            return pm.getApplicationBanner(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private Drawable icon(String pkg) {
        try {
            return pm.getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return pm.getDefaultActivityIcon();
        }
    }

    // ---------------------------------------------------------------- colour

    /**
     * Two ambient colours from the image's most common saturated hues (white/black
     * backgrounds are ignored), normalised to a calm brightness.
     */
    static int[] palette(Bitmap art) {
        int sw = 48, sh = 27;
        Bitmap b = Bitmap.createScaledBitmap(art, sw, sh, true);
        long[][] bins = new long[12][4];
        float[] hsv = new float[3];
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                int p = b.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                Color.colorToHSV(p, hsv);
                if (hsv[1] < 0.3f || hsv[2] < 0.25f) continue;
                long[] bin = bins[(int) (hsv[0] / 30f) % 12];
                bin[0] += Color.red(p); bin[1] += Color.green(p); bin[2] += Color.blue(p); bin[3]++;
            }
        }
        if (b != art) b.recycle();
        int first = -1, second = -1;
        for (int i = 0; i < 12; i++) {
            if (bins[i][3] == 0) continue;
            if (first < 0 || bins[i][3] > bins[first][3]) { second = first; first = i; }
            else if (second < 0 || bins[i][3] > bins[second][3]) second = i;
        }
        int a, c;
        if (first < 0) {
            a = 0xFF34364A; c = 0xFF1A1C2C;                       // monochrome art: cool slate
        } else {
            a = avg(bins[first]);
            c = (second >= 0 && bins[second][3] * 6 >= bins[first][3]) ? avg(bins[second]) : shiftHue(a, 28f);
        }
        return new int[]{normalise(a, 0.62f), normalise(c, 0.42f)};
    }

    private static int avg(long[] bin) {
        return Color.rgb((int) (bin[0] / bin[3]), (int) (bin[1] / bin[3]), (int) (bin[2] / bin[3]));
    }

    private static int normalise(int c, float value) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        hsv[1] = Math.max(0.45f, Math.min(hsv[1] * 1.1f, 0.92f));
        hsv[2] = value;
        return Color.HSVToColor(hsv);
    }

    private static int shiftHue(int c, float deg) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        hsv[0] = (hsv[0] + deg) % 360f;
        return Color.HSVToColor(hsv);
    }

    static int darken(int c, float f) {
        return Color.rgb((int) (Color.red(c) * f), (int) (Color.green(c) * f), (int) (Color.blue(c) * f));
    }
}
