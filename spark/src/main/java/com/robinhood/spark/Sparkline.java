package com.robinhood.spark;

import android.animation.ValueAnimator;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.TypedArray;
import android.database.DataSetObserver;
import android.graphics.Canvas;
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.os.Build;
import android.support.annotation.ColorInt;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A Sparkline is a simplified line chart with no axes.
 */
public class Sparkline extends View {
    // styleable values
    @ColorInt private int lineColor;
    private float lineWidth;
    private float cornerRadius;
    private boolean fill;
    @ColorInt private int baseLineColor;
    private float baseLineWidth;
    @ColorInt private int scrubLineColor;
    private float scrubLineWidth;
    private boolean scrubEnabled;
    private boolean animateChanges;

    // the normalized data
    private final Path renderPath = new Path();
    private final Path path = new Path();
    private final Path baseLinePath = new Path();
    private final Path scrubLinePath = new Path();

    // adapter
    private SparkAdapter adapter;

    // misc fields
    private Paint sparklinePaint;
    private Paint baseLinePaint;
    private Paint scrubLinePaint;
    private OnScrubListener scrubListener;
    private ScrubGestureDetector scrubGestureDetector;
    private List<Float> xPoints;
    private ValueAnimator pathAnimator;
    private RectF contentRect = new RectF();

    private static int shortAnimationTime;

    public Sparkline(Context context) {
        super(context);
        init(context, null, R.attr.spark_SparklineStyle, R.style.spark_Sparkline);
    }

    public Sparkline(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs, R.attr.spark_SparklineStyle, R.style.spark_Sparkline);
    }

    public Sparkline(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs, defStyleAttr, R.style.spark_Sparkline);
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public Sparkline(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init(context, attrs, defStyleAttr, defStyleRes);
    }

    private void init(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.spark_Sparkline,
                defStyleAttr, defStyleRes);
        lineColor = a.getColor(R.styleable.spark_Sparkline_spark_lineColor, 0);
        lineWidth = a.getDimension(R.styleable.spark_Sparkline_spark_lineWidth, 0);
        cornerRadius = a.getDimension(R.styleable.spark_Sparkline_spark_cornerRadius, 0);
        fill = a.getBoolean(R.styleable.spark_Sparkline_spark_fill, false);
        baseLineColor = a.getColor(R.styleable.spark_Sparkline_spark_baseLineColor, 0);
        baseLineWidth = a.getDimension(R.styleable.spark_Sparkline_spark_baseLineWidth, 0);
        scrubEnabled = a.getBoolean(R.styleable.spark_Sparkline_spark_scrubEnabled, true);
        scrubLineColor = a.getColor(R.styleable.spark_Sparkline_spark_scrubLineColor, baseLineColor);
        scrubLineWidth = a.getDimension(R.styleable.spark_Sparkline_spark_scrubLineWidth, lineWidth);
        animateChanges = a.getBoolean(R.styleable.spark_Sparkline_spark_animate, false);
        a.recycle();

        sparklinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        sparklinePaint.setColor(lineColor);
        sparklinePaint.setStrokeWidth(lineWidth);
        sparklinePaint.setStyle(fill ? Paint.Style.FILL : Paint.Style.STROKE);
        sparklinePaint.setStrokeCap(Paint.Cap.ROUND);
        if (cornerRadius != 0) {
            sparklinePaint.setPathEffect(new CornerPathEffect(cornerRadius));
        }

        baseLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        baseLinePaint.setStyle(Paint.Style.STROKE);
        baseLinePaint.setColor(baseLineColor);
        baseLinePaint.setStrokeWidth(baseLineWidth);

        scrubLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        scrubLinePaint.setStyle(Paint.Style.STROKE);
        scrubLinePaint.setStrokeWidth(lineWidth);
        scrubLinePaint.setColor(baseLineColor);
        scrubLinePaint.setStrokeCap(Paint.Cap.ROUND);

        scrubGestureDetector = new ScrubGestureDetector(context) {
            @Override
            public void onScrubbed(float x, float y) {
                if (adapter == null) return;
                if (scrubListener != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                    int index = getNearestIndex(x);
                    if (scrubListener != null) {
                        scrubListener.onScrubbed(adapter.getItem(index));
                    }
                }

                setScrubLine(x);
            }

            @Override
            public void onScrubEnded() {
                scrubLinePath.reset();
                if (scrubListener != null) scrubListener.onScrubbed(null);
                invalidate();
            }
        };
        scrubGestureDetector.setEnabled(scrubEnabled);
        setOnTouchListener(scrubGestureDetector);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        updateContentRect();
        populatePath();
    }

    /**
     * Populates the {@linkplain #path} with points
     */
    private void populatePath() {
        if (adapter == null) return;
        if (getWidth() == 0 || getHeight() == 0) return;

        ScaleHelper scaleHelper = new ScaleHelper(adapter, contentRect, lineWidth);
        int adapterCount = adapter.getCount();

        // xPoints is only used in scrubbing, skip if disabled
        if (scrubEnabled) {
            if (xPoints == null) {
                xPoints = new ArrayList<>(adapterCount);
            } else {
                xPoints.clear();
            }
        }

        // make our main graph path
        path.reset();
        for (int i = 0; i < adapterCount; i++) {
            final float x = scaleHelper.getX(adapter.getX(i));
            final float y = scaleHelper.getY(adapter.getY(i));

            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }

            if (scrubEnabled) {
                xPoints.add(x);
            }
        }

        // if we're filling the graph in, close the path's circuit
        if (fill) {
            float lastX = scaleHelper.getX(adapter.getCount() - 1);
            float bottom = getHeight() - getPaddingBottom();
            // line straight down to the bottom of the view
            path.lineTo(lastX, bottom);
            // line straight left to far edge of the view
            path.lineTo(0, bottom);
            // line straight up to meet the first point
            path.close();
        }

        // make our baseline path
        baseLinePath.reset();
        if (adapter.hasBaseLine()) {
            float scaledBaseLine = scaleHelper.getY(adapter.getBaseLine());
            baseLinePath.moveTo(0, scaledBaseLine);
            baseLinePath.lineTo(getWidth(), scaledBaseLine);
        }

        renderPath.reset();
        renderPath.addPath(path);

        invalidate();
    }

    private void setScrubLine(float x) {
        scrubLinePath.reset();
        scrubLinePath.moveTo(x, getPaddingTop());
        scrubLinePath.lineTo(x, getHeight() - getPaddingBottom());
        invalidate();
    }

    @Override
    public void setPadding(int left, int top, int right, int bottom) {
        super.setPadding(left, top, right, bottom);
        updateContentRect();
        populatePath();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawPath(baseLinePath, baseLinePaint);
        canvas.drawPath(renderPath, sparklinePaint);
        canvas.drawPath(scrubLinePath, scrubLinePaint);
    }

    /**
     * Get the color of the sparkline
     */
    @ColorInt public int getLineColor() {
        return lineColor;
    }

    /**
     * Set the color of the sparkline
     */
    public void setLineColor(@ColorInt int lineColor) {
        this.lineColor = lineColor;
        sparklinePaint.setColor(lineColor);
        invalidate();
    }

    /**
     * Get the width in pixels of the sparkline's stroke
     */
    public float getLineWidth() {
        return lineWidth;
    }

    /**
     * Set the width in pixels of the sparkline's stroke
     */
    public void setLineWidth(float lineWidth) {
        this.lineWidth = lineWidth;
        sparklinePaint.setStrokeWidth(lineWidth);
        invalidate();
    }

    /**
     * Get the corner radius in pixels used when rounding the sparkline's segments.
     */
    public float getCornerRadius() {
        return cornerRadius;
    }

    /**
     * Set the corner radius in pixels to use when rounding the sparkline's segments. Passing 0
     * indicates that corners should not be rounded.
     */
    public void setCornerRadius(float cornerRadius) {
        this.cornerRadius = cornerRadius;
        if (cornerRadius != 0) {
            sparklinePaint.setPathEffect(new CornerPathEffect(cornerRadius));
        } else {
            sparklinePaint.setPathEffect(null);
        }
        invalidate();
    }

    /**
     * Whether or not this view animates changes to its data.
     */
    public boolean getAnimateChanges() {
        return animateChanges;
    }

    /**
     * Whether or not this voiew should animate changes to its data.
     */
    public void setAnimateChanges(boolean animate) {
        this.animateChanges = animate;
    }

    /**
     * Get the {@link Paint} used to draw the scrub line. Any custom modifications to this
     * {@link Paint} will not reflect until the next call to {@link #invalidate()}
     */
    public Paint getScrubLinePaint() {
        return scrubLinePaint;
    }

    /**
     * Set the {@link Paint} to be used to draw the scrub line. Warning: setting a paint other than
     * the instance returned by {@link #getScrubLinePaint()} may result in loss of style attributes
     * specified on this view.
     */
    public void setScrubLinePaint(Paint scrubLinePaint) {
        this.scrubLinePaint = scrubLinePaint;
        invalidate();
    }

    /**
     * Return whether or not this sparkline should fill the area underneath.
     */
    public boolean isFill() {
        return fill;
    }

    /**
     * Set whether or not this sparkline should fill the area underneath.
     */
    public void setFill(boolean fill) {
        if (this.fill != fill) {
            this.fill = fill;
            sparklinePaint.setStyle(fill ? Paint.Style.FILL : Paint.Style.STROKE);
            populatePath();
        }
    }

    /**
     * Get the {@link Paint} used to draw the sparkline. Any modifications to this {@link Paint}
     * will not reflect until the next call to {@link #invalidate()}
     */
    public Paint getSparklinePaint() {
        return sparklinePaint;
    }

    /**
     * Set the {@link Paint} to be used to draw the sparkline. Warning: setting a paint other than
     * the instance returned by {@link #getSparklinePaint()} may result in loss of style attributes
     * specified on this view.
     */
    public void setSparklinePaint(Paint pathPaint) {
        this.sparklinePaint = pathPaint;
        invalidate();
    }

    /**
     * Get the color of the baseline
     */
    @ColorInt public int getBaseLineColor() {
        return baseLineColor;
    }

    /**
     * Set the color of the baseline
     */
    public void setBaseLineColor(@ColorInt int baseLineColor) {
        this.baseLineColor = baseLineColor;
        baseLinePaint.setColor(baseLineColor);
        invalidate();
    }

    /**
     * Get the width in pixels of the baseline's stroke
     */
    public float getBaseLineWidth() {
        return baseLineWidth;
    }

    /**
     * Set the width in pixels of the baseline's stroke
     */
    public void setBaseLineWidth(float baseLineWidth) {
        this.baseLineWidth = baseLineWidth;
        baseLinePaint.setStrokeWidth(baseLineWidth);
        invalidate();
    }

    /**
     * Get the {@link Paint} used to draw the baseline. Any modifications to this {@link Paint}
     * will not reflect until the next call to {@link #invalidate()}
     */
    public Paint getBaseLinePaint() {
        return baseLinePaint;
    }

    /**
     * Set the {@link Paint} to be used to draw the baseline. Warning: setting a paint other than
     * the instance returned by {@link #getBaseLinePaint()} ()} may result in loss of style
     * attributes specified on this view.
     */
    public void setBaseLinePaint(Paint baseLinePaint) {
        this.baseLinePaint = baseLinePaint;
        invalidate();
    }

    /**
     * Get the color of the scrub line
     */
    @ColorInt public int getScrubLineColor() {
        return scrubLineColor;
    }

    /**
     * Set the color of the scrub line
     */
    public void setScrubLineColor(@ColorInt int scrubLineColor) {
        this.scrubLineColor = scrubLineColor;
        scrubLinePaint.setColor(scrubLineColor);
        invalidate();
    }

    /**
     * Get the width in pixels of the scrub line's stroke
     */
    public float getScrubLineWidth() {
        return scrubLineWidth;
    }

    /**
     * Set the width in pixels of the scrub line's stroke
     */
    public void setScrubLineWidth(float scrubLineWidth) {
        this.scrubLineWidth = scrubLineWidth;
        scrubLinePaint.setStrokeWidth(scrubLineWidth);
        invalidate();
    }

    /**
     * Return true if scrubbing is enabled on this view
     */
    public boolean isScrubEnabled() {
        return scrubEnabled;
    }

    /**
     * Set whether or not to enable scrubbing on this view.
     */
    public void setScrubEnabled(boolean scrubbingEnabled) {
        this.scrubEnabled = scrubbingEnabled;
        scrubGestureDetector.setEnabled(scrubbingEnabled);
        invalidate();
    }

    /**
     * Get the current {@link OnScrubListener}
     */
    public OnScrubListener getScrubListener() {
        return scrubListener;
    }

    /**
     * Set a {@link OnScrubListener} to be notified of the user's scrubbing gestures.
     */
    public void setScrubListener(OnScrubListener scrubListener) {
        this.scrubListener = scrubListener;
    }

    /**
     * Get the backing {@link SparkAdapter}
     */
    public SparkAdapter getAdapter() {
        return adapter;
    }

    /**
     * Sets the backing {@link SparkAdapter} to generate the points to be graphed
     */
    public void setAdapter(SparkAdapter adapter) {
        if (this.adapter != null) {
            adapter.unregisterDataSetObserver(dataSetObserver);
        }
        this.adapter = adapter;
        if (this.adapter != null) {
            adapter.registerDataSetObserver(dataSetObserver);
        }
    }

    private void doPathAnimation(float newToOldLengthRatio) {
        if (pathAnimator != null) {
            pathAnimator.cancel();
        }

        if (shortAnimationTime == 0) {
            shortAnimationTime = getResources().getInteger(android.R.integer.config_shortAnimTime);
        }

        final PathMeasure pathMeasure = new PathMeasure(path, false);

        float endLength = pathMeasure.getLength();
        if (endLength == 0) return;

        float start = endLength * newToOldLengthRatio * 0;
        pathAnimator = ValueAnimator.ofFloat(start, endLength);
        pathAnimator.setDuration(shortAnimationTime);
        pathAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float animatedPathLength = (Float) animation.getAnimatedValue();
                renderPath.reset();
                pathMeasure.getSegment(0, animatedPathLength, renderPath, true);
                renderPath.rLineTo(0, 0);

                invalidate();
            }
        });
        pathAnimator.start();
    }

    /**
     * Helper class for handling scaling logic.
     */
    private static class ScaleHelper {
        // the width and height of the view
        final float width, height;
        final int size;
        // the distance in pixels between each X value
        final float xStep;
        // the scale factor for the Y values
        final float xScale, yScale;
        // translates the Y values back into the bounding rect after being scaled
        final float xTranslation, yTranslation;

        public ScaleHelper(SparkAdapter adapter, RectF contentRect, float lineWidth) {
            final float leftPadding = contentRect.left;
            final float topPadding = contentRect.top;

            // subtract lineWidth to offset for 1/2 of the line bleeding out of the content box on
            // either side of the view
            this.width = contentRect.width() - lineWidth;
            this.height = contentRect.height() - lineWidth;

            this.size = adapter.getCount();
            this.xStep = width / (size - 1);

            // calculate min and max values
            boolean hasBaseLine = adapter.hasBaseLine();
            float minY = hasBaseLine ? adapter.getBaseLine() : Float.MAX_VALUE;
            float maxY = hasBaseLine ? minY : Float.MIN_VALUE;
            float minX = Float.MAX_VALUE;
            float maxX = Float.MIN_VALUE;
            for (int i = 0; i < size; i++) {
                final float x = adapter.getX(i);
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);

                final float y = adapter.getY(i);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }

            // xScale will compress or expand the min and max x values to be just inside the view
            this.xScale = width / (maxX - minX);
            // xTranslation will move the x points back between 0 - width
            this.xTranslation = leftPadding - (minX * xScale) + (lineWidth / 2);
            // yScale will compress or expand the min and max y values to be just inside the view
            this.yScale = height / (maxY - minY);
            // yTranslation will move the y points back between 0 - height
            this.yTranslation = minY * yScale + topPadding + (lineWidth / 2);
        }

        /**
         * Given the 'raw' X value, scale it to fit within our view.
         */
        public float getX(float rawX) {
            return rawX * xScale + xTranslation;
        }

        /**
         * Given the 'raw' Y value, scale it to fit within our view. This method also 'flips' the
         * value to be ready for drawing.
         */
        public float getY(float rawY) {
            return height - (rawY * yScale) + yTranslation;
        }
    }

    @Override
    public int getPaddingStart() {
        return Build.VERSION.SDK_INT > Build.VERSION_CODES.JELLY_BEAN_MR1
                ? super.getPaddingStart()
                : getPaddingLeft();
    }

    @Override
    public int getPaddingEnd() {
        return Build.VERSION.SDK_INT > Build.VERSION_CODES.JELLY_BEAN_MR1
                ? super.getPaddingEnd()
                : getPaddingRight();
    }

    /**
     * Gets the rect representing the 'content area' of the view. This is essentially the bounding
     * rect minus any padding.
     */
    private void updateContentRect() {
        contentRect.set(
                getPaddingStart(),
                getPaddingTop(),
                getWidth() - getPaddingEnd(),
                getHeight() - getPaddingBottom()
        );
    }

    /**
     * returns the nearest index (into {@link #adapter}'s data) for the given x coordinate.
     */
    private int getNearestIndex(float x) {
        int index = Collections.binarySearch(xPoints, x);

        // if binary search returns positive, we had an exact match, return that index
        if (index >= 0) return index;

        // otherwise, calculate the binary search's specified insertion index
        index = - 1 - index;

        // if we're inserting at 0, then our guaranteed nearest index is 0
        if (index == 0) return index;

        // if we're inserting at the very end, then our guaranteed nearest index is the final one
        if (index == xPoints.size()) return --index;

        // otherwise we need to check which of our two neighbors we're closer to
        final float deltaUp = xPoints.get(index) - x;
        final float deltaDown = x - xPoints.get(index - 1);
        if (deltaUp > deltaDown) {
            // if the below neighbor is closer, decrement our index
            index--;
        }

        return index;
    }

    /**
     * Listener for a user scrubbing (dragging their finger along) the graph.
     */
    public interface OnScrubListener {
        /**
         * Indicates the user is currently scrubbing over the given value. A null value indicates
         * that the user has stopped scrubbing.
         */
        void onScrubbed(Object value);
    }

    private final DataSetObserver dataSetObserver = new DataSetObserver() {
        int oldCount;

        @Override
        public void onChanged() {
            super.onChanged();
            // detect when we're adding points and animate using path tracing:
            int newCount = adapter.getCount();
            boolean addingPoints = oldCount < newCount;
            float newToOldLengthRatio = (float) oldCount / (float) newCount;

            populatePath();

            if (animateChanges && addingPoints) {
                doPathAnimation(newToOldLengthRatio);
            }
        }

        @Override
        public void onInvalidated() {
            super.onInvalidated();
            renderPath.reset();
            path.reset();
            baseLinePath.reset();
            invalidate();
        }
    };
}

