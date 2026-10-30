package tv.calm.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Small line icons drawn in code (weather, Wi-Fi, gear): crisp at any size, no image assets. */
final class Icons {
    private Icons() {}

    static final int CLEAR = 0, PARTLY = 1, CLOUD = 2, FOG = 3, DRIZZLE = 4, RAIN = 5, SNOW = 6, THUNDER = 7;

    /** WMO weather code (Open-Meteo) to one of the kinds above. */
    static int kindFor(int wmo) {
        if (wmo == 0) return CLEAR;
        if (wmo <= 2) return PARTLY;
        if (wmo == 3) return CLOUD;
        if (wmo == 45 || wmo == 48) return FOG;
        if (wmo >= 51 && wmo <= 57) return DRIZZLE;
        if ((wmo >= 61 && wmo <= 67) || (wmo >= 80 && wmo <= 82)) return RAIN;
        if ((wmo >= 71 && wmo <= 77) || wmo == 85 || wmo == 86) return SNOW;
        if (wmo >= 95) return THUNDER;
        return CLOUD;
    }

    static String describe(int wmo) {
        switch (kindFor(wmo)) {
            case CLEAR: return "Clear";
            case PARTLY: return "Partly cloudy";
            case FOG: return "Fog";
            case DRIZZLE: return "Drizzle";
            case RAIN: return wmo >= 80 ? "Showers" : "Rain";
            case SNOW: return "Snow";
            case THUNDER: return "Thunderstorms";
            default: return "Cloudy";
        }
    }

    abstract static class IconView extends View {
        final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);

        IconView(Context c) {
            super(c);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            fill.setStyle(Paint.Style.FILL);
            setColor(0xF2FFFFFF);
        }

        void setColor(int c) {
            stroke.setColor(c);
            fill.setColor(c);
            invalidate();
        }

        /** Draw in a 24x24 box. */
        abstract void draw24(Canvas c);

        @Override
        protected void onDraw(Canvas c) {
            float s = Math.min(getWidth(), getHeight()) / 24f;
            c.save();
            c.translate((getWidth() - 24 * s) / 2f, (getHeight() - 24 * s) / 2f);
            c.scale(s, s);
            stroke.setStrokeWidth(1.7f);
            draw24(c);
            c.restore();
        }
    }

    static class Weather extends IconView {
        private int kind = CLOUD;
        private boolean day = true;

        Weather(Context c) { super(c); }

        void set(int kind, boolean day) {
            this.kind = kind;
            this.day = day;
            invalidate();
        }

        @Override
        void draw24(Canvas c) {
            switch (kind) {
                case CLEAR:
                    if (day) sun(c, 12, 12, 4.2f, true); else moon(c, 12, 12, 7.5f);
                    break;
                case PARTLY: {
                    // Sun/moon peeking out from behind the cloud: clip it to outside the cloud.
                    Path behind = cloudPath(1.5f, 3.5f, 0.9f);
                    Paint wide = new Paint(stroke);
                    wide.setStyle(Paint.Style.FILL_AND_STROKE);
                    wide.setStrokeWidth(4.5f);
                    Path mask = new Path();
                    wide.getFillPath(behind, mask);
                    c.save();
                    c.clipOutPath(mask);
                    if (day) sun(c, 8.5f, 8.5f, 3.2f, true); else moon(c, 9, 8, 5f);
                    c.restore();
                    c.drawPath(behind, stroke);
                    break;
                }
                case FOG:
                    for (int i = 0; i < 3; i++) c.drawLine(4 + (i % 2) * 2, 8 + i * 4, 20 - ((i + 1) % 2) * 2, 8 + i * 4, stroke);
                    break;
                case DRIZZLE:
                case RAIN:
                    cloud(c, 0, -2, 1f);
                    int drops = kind == RAIN ? 3 : 2;
                    for (int i = 0; i < drops; i++) {
                        float x = 8.5f + i * (kind == RAIN ? 3.5f : 5f);
                        c.drawLine(x, 17, x - 1.2f, 20.5f, stroke);
                    }
                    break;
                case SNOW:
                    cloud(c, 0, -2, 1f);
                    for (int i = 0; i < 3; i++) c.drawCircle(8.5f + i * 3.5f, 18.5f + (i % 2) * 1.6f, 1.1f, fill);
                    break;
                case THUNDER:
                    cloud(c, 0, -2, 1f);
                    Path bolt = new Path();
                    bolt.moveTo(12.8f, 15f); bolt.lineTo(10.4f, 18.6f); bolt.lineTo(13f, 18.6f); bolt.lineTo(11.2f, 22f);
                    c.drawPath(bolt, stroke);
                    break;
                default:
                    cloud(c, 0, 0, 1f);
            }
        }

        private void sun(Canvas c, float cx, float cy, float r, boolean rays) {
            c.drawCircle(cx, cy, r, stroke);
            if (!rays) return;
            for (int i = 0; i < 8; i++) {
                double a = Math.PI / 4 * i;
                float x1 = (float) (cx + Math.cos(a) * (r + 2.2f)), y1 = (float) (cy + Math.sin(a) * (r + 2.2f));
                float x2 = (float) (cx + Math.cos(a) * (r + 3.8f)), y2 = (float) (cy + Math.sin(a) * (r + 3.8f));
                c.drawLine(x1, y1, x2, y2, stroke);
            }
        }

        private void moon(Canvas c, float cx, float cy, float r) {
            Path p = new Path();
            p.addCircle(cx, cy, r, Path.Direction.CW);
            Path bite = new Path();
            bite.addCircle(cx + r * 0.55f, cy - r * 0.4f, r * 0.85f, Path.Direction.CW);
            p.op(bite, Path.Op.DIFFERENCE);
            c.drawPath(p, stroke);
        }

        private void cloud(Canvas c, float dx, float dy, float k) {
            c.drawPath(cloudPath(dx, dy, k), stroke);
        }

        /** Cloud outline spanning x 3..21.5, y 6..18 at scale 1, scaled about (12,13) then offset. */
        private static Path cloudPath(float dx, float dy, float k) {
            Path p = new Path();
            p.moveTo(7, 18);
            p.lineTo(17.5f, 18);
            p.arcTo(new RectF(14.5f, 11, 21.5f, 18), 90, -180, false);   // right bump
            p.arcTo(new RectF(8, 6, 16.5f, 14.5f), -10, -160, false);   // top dome
            p.arcTo(new RectF(3, 11, 10, 18), -90, -180, false);         // left bump
            p.close();
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setScale(k, k, 12, 13);
            m.postTranslate(dx, dy);
            p.transform(m);
            return p;
        }
    }

    static class Wifi extends IconView {
        private int level = 3;   // 0..3, -1 = offline

        Wifi(Context c) { super(c); }

        void setLevel(int level) {
            this.level = level;
            invalidate();
        }

        @Override
        void draw24(Canvas c) {
            int on = stroke.getColor(), off = (on & 0x00FFFFFF) | 0x47000000;
            for (int i = 0; i < 3; i++) {
                float r = 5.5f + i * 4.2f;
                stroke.setColor(level > i ? on : off);
                c.drawArc(new RectF(12 - r, 19 - r, 12 + r, 19 + r), -135, 90, false, stroke);
            }
            stroke.setColor(on);
            fill.setColor(level >= 0 ? on : off);
            c.drawCircle(12, 18.6f, 1.6f, fill);
            if (level < 0) c.drawLine(5, 5, 19, 19, stroke);
            fill.setColor(on);
        }
    }

    static class Gear extends IconView {
        Gear(Context c) { super(c); }

        @Override
        void draw24(Canvas c) {
            for (int i = 0; i < 8; i++) {
                c.save();
                c.rotate(45 * i, 12, 12);
                c.drawRoundRect(new RectF(10.6f, 2.6f, 13.4f, 6.2f), 0.8f, 0.8f, fill);
                c.restore();
            }
            c.drawCircle(12, 12, 6.3f, stroke);
            c.drawCircle(12, 12, 2.4f, stroke);
        }
    }

    static class Scene extends IconView {
        Scene(Context c) { super(c); }

        @Override
        void draw24(Canvas c) {
            Path p = new Path();
            p.moveTo(2.5f, 18.5f);
            p.lineTo(9.5f, 7.5f);
            p.lineTo(14.5f, 14.5f);
            p.lineTo(17.5f, 10.5f);
            p.lineTo(21.5f, 18.5f);
            p.close();
            c.drawPath(p, stroke);
            c.drawCircle(17.5f, 6.2f, 1.8f, fill);
        }
    }
}
