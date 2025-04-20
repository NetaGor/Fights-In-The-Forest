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

/**
 * CustomButton - A styled custom button implementation
 *
 * This class provides a custom button with gradient background, glowing effect,
 * custom typeface support, and visual feedback on press. It extends the View
 * class and implements the full touch interaction cycle.
 */
public class CustomButton extends View {
    private Paint paintBackground, paintGlow, paintText;  // Paint objects for drawing
    private RectF rect;                                   // Rectangle for button shape
    private boolean isPressed = false;                    // Button press state
    private String buttonText;                            // Text to display on button
    private OnClickListener onClickListener;              // Click listener
    private Typeface buttonTypeface;                      // Custom font

    /**
     * Constructor for creating a button from XML layout
     *
     * @param context The application context
     * @param attrs XML attributes from layout
     */
    public CustomButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs);
    }

    /**
     * Initializes the button properties and styles
     *
     * @param context The application context
     * @param attrs XML attributes from layout
     */
    private void init(Context context, AttributeSet attrs) {
        // Initialize paint objects for drawing
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

        // Extract custom attributes from XML
        TypedArray a = context.getTheme().obtainStyledAttributes(attrs, R.styleable.CustomButton, 0, 0);
        try {
            buttonText = a.getString(R.styleable.CustomButton_buttonText);
            if (buttonText == null) {
                buttonText = "Button";  // Default text
            }
            paintText.setColor(a.getColor(R.styleable.CustomButton_textColor, 0xFFFFFFFF));
            paintText.setTextSize(a.getDimension(R.styleable.CustomButton_textSize, 50));

            // Load custom font if specified
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

    /**
     * Called when the size of the button changes
     * Sets up the gradient paints for background and glow
     */
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        // Create background gradient
        LinearGradient gradient = new LinearGradient(0, 0, w, h,
                new int[]{0x4438241f, 0x4438241f},
                null,
                Shader.TileMode.CLAMP);
        paintBackground.setShader(gradient);

        // Create glow gradient
        LinearGradient glowGradient = new LinearGradient(0, 0, w, h,
                new int[]{0xAA38241f, 0xAA38241f},
                null,
                Shader.TileMode.CLAMP);
        paintGlow.setShader(glowGradient);
    }

    /**
     * Draws the button with its background, glow effect, and text
     *
     * @param canvas The canvas to draw on
     */
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // Draw the glow around the button
        rect.set(10, 10, getWidth() - 10, getHeight() - 10);
        paintGlow.setAlpha(isPressed ? 50 : 200);  // Change alpha based on press state
        canvas.drawRoundRect(rect, 40, 40, paintGlow);

        // Draw the button background
        rect.set(20, 20, getWidth() - 20, getHeight() - 20);
        canvas.drawRoundRect(rect, 40, 40, paintBackground);

        // Draw the button text
        float textX = getWidth() / 2f;
        float textY = getHeight() / 2f - ((paintText.descent() + paintText.ascent()) / 2f);
        canvas.drawText(buttonText, textX, textY, paintText);
    }

    /**
     * Handles touch events on the button
     *
     * @param event The motion event
     * @return true if the event was handled, false otherwise
     */
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

    /**
     * Called when the button is clicked
     *
     * @return true since the click was handled
     */
    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    /**
     * Sets a click listener for the button
     *
     * @param listener The click listener to set
     */
    @Override
    public void setOnClickListener(OnClickListener listener) {
        this.onClickListener = listener;
    }

    /**
     * Sets the text displayed on the button
     *
     * @param text The text to display
     */
    public void setButtonText(String text) {
        this.buttonText = text;
        invalidate();
    }

    /**
     * Sets the text size for the button
     *
     * @param size The text size in pixels
     */
    public void setTextSize(float size) {
        paintText.setTextSize(size);
        invalidate();
    }

    /**
     * Sets the text color for the button
     *
     * @param color The color value (ARGB)
     */
    public void setTextColor(int color) {
        paintText.setColor(color);
        invalidate();
    }

    /**
     * Sets the custom typeface for the button text
     *
     * @param typeface The typeface to use
     */
    public void setButtonFont(Typeface typeface) {
        this.buttonTypeface = typeface;
        paintText.setTypeface(typeface);
        invalidate();
    }

    /**
     * Gets the current button text
     *
     * @return The button text
     */
    public String getButtonText() {
        return buttonText;
    }
}