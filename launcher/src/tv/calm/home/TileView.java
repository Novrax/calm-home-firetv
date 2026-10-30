package tv.calm.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;

/**
 * An app tile: its pre-rounded picture plus the focus gloss and the sweeping glint, all drawn
 * as round rects so nothing needs clipping or an offscreen layer.
 */
final class TileView extends View {
    private final Paint artPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint glossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix glintMatrix = new Matrix();
    private final RectF bounds = new RectF();
    private final RectF ringBounds = new RectF();
    private final float radius;
    private Bitmap art;
    private float gloss, glintX = Float.NaN;
    private ValueAnimator glossAnim, glintAnim;

    TileView(Context c, float radius) {
        super(c);
        this.radius = radius;
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(2.2f * c.getResources().getDisplayMetrics().density);
        ringPaint.setColor(0xF2FFFFFF);
        setFocusable(true);
        setClickable(true);
        setLongClickable(true);
        setOutlineProvider(new ViewOutlineProvider() {   // for the shadow only
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
    }

    void setArt(Bitmap b) {
        art = b;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        bounds.set(0, 0, w, h);
        float inset = ringPaint.getStrokeWidth() * 0.5f;
        ringBounds.set(inset, inset, w - inset, h - inset);
        glossPaint.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0x2EFFFFFF, 0x08FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
        glintPaint.setShader(new LinearGradient(0, 0, w / 2f, 0,
                new int[]{0x00FFFFFF, 0x30FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
    }

    /** Gloss + focus ring on/off; with {@code sweep} the glint crosses the tile once. */
    void focusFx(boolean on, boolean sweep) {
        if (glossAnim != null) glossAnim.cancel();
        if (glintAnim != null) glintAnim.cancel();
        glintX = Float.NaN;
        if (!sweep) {
            gloss = on ? 1f : 0f;
            invalidate();
            return;
        }
        glossAnim = ValueAnimator.ofFloat(gloss, on ? 1f : 0f).setDuration(on ? 200 : 150);
        glossAnim.addUpdateListener(a -> { gloss = (float) a.getAnimatedValue(); invalidate(); });
        glossAnim.start();
        if (on) {
            float w = getWidth();
            glintAnim = ValueAnimator.ofFloat(-w * 0.6f, w * 1.2f).setDuration(700);
            glintAnim.setStartDelay(65);
            glintAnim.setInterpolator(new DecelerateInterpolator(1.6f));
            glintAnim.addUpdateListener(a -> {
                glintX = a.getAnimatedFraction() >= 1f ? Float.NaN : (float) a.getAnimatedValue();
                invalidate();
            });
            glintAnim.start();
        }
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (art != null) c.drawBitmap(art, null, bounds, artPaint);
        if (gloss > 0f) {
            glossPaint.setAlpha(Math.round(gloss * 255));
            c.drawRoundRect(bounds, radius, radius, glossPaint);
        }
        if (!Float.isNaN(glintX)) {
            glintMatrix.setRotate(18f, getWidth() / 2f, getHeight() / 2f);
            glintMatrix.preTranslate(glintX, 0);
            glintPaint.getShader().setLocalMatrix(glintMatrix);
            c.drawRoundRect(bounds, radius, radius, glintPaint);
        }
        if (gloss > 0f) {
            ringPaint.setAlpha(Math.round(gloss * 242));
            c.drawRoundRect(ringBounds, radius, radius, ringPaint);
        }
    }
}
