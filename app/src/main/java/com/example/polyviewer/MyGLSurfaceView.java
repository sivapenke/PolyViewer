package com.example.polyviewer;
import android.content.Context;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;

/**
 * Surface view that renders our scene.
 */
public class MyGLSurfaceView extends GLSurfaceView {
  // The renderer responsible for rendering the contents of this view.
  private final MyGLRenderer renderer;

  public MyGLSurfaceView(Context context) {
    this(context, null);
  }

  public MyGLSurfaceView(Context context, AttributeSet attributeSet) {
    super(context, attributeSet);
    // We want OpenGL ES 2.
    setEGLContextClientVersion(2);
    renderer = new MyGLRenderer();
    setRenderer(renderer);
  }

  public MyGLRenderer getRenderer() {
    return renderer;
  }
}
