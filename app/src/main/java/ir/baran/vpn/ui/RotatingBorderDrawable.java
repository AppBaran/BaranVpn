package ir.baran.vpn.ui;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.view.animation.LinearInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Animated border with a soft rotating light-halo (gradient sweep) instead of a single white dot.
 */
public class RotatingBorderDrawable extends Drawable implements Animatable {
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Matrix matrix = new Matrix();
    private float angle = 0f;
    private final ValueAnimator animator;
    private final float strokeWidth;
    private final float cornerRadius;
    private SweepGradient sweep;

    public RotatingBorderDrawable(float strokeWidth, float cornerRadius) {
        this.strokeWidth = strokeWidth;
        this.cornerRadius = cornerRadius;

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(strokeWidth);
        borderPaint.setColor(0x339374F5);

        haloPaint.setStyle(Paint.Style.STROKE);
        haloPaint.setStrokeWidth(strokeWidth * 6.1f);
        haloPaint.setStrokeCap(Paint.Cap.ROUND);

        animator = ValueAnimator.ofFloat(0f, 360f);
        animator.setDuration(2800);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            angle = (float) animation.getAnimatedValue();
            invalidateSelf();
        });
    }

    private void ensureSweep(Rect bounds) {
        float cx = bounds.exactCenterX();
        float cy = bounds.exactCenterY();
        // Soft rotating light halo: bright core → purple glow → transparent
        int[] colors = new int[]{
                0x00FFFFFF,
                0x55FFFFFF,
                0xAAFFFFFF,
                0xFFFFFFFF,
                0xCCB794F6,
                0x669374F5,
                0x229374F5,
                0x00FFFFFF
        };
        float[] positions = new float[]{
                0f, 0.08f, 0.16f, 0.22f, 0.32f, 0.45f, 0.60f, 1f
        };
        sweep = new SweepGradient(cx, cy, colors, positions);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) return;

        float half = strokeWidth / 1f;
        rect.set(
                bounds.left + half,
                bounds.top + half,
                bounds.right - half,
                bounds.bottom - half
        );

        // Dim static base border
        borderPaint.setShader(null);
        borderPaint.setColor(0x339374F5);
        borderPaint.setStrokeWidth(strokeWidth);
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint);

        // Rotating gradient halo along the border
        if (sweep == null) {
            ensureSweep(bounds);
        }
        matrix.reset();
        matrix.setRotate(angle, bounds.exactCenterX(), bounds.exactCenterY());
        sweep.setLocalMatrix(matrix);
        haloPaint.setShader(sweep);
        haloPaint.setStrokeWidth(strokeWidth * 0.15f);
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, haloPaint);

        // Softer outer glow pass for a "light halo" feel
        haloPaint.setStrokeWidth(strokeWidth * 0.8f);
        haloPaint.setAlpha(70);
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, haloPaint);
        haloPaint.setAlpha(255);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        sweep = null; // rebuild gradient for new size
    }

    @Override
    public void setAlpha(int alpha) {
        borderPaint.setAlpha(alpha);
        haloPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        borderPaint.setColorFilter(colorFilter);
        haloPaint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public void start() {
        if (!animator.isRunning()) animator.start();
    }

    @Override
    public void stop() {
        animator.cancel();
    }

    @Override
    public boolean isRunning() {
        return animator.isRunning();
    }
}
