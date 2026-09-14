package ir.baran.vpn;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.annotation.Nullable;

import java.util.Random;

public final class ConnectionOrbView extends View {

    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_CONNECTED = 2;
    private static final int STATE_DISCONNECTING = 3;
    private static final int STATE_ERROR = 4;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint particlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint satellitePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF arcBounds = new RectF();
    private final Path bodyPath = new Path();
    private final Paint.FontMetrics fontMetrics = new Paint.FontMetrics();

    private static final int PARTICLE_COUNT = 150;
    private final float[] particleAngles = new float[PARTICLE_COUNT];
    private final float[] particleBaseRadii = new float[PARTICLE_COUNT];
    private final float[] particleSpeeds = new float[PARTICLE_COUNT];
    private final float[] particleSizes = new float[PARTICLE_COUNT];
    private final int[] particleAlphas = new int[PARTICLE_COUNT];
    private final float[] particleZDistances = new float[PARTICLE_COUNT];
    private final float[] particlePulseOffsets = new float[PARTICLE_COUNT];

    // نقاط مینیمال و خلوت اطراف دکمه (Micro-Dots)
    private static final int SATELLITE_COUNT = 5;
    private final float[] satAngles = new float[SATELLITE_COUNT];
    private final float[] satOrbitRadii = new float[SATELLITE_COUNT];
    private final float[] satSpeeds = new float[SATELLITE_COUNT];
    private final float[] satSizes = new float[SATELLITE_COUNT];
    private final float[] satExplosionDistances = new float[SATELLITE_COUNT];

    private Shader bodyShader;
    private Shader innerCoreShader;
    private Shader highlightShader;

    private ValueAnimator renderLoopAnimator;
    private ValueAnimator touchAnimator;
    private ValueAnimator explosionAnimator;

    private int currentState = STATE_DISCONNECTED;
    private float touchScale = 1f;
    private float explosionProgress = 0f;
    private String userLabel = "";

    private int primaryColor;
    private int secondaryColor;

    private final long startTime = System.currentTimeMillis();

    public ConnectionOrbView(Context context) {
        super(context, null);
        init();
    }

    public ConnectionOrbView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ConnectionOrbView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setClickable(true);
        setFocusable(true);
        setLayerType(LAYER_TYPE_HARDWARE, null);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));

        subTextPaint.setColor(Color.argb(200, 255, 255, 255));
        subTextPaint.setTextAlign(Paint.Align.CENTER);
        subTextPaint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));

        iconPaint.setColor(Color.WHITE);
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        iconPaint.setStrokeJoin(Paint.Join.ROUND);

        particlePaint.setStyle(Paint.Style.FILL);
        satellitePaint.setStyle(Paint.Style.FILL);

        Random random = new Random(777);
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            particleAngles[i] = (float) (random.nextDouble() * Math.PI * 2);
            particleBaseRadii[i] = 0.1f + random.nextFloat() * 0.7f;
            particleSpeeds[i] = 0.2f + random.nextFloat() * 1.5f;
            particleSizes[i] = 1.2f + random.nextFloat() * 3.6f;
            particleAlphas[i] = random.nextInt(170) + 80;
            particleZDistances[i] = random.nextFloat();
            particlePulseOffsets[i] = random.nextFloat() * (float) Math.PI * 2;
        }

        // تنظیمات بسیار خلوت و مینیمال برای نقاط دور دکمه
        for (int i = 0; i < SATELLITE_COUNT; i++) {
            satAngles[i] = (float) (i * (Math.PI * 2 / SATELLITE_COUNT));
            satOrbitRadii[i] = 1.10f; // فاصله مدرن و شکیل
            satSpeeds[i] = 0.35f + (i % 2 == 0 ? 0.15f : -0.2f);
            satSizes[i] = 2.2f + random.nextFloat() * 1.0f; // بسیار ریز و ظریف
            satExplosionDistances[i] = 1.32f + (i * 0.04f); // پرتاب نرم موقع اتصال
        }

        updateColors();
    }

    public void setConnectionState(String value, String text) {
        int nextState;
        if ("connected".equals(value)) {
            nextState = STATE_CONNECTED;
        } else if ("disconnecting".equals(value)) {
            nextState = STATE_DISCONNECTING;
        } else if ("starting".equals(value) || "smart-testing".equals(value) ||
                "scanning".equals(value) || "securing".equals(value) ||
                "reconnecting".equals(value) ||
                "proxy-starting".equals(value) || "proxy-connected".equals(value)) {
            nextState = STATE_CONNECTING;
        } else if ("error".equals(value) || "blocked".equals(value)) {
            nextState = STATE_ERROR;
        } else {
            nextState = STATE_DISCONNECTED;
        }

        boolean changed = (nextState != currentState);
        currentState = nextState;
        userLabel = text == null ? "" : text;

        if (changed) {
            updateColors();
            updateShaders();

            if (currentState == STATE_CONNECTED) {
                animateExplosion(1f);
            } else if (currentState == STATE_DISCONNECTED || currentState == STATE_ERROR || currentState == STATE_DISCONNECTING) {
                animateExplosion(0f);
            }
        }
        invalidate();
    }

    private void animateExplosion(float targetProgress) {
        if (explosionAnimator != null) explosionAnimator.cancel();
        explosionAnimator = ValueAnimator.ofFloat(explosionProgress, targetProgress);
        explosionAnimator.setDuration(850);
        explosionAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        explosionAnimator.addUpdateListener(a -> {
            explosionProgress = (Float) a.getAnimatedValue();
            invalidate();
        });
        explosionAnimator.start();
    }

    private void updateColors() {
        switch (currentState) {
            case STATE_CONNECTED:
                primaryColor = Color.parseColor("#34D399");
                secondaryColor = Color.parseColor("#059669");
                break;
            case STATE_CONNECTING:
                primaryColor = Color.parseColor("#C084FC");
                secondaryColor = Color.parseColor("#7C3AED");
                break;
            case STATE_DISCONNECTING:
                primaryColor = Color.parseColor("#FBBF24");
                secondaryColor = Color.parseColor("#D97706");
                break;
            case STATE_ERROR:
                primaryColor = Color.parseColor("#EF4444");
                secondaryColor = Color.parseColor("#DC2626");
                break;
            case STATE_DISCONNECTED:
            default:
                primaryColor = Color.parseColor("#9374F5");
                secondaryColor = Color.parseColor("#7E5BEF");
                break;
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateShaders();
    }

    private void updateShaders() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float cx = w / 2f;
        float cy = h / 2f;
        float radius = Math.min(w, h) * 0.42f;

        bodyShader = new RadialGradient(
                cx - radius * 0.25f, cy - radius * 0.25f, radius * 1.1f,
                new int[]{ lighten(primaryColor, 0.35f), primaryColor, secondaryColor, adjustAlpha(secondaryColor, 0.85f) },
                new float[]{ 0f, 0.4f, 0.8f, 1f },
                Shader.TileMode.CLAMP
        );

        innerCoreShader = new RadialGradient(
                cx, cy, radius * 0.75f,
                new int[]{ adjustAlpha(secondaryColor, 0.3f), Color.argb(200, 10, 10, 25) },
                null,
                Shader.TileMode.CLAMP
        );

        highlightShader = new RadialGradient(
                cx - radius * 0.35f, cy - radius * 0.45f, radius * 0.65f,
                new int[]{ Color.argb(140, 255, 255, 255), Color.TRANSPARENT },
                null,
                Shader.TileMode.CLAMP
        );
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        float cx = w / 2f;
        float cy = h / 2f;
        float baseRadius = Math.min(w, h) * 0.42f;

        float timeSeconds = (System.currentTimeMillis() - startTime) / 1000f;
        float speedMult = (currentState == STATE_CONNECTING) ? 2.5f : 1.0f;

        canvas.save();
        canvas.scale(touchScale, touchScale, cx, cy);

        float pulse = 1f + 0.03f * (float) Math.sin(timeSeconds * 1.5f * Math.PI);
        float radius = baseRadius * pulse;

        // 1. ذرات بیرونی
        if (currentState != STATE_ERROR) {
            for (int i = 0; i < PARTICLE_COUNT; i++) {
                float angle = particleAngles[i] + timeSeconds * particleSpeeds[i] * speedMult * (particleZDistances[i] > 0.5f ? 1f : -1f);
                boolean isExternalParticle = (i % 2 == 0);

                float distance;
                if (isExternalParticle) {
                    float spreadFactor = (i % 3 == 0) ? 0.75f : 0.35f;
                    float explosionOffset = explosionProgress * (spreadFactor + (i % 6) * 0.08f);
                    distance = radius * (0.82f + explosionOffset);
                } else {
                    distance = radius * particleBaseRadii[i] * 0.75f;
                }

                float px = cx + (float) Math.cos(angle) * distance;
                float py = cy + (float) Math.sin(angle) * distance;

                if (isExternalParticle && explosionProgress <= 0.01f) {
                    continue;
                }

                int alpha = (int) (particleAlphas[i] * (0.4f + 0.6f * Math.abs(Math.sin(angle * 2.0f + timeSeconds * 2f))));
                particlePaint.setColor(i % 3 == 0 ? Color.WHITE : primaryColor);
                particlePaint.setAlpha(Math.max(30, Math.min(255, alpha)));

                canvas.drawCircle(px, py, particleSizes[i] * (radius / 140f), particlePaint);
            }
        }

        // 2. بدنه اصلی شیشه‌ای دکمه
        paint.reset();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(bodyShader);
        Path organicBody = buildOrganicPath(cx, cy, radius * 0.98f, timeSeconds);
        canvas.drawPath(organicBody, paint);

        // 3. هسته مرکزی
        paint.setShader(innerCoreShader);
        canvas.drawCircle(cx, cy, radius * 0.78f, paint);

        // 4. ذرات داخلی
        if (currentState != STATE_ERROR) {
            for (int i = 0; i < PARTICLE_COUNT; i++) {
                if (i % 2 != 0) {
                    float angle = particleAngles[i] + timeSeconds * particleSpeeds[i] * speedMult;

                    float commuteWave = (float) Math.sin(timeSeconds * 2.5f + particlePulseOffsets[i]);
                    float dynamicDistanceFactor = particleBaseRadii[i] * 0.75f + (explosionProgress * commuteWave * 0.25f);

                    float distance = radius * Math.max(0.1f, Math.min(1.05f, dynamicDistanceFactor));
                    float px = cx + (float) Math.cos(angle) * distance;
                    float py = cy + (float) Math.sin(angle) * distance;

                    int alpha = (int) (particleAlphas[i] * (0.5f + 0.5f * Math.abs(commuteWave)));
                    particlePaint.setColor(i % 4 == 0 ? Color.WHITE : primaryColor);
                    particlePaint.setAlpha(Math.max(40, Math.min(255, alpha)));

                    canvas.drawCircle(px, py, particleSizes[i] * (radius / 140f), particlePaint);
                }
            }
        }

        // 5. رسم نقاط مینیمالِ دور دکمه (بسیار ظریف، خلوت و شیشه‌ای)
        for (int i = 0; i < SATELLITE_COUNT; i++) {
            float currentSatAngle = satAngles[i] + timeSeconds * satSpeeds[i];
            float currentOrbitRadius = radius * (satOrbitRadii[i] + (satExplosionDistances[i] - satOrbitRadii[i]) * explosionProgress);

            float sx = cx + (float) Math.cos(currentSatAngle) * currentOrbitRadius;
            float sy = cy + (float) Math.sin(currentSatAngle) * currentOrbitRadius;

            float dotSize = satSizes[i] * (radius / 140f);

            // نقطه‌ی نورانی مینیمال
            satellitePaint.setStyle(Paint.Style.FILL);
            satellitePaint.setColor(i == 0 ? Color.WHITE : primaryColor);
            satellitePaint.setAlpha(200 + (int)(55 * explosionProgress));
            canvas.drawCircle(sx, sy, dotSize, satellitePaint);
        }

        // 6. هایلایت شیشه‌ای رویی
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(highlightShader);
        canvas.drawCircle(cx, cy, radius * 0.95f, paint);

        // 7. آیکون پاور مرکزی
        paint.setShader(null);
        float iconY = cy - radius * 0.17f;
        iconPaint.setStrokeWidth(Math.max(5f, radius * 0.045f));
        canvas.drawLine(cx, iconY - radius * 0.25f, cx, iconY, iconPaint);
        arcBounds.set(cx - radius * 0.20f, iconY - radius * 0.14f, cx + radius * 0.20f, iconY + radius * 0.26f);
        canvas.drawArc(arcBounds, -45f, 270f, false, iconPaint);

        // 8. متون رابط کاربری
        float primaryTextSize = Math.max(16f, radius * 0.13f);
        textPaint.setTextSize(primaryTextSize);
        textPaint.getFontMetrics(fontMetrics);
        float baseline = cy + radius * 0.42f - (fontMetrics.ascent + fontMetrics.descent) / 2f;

        String mainText = userLabel.isEmpty() ? (currentState == STATE_CONNECTED ? "CONNECTED" : "TAP TO SECURE") : userLabel;
        canvas.drawText(mainText, cx, baseline, textPaint);

        float subTextSize = Math.max(10f, radius * 0.07f);
        subTextPaint.setTextSize(subTextSize);
        String subText = (currentState == STATE_CONNECTED) ? "TAP TO DISCONNECT" : "TAP TO SECURE";
        canvas.drawText(subText, cx, baseline + radius * 0.18f, subTextPaint);

        canvas.restore();
    }

    private Path buildOrganicPath(float cx, float cy, float radius, float timeSeconds) {
        bodyPath.reset();
        int points = 24;
        float[] xs = new float[points];
        float[] ys = new float[points];

        for (int i = 0; i < points; i++) {
            double angle = -Math.PI / 2 + i * Math.PI * 2 / points;
            float wave = 1f + 0.018f * (float) Math.sin(i * 3.0f + timeSeconds * 0.8f * Math.PI);
            xs[i] = cx + radius * wave * (float) Math.cos(angle);
            ys[i] = cy + radius * wave * (float) Math.sin(angle);
        }

        bodyPath.moveTo((xs[0] + xs[points - 1]) * 0.5f, (ys[0] + ys[points - 1]) * 0.5f);
        for (int i = 0; i < points; i++) {
            int next = (i + 1) % points;
            bodyPath.quadTo(xs[i], ys[i], (xs[i] + xs[next]) * 0.5f, (ys[i] + ys[next]) * 0.5f);
        }
        bodyPath.close();
        return bodyPath;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startTouchAnimation(0.92f);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                startTouchAnimation(1f);
                break;
        }
        return super.onTouchEvent(event);
    }

    private void startTouchAnimation(float target) {
        if (touchAnimator != null) touchAnimator.cancel();
        touchAnimator = ValueAnimator.ofFloat(touchScale, target);
        touchAnimator.setDuration(140);
        touchAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        touchAnimator.addUpdateListener(a -> {
            touchScale = (Float) a.getAnimatedValue();
            invalidate();
        });
        touchAnimator.start();
    }

    private void startRenderLoop() {
        stopRenderLoop();
        renderLoopAnimator = ValueAnimator.ofFloat(0f, 1f);
        renderLoopAnimator.setDuration(Long.MAX_VALUE);
        renderLoopAnimator.addUpdateListener(animation -> invalidate());
        renderLoopAnimator.start();
    }

    private void stopRenderLoop() {
        if (renderLoopAnimator != null) {
            renderLoopAnimator.cancel();
            renderLoopAnimator = null;
        }
        if (explosionAnimator != null) {
            explosionAnimator.cancel();
            explosionAnimator = null;
        }
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); startRenderLoop(); }
    @Override protected void onDetachedFromWindow() { stopRenderLoop(); super.onDetachedFromWindow(); }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) startRenderLoop(); else stopRenderLoop();
    }

    private static int lighten(int color, float amount) {
        int r = Math.min(255, (int) (Color.red(color) + (255 - Color.red(color)) * amount));
        int g = Math.min(255, (int) (Color.green(color) + (255 - Color.green(color)) * amount));
        int b = Math.min(255, (int) (Color.blue(color) + (255 - Color.blue(color)) * amount));
        return Color.rgb(r, g, b);
    }

    private static int adjustAlpha(int color, float factor) {
        int alpha = Math.round(Color.alpha(color) * factor);
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        return Color.argb(alpha, r, g, b);
    }
}