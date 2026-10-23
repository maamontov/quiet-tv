package local.quiet.tv;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.HorizontalScrollView;

/** Small native drawing primitives. No live blur or continuously running animations. */
final class TvStyle {
    static final int WHITE = Color.rgb(245, 245, 245);
    static final int MUTED = Color.rgb(162, 162, 162);
    static final int INPUT = 1, SETTINGS = 2, MOVE = 3, HIDE = 4, HOME = 5,
            RESTORE = 6, CHEVRON = 7, BACK = 8, CHECK = 9, HORIZONTAL = 10, UP = 11, BELL = 12;

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /** One shared, pre-rendered focus texture; no GPU blur or shadow calculation. */
    static final class FocusGlow extends Drawable {
        private static Bitmap texture;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF destination = new RectF();
        private final int outset;
        private ValueAnimator fade;
        FocusGlow(Context context) {
            outset = dp(context, 58);
            if (texture == null) {
                texture = Bitmap.createBitmap(320, 200, Bitmap.Config.ARGB_8888);
                Paint brush = new Paint(Paint.ANTI_ALIAS_FLAG);
                brush.setColor(0x55ffffff);
                brush.setShadowLayer(27, 0, 0, 0x99ffffff);
                new Canvas(texture).drawRoundRect(43, 40, 277, 160, 18, 18, brush);
            }
            paint.setAlpha(0);
        }
        void focus(boolean focused) {
            stop();
            int target = focused ? 255 : 0;
            if (!ValueAnimator.areAnimatorsEnabled()) { setAlpha(target); return; }
            fade = ValueAnimator.ofInt(paint.getAlpha(), target);
            fade.setDuration(420);
            fade.setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f));
            fade.addUpdateListener(animation -> setAlpha((Integer) animation.getAnimatedValue()));
            fade.start();
        }
        void stop() { if (fade != null) { fade.cancel(); fade = null; } }
        @Override public void draw(Canvas canvas) {
            if (paint.getAlpha() == 0) return;
            destination.set(getBounds()); destination.inset(-outset, -outset);
            canvas.drawBitmap(texture, null, destination, paint);
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setTint(int color) {
            paint.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
            invalidateSelf();
        }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    static final class Rail extends HorizontalScrollView {
        private boolean running;
        private double position, velocity;
        private int target;
        private long lastFrame;
        private final Runnable frame = new Runnable() {
            @Override public void run() {
                if (!running) return;
                long now = System.nanoTime();
                double dt = Math.min(.05, (now - lastFrame) / 1_000_000_000.0);
                lastFrame = now;
                // Critically damped motion keeps velocity when a key retargets the rail.
                double omega = 14;
                double displacement = position - target;
                double c = velocity + omega * displacement;
                double decay = Math.exp(-omega * dt);
                position = target + (displacement + c * dt) * decay;
                velocity = (velocity - omega * c * dt) * decay;
                scrollTo((int) Math.round(position), 0);
                if (Math.abs(position - target) < .5 && Math.abs(velocity) < 5) {
                    running = false; scrollTo(target, 0); return;
                }
                postOnAnimation(this);
            }
        };
        Rail(Context context) {
            super(context);
            setHorizontalScrollBarEnabled(false);
            setOverScrollMode(OVER_SCROLL_NEVER);
            setClipToPadding(false);
            setClipChildren(false);
            setFocusable(false);
        }
        // Automatic focus scrolling fights the centered animation; control it in one place.
        @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) { return 0; }
        void reveal(View tile, boolean immediate) {
            if (getChildCount() == 0 || getWidth() == 0) return;
            int max = Math.max(0, getChildAt(0).getWidth() - getWidth() + getPaddingLeft() + getPaddingRight());
            target = Math.max(0, Math.min(max, tile.getLeft() + getPaddingLeft() + tile.getWidth() / 2 - getWidth() / 2));
            if (immediate || !ValueAnimator.areAnimatorsEnabled()) {
                running = false; removeCallbacks(frame); position = target; velocity = 0; scrollTo(target, 0); return;
            }
            if (running || target == getScrollX()) return;
            position = getScrollX(); velocity = 0; lastFrame = System.nanoTime();
            running = true; postOnAnimation(frame);
        }
        @Override protected void onDetachedFromWindow() {
            running = false; removeCallbacks(frame);
            super.onDetachedFromWindow();
        }
    }

    static final class Surface extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float radius, stroke;
        private final boolean bright;
        private final int tint;
        Surface(Context context, int radius, boolean bright) { this(context, radius, bright, 0xff262626); }
        Surface(Context context, int radius, boolean bright, int tint) {
            this.radius = dp(context, radius);
            stroke = context.getResources().getDisplayMetrics().density;
            this.bright = bright;
            this.tint = tint;
        }
        @Override protected void onBoundsChange(Rect bounds) {
            rect.set(bounds);
            rect.inset(stroke * .5f, stroke * .5f);
        }
        @Override public void draw(Canvas canvas) {
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(null);
            paint.setColor(bright ? 0xfff7f5ef : tint);
            canvas.drawRoundRect(rect, radius, radius, paint);
            paint.setColor(bright ? 0xffeeeeee : 0x14ffffff);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            canvas.drawRoundRect(rect, radius, radius, paint);
        }
        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter filter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public void getOutline(android.graphics.Outline outline) {
            outline.setRoundRect(getBounds(), radius);
            outline.setAlpha(bright ? .35f : .18f);
        }
    }

    static final class Symbol extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int symbol;
        private final Path path = new Path();
        private int color = WHITE;
        Symbol(Context context, int symbol) {
            super(context); this.symbol = symbol;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        void tint(int color) { this.color = color; invalidate(); }
        private void line(Canvas canvas, float x1, float y1, float x2, float y2) { canvas.drawLine(x1, y1, x2, y2, paint); }
        @Override protected void onDraw(Canvas canvas) {
            canvas.save();
            float scale = Math.min(getWidth(), getHeight()) / 24f;
            canvas.translate((getWidth() - scale * 24) / 2, (getHeight() - scale * 24) / 2);
            canvas.scale(scale, scale);
            paint.setColor(color); paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.65f); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
            path.reset();
            if (symbol == INPUT) {
                canvas.drawRoundRect(3, 4, 21, 17, 2, 2, paint); line(canvas, 8, 21, 16, 21); line(canvas, 12, 17, 12, 21);
            } else if (symbol == SETTINGS) {
                canvas.drawCircle(12, 12, 3.4f, paint);
                canvas.drawCircle(12, 12, 8, paint);
                for (int i = 0; i < 8; i++) {
                    canvas.save(); canvas.rotate(i * 45, 12, 12); line(canvas, 12, 2, 12, 4.5f); canvas.restore();
                }
            } else if (symbol == MOVE || symbol == HORIZONTAL) {
                line(canvas, 3, 12, 21, 12); line(canvas, 3, 12, 7, 8); line(canvas, 3, 12, 7, 16);
                line(canvas, 21, 12, 17, 8); line(canvas, 21, 12, 17, 16);
            } else if (symbol == HIDE || symbol == RESTORE) {
                path.moveTo(2, 12); path.cubicTo(7, 4, 17, 4, 22, 12); path.cubicTo(17, 20, 7, 20, 2, 12);
                canvas.drawPath(path, paint); canvas.drawCircle(12, 12, 2.5f, paint);
                if (symbol == HIDE) line(canvas, 4, 3, 20, 21);
            } else if (symbol == HOME) {
                path.moveTo(3, 11); path.lineTo(12, 3); path.lineTo(21, 11); path.moveTo(6, 9);
                path.lineTo(6, 21); path.lineTo(18, 21); path.lineTo(18, 9); canvas.drawPath(path, paint);
            } else if (symbol == BACK) {
                line(canvas, 16, 5, 9, 12); line(canvas, 9, 12, 16, 19);
            } else if (symbol == UP) {
                line(canvas, 6, 14, 12, 8); line(canvas, 12, 8, 18, 14);
            } else if (symbol == CHECK) {
                line(canvas, 5, 12, 10, 17); line(canvas, 10, 17, 20, 6);
            } else if (symbol == BELL) {
                path.moveTo(4, 17); path.lineTo(6, 14); path.lineTo(6, 9);
                path.cubicTo(6, 1, 18, 1, 18, 9); path.lineTo(18, 14);
                path.lineTo(20, 17); path.close(); canvas.drawPath(path, paint);
                path.reset(); path.moveTo(9, 20); path.quadTo(12, 23, 15, 20); canvas.drawPath(path, paint);
            } else if (symbol == CHEVRON) {
                line(canvas, 9, 6, 15, 12); line(canvas, 15, 12, 9, 18);
            }
            canvas.restore();
        }
    }
}
