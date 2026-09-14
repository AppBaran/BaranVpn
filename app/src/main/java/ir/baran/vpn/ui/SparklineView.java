package ir.baran.vpn.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * Minimal sparkline for upload/download rate history.
 */
public class SparklineView extends View {

    private final float[] points = new float[24];
    private int size = 0;
    private final Path linePath = new Path();
    private final Path fillPath = new Path();
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int strokeColor = 0xFF5CE68F;
    private int fillColor = 0x335CE68F;

    public SparklineView(Context context) {
        super(context);
        init();
    }

    public SparklineView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SparklineView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(1.6f));
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        fillPaint.setStyle(Paint.Style.FILL);
        setWillNotDraw(false);
    }

    public void setColors(int stroke, int fill) {
        strokeColor = stroke;
        fillColor = fill;
        invalidate();
    }

    /** Push a non-negative sample (bytes/sec or absolute); auto-scales. */
    public void push(float value) {
        if (value < 0f) value = 0f;
        if (size < points.length) {
            points[size++] = value;
        } else {
            System.arraycopy(points, 1, points, 0, points.length - 1);
            points[points.length - 1] = value;
        }
        invalidate();
    }

    public void clear() {
        size = 0;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (size < 2) return;

        float w = getWidth() - getPaddingLeft() - getPaddingRight();
        float h = getHeight() - getPaddingTop() - getPaddingBottom();
        if (w <= 0 || h <= 0) return;

        float left = getPaddingLeft();
        float top = getPaddingTop();
        float max = 1f;
        for (int i = 0; i < size; i++) {
            if (points[i] > max) max = points[i];
        }

        linePath.reset();
        fillPath.reset();
        float bottomPadding = dp(8f); // Increased padding to avoid clipping
        float topPadding = dp(2f);
        float usableH = h - bottomPadding - topPadding;
        
        for (int i = 0; i < size; i++) {
            float x = left + (w * i / (float) (points.length - 1));
            float y = top + topPadding + usableH - (points[i] / max) * (usableH * 0.85f);
            if (i == 0) {
                linePath.moveTo(x, y);
                fillPath.moveTo(x, top + h);
                fillPath.lineTo(x, y);
            } else {
                float prevX = left + (w * (i - 1) / (float) (points.length - 1));
                float prevY = top + topPadding + usableH - (points[i - 1] / max) * (usableH * 0.85f);
                float cp1x = (prevX + x) / 2;
                linePath.cubicTo(cp1x, prevY, cp1x, y, x, y);
                fillPath.cubicTo(cp1x, prevY, cp1x, y, x, y);
            }
        }
        float lastX = left + (w * (size - 1) / (float) (points.length - 1));
        fillPath.lineTo(lastX, top + h);
        fillPath.close();

        fillPaint.setShader(new LinearGradient(
                0, top, 0, top + h,
                adjustAlpha(fillColor, 0.7f), Color.TRANSPARENT,
                Shader.TileMode.CLAMP
        ));
        canvas.drawPath(fillPath, fillPaint);

        linePaint.setColor(strokeColor);
        linePaint.setStrokeWidth(dp(2.2f));
        // Add a subtle glow effect to the line
        linePaint.setShadowLayer(dp(3f), 0, 0, strokeColor);
        canvas.drawPath(linePath, linePaint);
        linePaint.clearShadowLayer();
    }

    private int adjustAlpha(int color, float factor) {
        int alpha = Math.round(Color.alpha(color) * factor);
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
