package tv.calm.home;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Full-screen backdrop / scrim layer.
 *
 * Engineered for single-pass GPU rendering and zero per-frame texture uploads:
 * - glowBuf (1/5 scale) is only redrawn when ambient colors actually change.
 * - vignetteSteps[0..DIM_STEPS] (2 x 1/5 height, ~14 KB total) pre-bakes the top/bottom
 *   vignette combined with each dim level ONCE in onSizeChanged. During scrolling and
 *   when settled in lower rows, AmbientView renders in a SINGLE GPU pass with zero
 *   full-screen drawColor overdraw and zero Scratch Texture allocations.
 * - At dim == 0 (top shelf), only the top 34% and bottom 50% strips are drawn, skipping
 *   the transparent middle 16% of the 1080p screen entirely.
 */
public class AmbientView extends View {
    private static final int BASE = 0xFF060608, SCALE = 5, DIM_STEPS = 16;
    private final Paint glowA = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint glowB = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint scrim = new Paint(Paint.DITHER_FLAG);
    private final Paint blit = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Rect dst = new Rect();
    private final Rect srcTop = new Rect(), dstTop = new Rect();
    private final Rect srcBot = new Rect(), dstBot = new Rect();
    private final ArgbEvaluator argb = new ArgbEvaluator();
    private int colorA = 0xFF1F4E5F, colorB = 0xFF0E2433;
    private int targetColorA = colorA, targetColorB = colorB;
    private float dim, targetDim;
    private float videoAlpha, targetVideoAlpha;   // 0 = opaque ambient glow, 1 = live video
    private boolean videoMode;
    private ValueAnimator colorAnim, dimAnim, videoAnim;
    private Bitmap glowBuf;
    private Canvas glowCanvas;
    private final Bitmap[] vignetteSteps = new Bitmap[DIM_STEPS + 1];
    private boolean glowDirty = true;

    public AmbientView(Context c) {
        super(c);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    public void setVideoMode(boolean on) {
        if (videoMode == on) return;
        videoMode = on;
        if (!on) setVideoPlaying(false);
        invalidate();
    }

    public void setVideoPlaying(boolean playing) {
        setVideoPlaying(playing, playing ? 650 : 220);
    }

    public void setVideoPlaying(boolean playing, long durationMs) {
        float target = (videoMode && playing) ? 1f : 0f;
        if (Math.abs(target - targetVideoAlpha) < 0.001f) return;
        targetVideoAlpha = target;
        if (videoAnim != null) videoAnim.cancel();
        if (Math.abs(target - videoAlpha) < 0.004f || durationMs <= 0) {
            videoAlpha = target;
            invalidate();
            return;
        }
        videoAnim = ValueAnimator.ofFloat(videoAlpha, target);
        videoAnim.setDuration(Math.max(80, durationMs));
        videoAnim.setInterpolator(new DecelerateInterpolator(1.5f));
        videoAnim.addUpdateListener(v -> {
            videoAlpha = (float) v.getAnimatedValue();
            invalidate();
        });
        videoAnim.start();
    }

    public void setColors(int a, int b) {
        if (a == targetColorA && b == targetColorB) return;
        targetColorA = a;
        targetColorB = b;
        if (colorAnim != null) colorAnim.cancel();
        if (videoAlpha >= 0.99f) {
            colorA = a;
            colorB = b;
            rebuildGlowShaders();
            return;
        }
        int fromA = colorA, fromB = colorB;
        colorAnim = ValueAnimator.ofFloat(0f, 1f);
        colorAnim.setDuration(420);
        colorAnim.setInterpolator(new DecelerateInterpolator());
        colorAnim.addUpdateListener(v -> {
            float f = (float) v.getAnimatedValue();
            colorA = (int) argb.evaluate(f, fromA, a);
            colorB = (int) argb.evaluate(f, fromB, b);
            rebuildGlowShaders();
            invalidate();
        });
        colorAnim.start();
    }

    /** 0 = top shelf fully lit, 1 = dimmed (scrolled down into the app grid). */
    public void setDim(float target) {
        if (Math.abs(target - targetDim) < 0.001f) return;
        targetDim = target;
        if (dimAnim != null) dimAnim.cancel();
        if (Math.abs(target - dim) < 0.004f) {
            dim = target;
            invalidate();
            return;
        }
        dimAnim = ValueAnimator.ofFloat(dim, target);
        dimAnim.setDuration(220);
        dimAnim.setInterpolator(new DecelerateInterpolator(1.8f));
        dimAnim.addUpdateListener(v -> {
            int oldStep = Math.round(dim * DIM_STEPS);
            dim = (float) v.getAnimatedValue();
            if (Math.round(dim * DIM_STEPS) != oldStep || videoAlpha < 0.996f) {
                invalidate();
            }
        });
        dimAnim.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (w == 0 || h == 0) return;
        dst.set(0, 0, w, h);
        int bw = Math.max(1, w / SCALE), bh = Math.max(1, h / SCALE);
        if (glowBuf != null) glowBuf.recycle();
        glowBuf = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        glowCanvas = new Canvas(glowBuf);
        scrim.setShader(new LinearGradient(0, bh * 0.30f, 0, bh, 0x00000000, 0xC0000000, Shader.TileMode.CLAMP));

        int topEnd = Math.round(bh * 0.34f);
        int botStart = Math.round(bh * 0.50f);
        srcTop.set(0, 0, 2, topEnd);
        dstTop.set(0, 0, w, Math.round(h * 0.34f));
        srcBot.set(0, botStart, 2, bh);
        dstBot.set(0, Math.round(h * 0.50f), w, h);

        Paint topV = new Paint(Paint.DITHER_FLAG);
        topV.setShader(new LinearGradient(0, 0, 0, bh * 0.34f, 0x58000000, 0x00000000, Shader.TileMode.CLAMP));
        Paint botV = new Paint(Paint.DITHER_FLAG);
        botV.setShader(new LinearGradient(0, bh * 0.50f, 0, bh, 0x00000000, 0x9E000000, Shader.TileMode.CLAMP));

        for (int i = 0; i <= DIM_STEPS; i++) {
            if (vignetteSteps[i] != null) vignetteSteps[i].recycle();
            Bitmap vb = Bitmap.createBitmap(2, bh, Bitmap.Config.ARGB_8888);
            Canvas vc = new Canvas(vb);
            vc.drawRect(0, 0, 2, bh * 0.34f, topV);
            vc.drawRect(0, bh * 0.50f, 2, bh, botV);
            if (i > 0) {
                float d = i / (float) DIM_STEPS;
                vc.drawColor(alpha(0xFF000000, Math.round(d * 0xB4)));
            }
            vignetteSteps[i] = vb;
        }

        rebuildGlowShaders();
    }

    private void rebuildGlowShaders() {
        glowDirty = true;
        if (glowBuf == null) return;
        int w = glowBuf.getWidth(), h = glowBuf.getHeight();
        glowA.setShader(new RadialGradient(w * 0.20f, h * 0.10f, w * 0.80f,
                new int[]{alpha(colorA, 0xD8), alpha(colorA, 0x58), 0},
                new float[]{0f, 0.48f, 1f}, Shader.TileMode.CLAMP));
        glowB.setShader(new RadialGradient(w * 0.92f, h * -0.05f, w * 0.62f,
                new int[]{alpha(colorB, 0xB8), alpha(colorB, 0x30), 0},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas c) {
        if (glowBuf == null) { c.drawColor(BASE); return; }
        int step = Math.min(DIM_STEPS, Math.max(0, Math.round(dim * DIM_STEPS)));
        if (videoAlpha < 0.996f) {
            if (glowDirty) {
                Canvas b = glowCanvas;
                b.drawColor(BASE);
                b.drawPaint(glowA);
                b.drawPaint(glowB);
                b.drawPaint(scrim);
                glowDirty = false;
            }
            blit.setAlpha(Math.round((1f - videoAlpha) * 255));
            c.drawBitmap(glowBuf, null, dst, blit);
            if (videoAlpha > 0.004f && vignetteSteps[step] != null) {
                blit.setAlpha(Math.round(videoAlpha * 255));
                c.drawBitmap(vignetteSteps[step], null, dst, blit);
            } else if (step > 0) {
                c.drawColor(alpha(0xFF000000, Math.round(dim * 0xB4)));
            }
            return;
        }
        // Fast path when live video is active (videoAlpha == 1): single-pass hardware blit.
        Bitmap vb = vignetteSteps[step];
        if (vb == null) return;
        blit.setAlpha(255);
        if (step == 0) {
            // Middle 16% of screen is 100% transparent when undimmed; skip drawing it.
            c.drawBitmap(vb, srcTop, dstTop, blit);
            c.drawBitmap(vb, srcBot, dstBot, blit);
        } else {
            c.drawBitmap(vb, null, dst, blit);
        }
    }

    private static int alpha(int c, int a) {
        return (c & 0x00FFFFFF) | (a << 24);
    }
}
