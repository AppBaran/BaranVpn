package ir.baran.vpn;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import androidx.annotation.Nullable;

public final class TrafficWaveView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private float intensity = 0f;
    private float time = 0f;
    private float currentVisualIntensity = 0f;

    public TrafficWaveView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3.5f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    public void setTrafficSpeed(long bytesPerSec) {
        // Very dynamic scale
        // 0-10KB -> 0.0 to 0.1
        // 10KB-1MB -> 0.1 to 0.5
        // >1MB -> 0.5 to 1.0
        float newIntensity;
        if (bytesPerSec < 10240) {
            newIntensity = (bytesPerSec / 10240f) * 0.1f;
        } else if (bytesPerSec < 1048576) {
            newIntensity = 0.1f + ((float) Math.log10(bytesPerSec / 10240.0) / 2.0f) * 0.4f;
        } else {
            newIntensity = 0.5f + Math.min(0.5f, (float) Math.log10(bytesPerSec / 1048576.0) / 3.0f);
        }
        this.intensity = Math.max(0.01f, Math.min(1.0f, newIntensity));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        // Smoothly interpolate intensity for visual smoothness
        currentVisualIntensity += (intensity - currentVisualIntensity) * 0.1f;

        if (paint.getShader() == null) {
            paint.setShader(new LinearGradient(0, 0, w, 0,
                    new int[]{0x00A78BFA, 0xCCA78BFA, 0x00A78BFA},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        }

        path.reset();
        float centerY = h / 2f;
        
        // Much slower idle, faster active
        time += 0.015f + currentVisualIntensity * 0.18f;

        float step = 4f;
        for (float x = 0; x <= w; x += step) {
            float normalizedX = x / w;
            
            // Tremor that scales with data
            float y = (float) Math.sin(normalizedX * 18 - time * 1.5f) * (0.3f + currentVisualIntensity * 4.5f);
            
            // Heartbeat frequency scales with data
            float cycle = (time * (0.2f + currentVisualIntensity * 0.8f) + normalizedX * 3.2f) % 2.0f;
            
            if (cycle < 0.12f) { // R-peak
                float pulse = (float) Math.sin(cycle * Math.PI / 0.12f);
                y -= pulse * (4f + currentVisualIntensity * 45f);
            } else if (cycle > 0.12f && cycle < 0.22f) { // S-dip
                float dip = (float) Math.sin((cycle - 0.12f) * Math.PI / 0.10f);
                y += dip * (2f + currentVisualIntensity * 15f);
            }

            if (x == 0) path.moveTo(x, centerY + y);
            else path.lineTo(x, centerY + y);
        }

        canvas.drawPath(path, paint);
        invalidate();
    }
}