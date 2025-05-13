/**
 * CustomButton - Custom button with gradient background and visual feedback
 *
 * Features gradient background, custom fonts, and press animations.
 * Designed for a cohesive look across the app.
 */
package com.example.myproject;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import androidx.core.content.res.ResourcesCompat;

public class CustomButton extends View {
    private Paint paintBackground, paintGlow, paintText;
    private RectF rect;
    private boolean isPressed = false;
    private String buttonText;
    private OnClickListener onClickListener;
    private Typeface buttonTypeface;

    public CustomButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs);
    }

    private void init(Context context, AttributeSet attrs) {
        paintGlow = new Paint();
        paintGlow.setStyle(Paint.Style.FILL);
        paintGlow.setAntiAlias(true);

        paintBackground = new Paint();
        paintBackground.setStyle(Paint.Style.FILL);
        paintBackground.setAntiAlias(true);

        paintText = new Paint();
        paintText.setStyle(Paint.Style.FILL);
        paintText.setAntiAlias(true);
        paintText.setTextAlign(Paint.Align.CENTER);

        rect = new RectF();

        TypedArray a = context.getTheme().obtainStyledAttributes(attrs, R.styleable.CustomButton, 0, 0);
        try {
            buttonText = a.getString(R.styleable.CustomButton_buttonText);
            if (buttonText == null) {
                buttonText = "Button";
            }
            paintText.setColor(a.getColor(R.styleable.CustomButton_textColor, 0xFFFFFFFF));
            paintText.setTextSize(a.getDimension(R.styleable.CustomButton_textSize, 50));

            int fontResourceId = a.getResourceId(R.styleable.CustomButton_buttonFont, 0);
            if (fontResourceId != 0) {
                buttonTypeface = ResourcesCompat.getFont(context, fontResourceId);
                paintText.setTypeface(buttonTypeface);
            }
        } finally {
            a.recycle();
        }

        setClickable(true);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        LinearGradient gradient = new LinearGradient(0, 0, w, h,
                new int[]{0x4438241f, 0x4438241f},
                null,
                Shader.TileMode.CLAMP);
        paintBackground.setShader(gradient);

        LinearGradient glowGradient = new LinearGradient(0, 0, w, h,
                new int[]{0xAA38241f, 0xAA38241f},
                null,
                Shader.TileMode.CLAMP);
        paintGlow.setShader(glowGradient);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        rect.set(10, 10, getWidth() - 10, getHeight() - 10);
        paintGlow.setAlpha(isPressed ? 50 : 200);
        canvas.drawRoundRect(rect, 40, 40, paintGlow);

        rect.set(20, 20, getWidth() - 20, getHeight() - 20);
        canvas.drawRoundRect(rect, 40, 40, paintBackground);

        float textX = getWidth() / 2f;
        float textY = getHeight() / 2f - ((paintText.descent() + paintText.ascent()) / 2f);
        canvas.drawText(buttonText, textX, textY, paintText);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                isPressed = true;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                isPressed = false;
                if (onClickListener != null) {
                    onClickListener.onClick(this);
                }
                invalidate();
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                isPressed = false;
                invalidate();
                return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override
    public void setOnClickListener(OnClickListener listener) {
        this.onClickListener = listener;
    }

    public void setButtonText(String text) {
        this.buttonText = text;
        invalidate();
    }

    public void setTextSize(float size) {
        paintText.setTextSize(size);
        invalidate();
    }

    public void setTextColor(int color) {
        paintText.setColor(color);
        invalidate();
    }

    public void setButtonFont(Typeface typeface) {
        this.buttonTypeface = typeface;
        paintText.setTypeface(typeface);
        invalidate();
    }

    public String getButtonText() {
        return buttonText;
    }
}