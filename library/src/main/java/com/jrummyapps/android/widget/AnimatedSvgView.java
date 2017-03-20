/*
 * Copyright (C) 2016 Jared Rummler <jared.rummler@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.jrummyapps.android.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.Build;
import android.support.annotation.ColorInt;
import android.support.annotation.IntDef;
import android.support.annotation.NonNull;
import android.support.graphics.drawable.ExposedPathParser;
import android.support.v4.view.ViewCompat;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import com.jrummyapps.android.animatedsvgview.R;

/**
 * Animated SVG Drawing for Android
 */
public class AnimatedSvgView extends View {

  /** The animation has been reset or hasn't started yet. */
  public static final int STATE_NOT_STARTED = 0;
  /** The SVG is being traced */
  public static final int STATE_TRACE_STARTED = 1;
  /** The SVG has been traced and is now being filled */
  public static final int STATE_FILL_STARTED = 2;
  /** The animation has finished */
  public static final int STATE_FINISHED = 3;

  private static final String TAG = "AnimatedSvgView";

  private static final Interpolator INTERPOLATOR = new DecelerateInterpolator();

  private static float constrain(float min, float max, float v) {
    return Math.max(min, Math.min(max, v));
  }

  private int mTraceTime = 2000;
  private int mTraceTimePerGlyph = 1000;
  private int mFillStart = 1200;
  private int mFillTime = 1000;
  private int[] mTraceResidueColors;
  private int[] mTraceColors;
  private float mViewportWidth;
  private float mViewportHeight;
  private PointF mViewport = new PointF(mViewportWidth, mViewportHeight);
  private float aspectRatioWidth = 1;
  private float aspectRatioHeight = 1;

  private Paint mFillPaint;
  private int[] mFillColors;
  private GlyphData[] mGlyphData;
  private String[] mGlyphStrings;
  private float mMarkerLength;
  private int mWidth;
  private int mHeight;
  private long mStartTime;

  private int mState = STATE_NOT_STARTED;
  private OnStateChangeListener mOnStateChangeListener;

  public AnimatedSvgView(Context context) {
    super(context);
    init(context, null);
  }

  public AnimatedSvgView(Context context, AttributeSet attrs) {
    super(context, attrs);
    init(context, attrs);
  }

  public AnimatedSvgView(Context context, AttributeSet attrs, int defStyle) {
    super(context, attrs, defStyle);
    init(context, attrs);
  }

  private void init(Context context, AttributeSet attrs) {
    mFillPaint = new Paint();
    mFillPaint.setAntiAlias(true);
    mFillPaint.setStyle(Paint.Style.FILL);

    mTraceColors = new int[1];
    mTraceColors[0] = Color.BLACK;
    mTraceResidueColors = new int[1];
    mTraceResidueColors[0] = 0x32000000;

    if (attrs != null) {
      TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.AnimatedSvgView);
      mViewportWidth = a.getInt(R.styleable.AnimatedSvgView_animatedSvgImageSizeX, 512);
      aspectRatioWidth = a.getInt(R.styleable.AnimatedSvgView_animatedSvgImageSizeX, 512);
      mViewportHeight = a.getInt(R.styleable.AnimatedSvgView_animatedSvgImageSizeY, 512);
      aspectRatioHeight = a.getInt(R.styleable.AnimatedSvgView_animatedSvgImageSizeY, 512);
      mTraceTime = a.getInt(R.styleable.AnimatedSvgView_animatedSvgTraceTime, 2000);
      mTraceTimePerGlyph = a.getInt(R.styleable.AnimatedSvgView_animatedSvgTraceTimePerGlyph, 1000);
      mFillStart = a.getInt(R.styleable.AnimatedSvgView_animatedSvgFillStart, 1200);
      mFillTime = a.getInt(R.styleable.AnimatedSvgView_animatedSvgFillTime, 1000);
      int traceMarkerLength = a.getInt(R.styleable.AnimatedSvgView_animatedSvgTraceMarkerLength, 16);
      mMarkerLength =
          TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, traceMarkerLength, getResources().getDisplayMetrics());
      int glyphStringsId = a.getResourceId(R.styleable.AnimatedSvgView_animatedSvgGlyphStrings, 0);
      int traceResidueColorsId = a.getResourceId(R.styleable.AnimatedSvgView_animatedSvgTraceResidueColors, 0);
      int traceColorsId = a.getResourceId(R.styleable.AnimatedSvgView_animatedSvgTraceColors, 0);
      int fillColorsId = a.getResourceId(R.styleable.AnimatedSvgView_animatedSvgFillColors, 0);

      a.recycle();

      if (glyphStringsId != 0) {
        setGlyphStrings(getResources().getStringArray(glyphStringsId));
        setTraceResidueColor(Color.argb(50, 0, 0, 0));
        setTraceColor(Color.BLACK);
      }
      if (traceResidueColorsId != 0) {
        setTraceResidueColors(getResources().getIntArray(traceResidueColorsId));
      }
      if (traceColorsId != 0) {
        setTraceColors(getResources().getIntArray(traceColorsId));
      }
      if (fillColorsId != 0) {
        setFillColors(getResources().getIntArray(fillColorsId));
      }

      mViewport = new PointF(mViewportWidth, mViewportHeight);
    }

    // Note: using a software layer here is an optimization. This view works with hardware accelerated rendering but
    // every time a path is modified (when the dash path effect is modified), the graphics pipeline will rasterize
    // the path again in a new texture. Since we are dealing with dozens of paths, it is much more efficient to
    // rasterize the entire view into a single re-usable texture instead. Ideally this should be toggled using a
    // heuristic based on the number and or dimensions of paths to render.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
      setLayerType(LAYER_TYPE_SOFTWARE, null);
    }
  }

  @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    mWidth = w;
    mHeight = h;
    rebuildGlyphData();
  }

  @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    int width = View.MeasureSpec.getSize(widthMeasureSpec);
    int height = View.MeasureSpec.getSize(heightMeasureSpec);
    int widthMode = View.MeasureSpec.getMode(widthMeasureSpec);
    int heightMode = View.MeasureSpec.getMode(heightMeasureSpec);

    if (height <= 0 && width <= 0 && heightMode == View.MeasureSpec.UNSPECIFIED &&
        widthMode == View.MeasureSpec.UNSPECIFIED) {
      width = 0;
      height = 0;
    } else if (height <= 0 && heightMode == View.MeasureSpec.UNSPECIFIED) {
      height = (int) (width * aspectRatioHeight / aspectRatioWidth);
    } else if (width <= 0 && widthMode == View.MeasureSpec.UNSPECIFIED) {
      width = (int) (height * aspectRatioWidth / aspectRatioHeight);
    } else if (width * aspectRatioHeight > aspectRatioWidth * height) {
      width = (int) (height * aspectRatioWidth / aspectRatioHeight);
    } else {
      height = (int) (width * aspectRatioHeight / aspectRatioWidth);
    }

    super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
        MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
  }

  @SuppressLint("DrawAllocation")
  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    if (mState == STATE_NOT_STARTED || mGlyphData == null) {
      return;
    }

    long t = System.currentTimeMillis() - mStartTime;

    // Draw outlines (starts as traced)
    for (int i = 0; i < mGlyphData.length; i++) {
      float phase = constrain(0, 1,
          (t - (mTraceTime - mTraceTimePerGlyph) * i * 1f / mGlyphData.length) * 1f / mTraceTimePerGlyph);
      float distance = INTERPOLATOR.getInterpolation(phase) * mGlyphData[i].length;
      mGlyphData[i].paint.setColor(mTraceResidueColors[i]);
      mGlyphData[i].paint.setPathEffect(new DashPathEffect(
          new float[]{distance, mGlyphData[i].length}, 0));
      canvas.drawPath(mGlyphData[i].path, mGlyphData[i].paint);

      mGlyphData[i].paint.setColor(mTraceColors[i]);
      mGlyphData[i].paint.setPathEffect(new DashPathEffect(
          new float[]{0, distance, phase > 0 ? mMarkerLength : 0, mGlyphData[i].length}, 0));
      canvas.drawPath(mGlyphData[i].path, mGlyphData[i].paint);
    }

    if (t > mFillStart) {
      if (mState < STATE_FILL_STARTED) {
        changeState(STATE_FILL_STARTED);
      }

      // If after fill start, draw fill
      float phase = constrain(0, 1, (t - mFillStart) * 1f / mFillTime);
      for (int i = 0; i < mGlyphData.length; i++) {
        GlyphData glyphData = mGlyphData[i];
        int fillColor = mFillColors[i];
        int a = (int) (phase * ((float) Color.alpha(fillColor) / (float) 255) * 255);
        int r = Color.red(fillColor);
        int g = Color.green(fillColor);
        int b = Color.blue(fillColor);
        mFillPaint.setARGB(a, r, g, b);
        canvas.drawPath(glyphData.path, mFillPaint);
      }
    }

    if (t < mFillStart + mFillTime) {
      // draw next frame if animation isn't finished
      ViewCompat.postInvalidateOnAnimation(this);
    } else {
      changeState(STATE_FINISHED);
    }
  }

  /**
   * If you set the SVG data paths more than once using {@link #setGlyphStrings(String...)} you should call this method
   * before playing the animation.
   */
  @SuppressWarnings("SuspiciousNameCombination")
  public void rebuildGlyphData() {

    float X = mWidth / mViewport.x;
    float Y = mHeight / mViewport.y;

    Matrix scaleMatrix = new Matrix();
    RectF outerRect = new RectF(X, X, Y, Y);
    scaleMatrix.setScale(X, Y, outerRect.centerX(), outerRect.centerY());

    mGlyphData = new GlyphData[mGlyphStrings.length];
    for (int i = 0; i < mGlyphStrings.length; i++) {
      mGlyphData[i] = new GlyphData();
      try {
        mGlyphData[i].path = ExposedPathParser.createPathFromPathData(mGlyphStrings[i]);
        mGlyphData[i].path.transform(scaleMatrix);
      } catch (Exception e) {
        mGlyphData[i].path = new Path();
        Log.e(TAG, "Couldn't parse path", e);
      }
      PathMeasure pm = new PathMeasure(mGlyphData[i].path, true);
      while (true) {
        mGlyphData[i].length = Math.max(mGlyphData[i].length, pm.getLength());
        if (!pm.nextContour()) {
          break;
        }
      }
      mGlyphData[i].paint = new Paint();
      mGlyphData[i].paint.setStyle(Paint.Style.STROKE);
      mGlyphData[i].paint.setAntiAlias(true);
      mGlyphData[i].paint.setColor(Color.WHITE);
      mGlyphData[i].paint.setStrokeWidth(
          TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1, getResources().getDisplayMetrics()));
    }
  }

  /**
   * Set the viewport width and height of the SVG. This can be found in the viewBox in the SVG. This is not the size
   * of the view.
   *
   * @param viewportWidth
   *     the width
   * @param viewportHeight
   *     the height
   */
  public void setViewportSize(float viewportWidth, float viewportHeight) {
    mViewportWidth = viewportWidth;
    mViewportHeight = viewportHeight;
    aspectRatioWidth = viewportWidth;
    aspectRatioHeight = viewportHeight;
    mViewport = new PointF(mViewportWidth, mViewportHeight);
    requestLayout();
  }

  /**
   * Set the SVG path data.
   *
   * @param glyphStrings
   *     The path strings found in the SVG.
   */
  public void setGlyphStrings(@NonNull String... glyphStrings) {
    mGlyphStrings = glyphStrings;
  }

  /**
   * Set the colors used during tracing the SVG
   *
   * @param traceResidueColors
   *     the colors. Should be the same length as the SVG paths.
   */
  public void setTraceResidueColors(@NonNull int[] traceResidueColors) {
    mTraceResidueColors = traceResidueColors;
  }

  /**
   * Set the colors used to trace the SVG.
   *
   * @param traceColors
   *     The colors. Should be the same length as the SVG paths.
   */
  public void setTraceColors(@NonNull int[] traceColors) {
    mTraceColors = traceColors;
  }

  /**
   * Set the colors for the SVG. This corresponds with each data path.
   *
   * @param fillColors
   *     The colors for each SVG data path.
   */
  public void setFillColors(@NonNull int[] fillColors) {
    mFillColors = fillColors;
  }

  /**
   * Set the color used for tracing. This will be applied to all data paths.
   *
   * @param color
   *     The color
   */
  public void setTraceResidueColor(@ColorInt int color) {
    if (mGlyphStrings == null) {
      throw new RuntimeException("You need to set the glyphs first.");
    }
    int length = mGlyphStrings.length;
    int[] colors = new int[length];
    for (int i = 0; i < length; i++) {
      colors[i] = color;
    }
    setTraceResidueColors(colors);
  }

  /**
   * Set the color used for tracing. This will be applied to all data paths.
   *
   * @param color
   *     The color
   */
  public void setTraceColor(@ColorInt int color) {
    if (mGlyphStrings == null) {
      throw new RuntimeException("You need to set the glyphs first.");
    }
    int length = mGlyphStrings.length;
    int[] colors = new int[length];
    for (int i = 0; i < length; i++) {
      colors[i] = color;
    }
    setTraceColors(colors);
  }

  /**
   * Set the color used for the icon. This will apply the color to all SVG data paths.
   *
   * @param color
   *     The color
   */
  public void setFillColor(@ColorInt int color) {
    if (mGlyphStrings == null) {
      throw new RuntimeException("You need to set the glyphs first.");
    }
    int length = mGlyphStrings.length;
    int[] colors = new int[length];
    for (int i = 0; i < length; i++) {
      colors[i] = color;
    }
    setFillColors(colors);
  }

  /**
   * Set the animation trace time
   *
   * @param traceTime
   *     time in milliseconds
   */
  public void setTraceTime(int traceTime) {
    mTraceTime = traceTime;
  }

  /**
   * Set the time used to trace each glyph
   *
   * @param traceTimePerGlyph
   *     time in milliseconds
   */
  public void setTraceTimePerGlyph(int traceTimePerGlyph) {
    mTraceTimePerGlyph = traceTimePerGlyph;
  }

  /**
   * Set the time at which colors will start being filled after the tracing begins
   *
   * @param fillStart
   *     time in milliseconds
   */
  public void setFillStart(int fillStart) {
    mFillStart = fillStart;
  }

  /**
   * Set the time it takes to fill colors
   *
   * @param fillTime
   *     time in milliseconds
   */
  public void setFillTime(int fillTime) {
    mFillTime = fillTime;
  }

  /**
   * Start the animation
   */
  public void start() {
    mStartTime = System.currentTimeMillis();
    changeState(STATE_TRACE_STARTED);
    ViewCompat.postInvalidateOnAnimation(this);
  }

  /**
   * Reset the animation
   */
  public void reset() {
    mStartTime = 0;
    changeState(STATE_NOT_STARTED);
    ViewCompat.postInvalidateOnAnimation(this);
  }

  /**
   * Draw the SVG, skipping any animation.
   */
  public void setToFinishedFrame() {
    mStartTime = 1;
    changeState(STATE_FINISHED);
    ViewCompat.postInvalidateOnAnimation(this);
  }

  /**
   * Get the animation state.
   *
   * @return Either {{@link #STATE_NOT_STARTED},
   * {@link #STATE_TRACE_STARTED}},
   * {@link #STATE_FILL_STARTED} or
   * {@link #STATE_FINISHED}
   */
  @State public int getState() {
    return mState;
  }

  /**
   * Get notified about the animation states.
   *
   * @param onStateChangeListener
   *     The {@link OnStateChangeListener}
   */
  public void setOnStateChangeListener(OnStateChangeListener onStateChangeListener) {
    mOnStateChangeListener = onStateChangeListener;
  }

  private void changeState(@State int state) {
    if (mState == state) {
      return;
    }

    mState = state;
    if (mOnStateChangeListener != null) {
      mOnStateChangeListener.onStateChange(state);
    }
  }

  /**
   * Callback for listening to animation state changes
   */
  public interface OnStateChangeListener {

    /**
     * Called when the animation state changes.
     *
     * @param state
     *     The state of the animation.
     *     Either {{@link #STATE_NOT_STARTED},
     *     {@link #STATE_TRACE_STARTED}},
     *     {@link #STATE_FILL_STARTED} or
     *     {@link #STATE_FINISHED}
     */
    void onStateChange(@State int state);
  }

  @IntDef({STATE_NOT_STARTED, STATE_TRACE_STARTED, STATE_FILL_STARTED, STATE_FINISHED})
  public @interface State {
  }

  static final class GlyphData {
    Path path;
    Paint paint;
    float length;

  }

}