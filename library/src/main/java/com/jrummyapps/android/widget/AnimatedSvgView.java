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
import android.support.graphics.drawable.ExposedPathParser;
import android.support.v4.view.ViewCompat;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;

import com.jrummyapps.android.animatedsvgview.R;

public class AnimatedSvgView extends View {

  public static final int STATE_NOT_STARTED = 0;
  public static final int STATE_TRACE_STARTED = 1;
  public static final int STATE_FILL_STARTED = 2;
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
  private static final int MARKER_LENGTH_DIP = 16;
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

    mMarkerLength =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, MARKER_LENGTH_DIP, getResources().getDisplayMetrics());

    mTraceColors = new int[1];
    mTraceColors[0] = Color.BLACK;
    mTraceResidueColors = new int[1];
    mTraceResidueColors[0] = 0x32000000;

    if (attrs != null) {
      TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.AnimatedSvgView);
      mViewportWidth = a.getInt(R.styleable.AnimatedSvgView_animatedSvgRawImageWidthPx, 512);
      aspectRatioWidth = a.getInt(R.styleable.AnimatedSvgView_animatedSvgRawImageWidthPx, 512);
      mViewportHeight = a.getInt(R.styleable.AnimatedSvgView_animatedSvgRawImageHeightPx, 512);
      aspectRatioHeight = a.getInt(R.styleable.AnimatedSvgView_animatedSvgRawImageHeightPx, 512);
      mTraceTime = a.getInt(R.styleable.AnimatedSvgView_animatedSvgTraceTimeMs, 2000);
      mTraceTimePerGlyph = a.getInt(R.styleable.AnimatedSvgView_animatedSvgTraceTimePerGlyphMs, 1000);
      mFillStart = a.getInt(R.styleable.AnimatedSvgView_animatedSvgFillStartMs, 1200);
      mFillTime = a.getInt(R.styleable.AnimatedSvgView_animatedSvgFillTimeMs, 1000);
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

  public void setViewportSize(float viewportWidth, float viewportHeight) {
    mViewportWidth = viewportWidth;
    mViewportHeight = viewportHeight;
    aspectRatioWidth = viewportWidth;
    aspectRatioHeight = viewportHeight;
    mViewport = new PointF(mViewportWidth, mViewportHeight);
    requestLayout();
  }

  public void setGlyphStrings(String... glyphStrings) {
    mGlyphStrings = glyphStrings;
  }

  public void setTraceResidueColors(int[] traceResidueColors) {
    mTraceResidueColors = traceResidueColors;
  }

  public void setTraceColors(int[] traceColors) {
    mTraceColors = traceColors;
  }

  public void setFillColors(int[] fillColors) {
    mFillColors = fillColors;
  }

  public void setTraceResidueColor(int color) {
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

  public void setTraceColor(int color) {
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

  public void setFillColor(int color) {
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

  public void start() {
    mStartTime = System.currentTimeMillis();
    changeState(STATE_TRACE_STARTED);
    ViewCompat.postInvalidateOnAnimation(this);
  }

  public void reset() {
    mStartTime = 0;
    changeState(STATE_NOT_STARTED);
    ViewCompat.postInvalidateOnAnimation(this);
  }

  public void setToFinishedFrame() {
    mStartTime = 1;
    changeState(STATE_FINISHED);
    ViewCompat.postInvalidateOnAnimation(this);
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

  private void changeState(int state) {
    if (mState == state) {
      return;
    }

    mState = state;
    if (mOnStateChangeListener != null) {
      mOnStateChangeListener.onStateChange(state);
    }
  }

  public int getState() {
    return mState;
  }

  public void setOnStateChangeListener(OnStateChangeListener onStateChangeListener) {
    mOnStateChangeListener = onStateChangeListener;
  }

  public interface OnStateChangeListener {

    void onStateChange(int state);
  }

  private static class GlyphData {

    Path path;
    Paint paint;
    float length;
  }

}