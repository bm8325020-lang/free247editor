package clipfactory.app

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import clipfactory.core.Box
import clipfactory.core.Layout
import clipfactory.core.LayoutMath

/**
 * One GPU pass that turns the horizontal frame into the 1080x1920 vertical layout:
 *  - split:  webcam box stretched over the top panel, gameplay box below, thin dark seam;
 *  - game:   gameplay band zoomed in the middle, blurred and darkened cover copy behind it;
 *  - follow: a 9:16 crop.
 * Image coordinates in uniforms have their origin at the top-left; GL textures have it at the bottom-left.
 */
@OptIn(UnstableApi::class)
class LayoutEffect(private val layout: Layout, private val srcW: Int, private val srcH: Int) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        Program(layout, srcW.toFloat() / srcH, useHdr)

    private class Program(private val layout: Layout, private val srcAspect: Float, useHdr: Boolean) :
        BaseGlShaderProgram(useHdr, 1) {

        private val program = GlProgram(VERTEX, FRAGMENT).also {
            it.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(LayoutMath.OUT_W, LayoutMath.OUT_H)

        private fun vec(b: Box) = floatArrayOf(b.x.toFloat(), b.y.toFloat(), b.w.toFloat(), b.h.toFloat())

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            val outAspect = LayoutMath.OUT_W.toFloat() / LayoutMath.OUT_H
            var mode = 0f
            var cam = floatArrayOf(0f, 0f, 1f, 1f)
            var game = floatArrayOf(0f, 0f, 1f, 1f)
            var panel = 0.36f
            var zoom = 1.3f
            when (layout) {
                is Layout.Split -> {
                    mode = 0f; cam = vec(layout.cam); game = vec(layout.game); panel = layout.panel.toFloat()
                }
                is Layout.Game -> {
                    mode = 1f; zoom = layout.zoom.toFloat()
                }
                is Layout.Follow -> {
                    mode = 2f; cam = vec(layout.crop)
                }
            }
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setFloatUniform("uMode", mode)
            program.setFloatsUniform("uCam", cam)
            program.setFloatsUniform("uGame", game)
            program.setFloatUniform("uPanel", panel)
            program.setFloatUniform("uZoom", zoom)
            program.setFloatUniform("uSrcAspect", srcAspect)
            program.setFloatUniform("uOutAspect", outAspect)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        }

        override fun release() {
            super.release()
            program.delete()
        }
    }

    companion object {
        private const val VERTEX = """
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
}
"""
        private const val FRAGMENT = """
precision highp float;
uniform sampler2D uTexSampler;
uniform float uMode;
uniform vec4 uCam;
uniform vec4 uGame;
uniform float uPanel;
uniform float uZoom;
uniform float uSrcAspect;
uniform float uOutAspect;
varying vec2 vTexSamplingCoord;

// p: source image coordinates, origin top-left
vec4 img(vec2 p) {
  p = clamp(p, vec2(0.0), vec2(1.0));
  return texture2D(uTexSampler, vec2(p.x, 1.0 - p.y));
}

vec4 blurred(vec2 p) {
  vec4 c = vec4(0.0);
  float r = 0.035;
  c += img(p);
  c += img(p + vec2( r, 0.0)) + img(p + vec2(-r, 0.0)) + img(p + vec2(0.0,  r)) + img(p + vec2(0.0, -r));
  c += img(p + vec2( r,  r) * 0.7) + img(p + vec2(-r,  r) * 0.7) + img(p + vec2( r, -r) * 0.7) + img(p + vec2(-r, -r) * 0.7);
  c += img(p + vec2( r * 2.0, 0.0)) + img(p + vec2(-r * 2.0, 0.0)) + img(p + vec2(0.0,  r * 2.0)) + img(p + vec2(0.0, -r * 2.0));
  return c / 13.0;
}

void main() {
  vec2 o = vec2(vTexSamplingCoord.x, 1.0 - vTexSamplingCoord.y); // output, origin top-left
  vec4 col;
  if (uMode < 0.5) {
    if (o.y < uPanel) {
      col = img(uCam.xy + vec2(o.x, o.y / uPanel) * uCam.zw);
    } else {
      col = img(uGame.xy + vec2(o.x, (o.y - uPanel) / (1.0 - uPanel)) * uGame.zw);
    }
    if (abs(o.y - uPanel) < 3.0 / 1920.0) col = vec4(0.0, 0.0, 0.0, 1.0);
  } else if (uMode < 1.5) {
    float band = uZoom * uOutAspect / uSrcAspect; // gameplay band height, fraction of output height
    float top = 0.5 - band * 0.5;
    if (o.y >= top && o.y <= top + band) {
      col = img(vec2(0.5 + (o.x - 0.5) / uZoom, (o.y - top) / band));
    } else {
      vec2 p = vec2(0.5 + (o.x - 0.5) * uOutAspect / uSrcAspect, o.y);
      col = vec4(blurred(p).rgb * 0.55, 1.0);
    }
  } else {
    col = img(uCam.xy + o * uCam.zw);
  }
  gl_FragColor = vec4(col.rgb, 1.0);
}
"""
    }
}
