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
class LayoutEffect(
    private val layout: Layout, private val srcW: Int, private val srcH: Int,
    /** stacked layout: webcam on screen, one value per half second of the clip (null = always) */
    private val camOn: BooleanArray? = null,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        Program(layout, srcW.toFloat() / srcH, camOn, useHdr)

    private class Program(
        private val layout: Layout, private val srcAspect: Float, private val camOn: BooleanArray?, useHdr: Boolean,
    ) : BaseGlShaderProgram(useHdr, 1) {
        private var baseUs = Long.MIN_VALUE

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
            var pip = floatArrayOf(0f, 0f, 0f, 0f)
            var round = 0f
            when (layout) {
                is Layout.Stack -> {
                    mode = 4f; cam = vec(layout.cam); game = vec(layout.game); panel = layout.top.toFloat()
                    pip = floatArrayOf(layout.bandY.toFloat(), layout.bandH.toFloat(), 0f, 0f)
                }
                is Layout.Pip -> {
                    mode = 3f; cam = vec(layout.src); game = vec(layout.game); pip = vec(layout.dst)
                    round = if (layout.round) 1f else 0f
                }
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
            if (baseUs == Long.MIN_VALUE) baseUs = presentationTimeUs
            val idx = ((presentationTimeUs - baseUs) / 500_000L).toInt()
            val on = camOn == null || camOn.isEmpty() || camOn[idx.coerceIn(0, camOn.size - 1)]
            program.setFloatUniform("uCamOn", if (on) 1f else 0f)
            program.setFloatsUniform("uPip", pip)
            program.setFloatUniform("uRound", round)
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
uniform vec4 uPip;   // webcam window in the output (fractions, origin top-left)
uniform float uRound;
uniform float uCamOn;  // stacked layout: 0 while the webcam is off screen (the top keeps the blurred background)
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
  } else if (uMode < 2.5) {
    col = img(uCam.xy + o * uCam.zw);
  } else if (uMode < 3.5) {
    // picture-in-picture: full-screen gameplay, the whole webcam small in the corner, in its own shape
    col = img(uGame.xy + o * uGame.zw);
    vec2 q = (o - uPip.xy) / uPip.zw;
    if (q.x >= 0.0 && q.x <= 1.0 && q.y >= 0.0 && q.y <= 1.0) {
      vec2 size = uPip.zw * vec2(1080.0, 1920.0);          // window size in output pixels
      float bw = max(3.0, floor(size.x / 90.0));            // white border
      vec4 cam = img(uCam.xy + q * uCam.zw);
      if (uRound > 0.5) {
        float rad = min(size.x, size.y) * 0.5;
        float d = length((q - 0.5) * size);
        col = mix(col, cam, clamp(rad - d, 0.0, 1.0));      // anti-aliased circle
        float ring = clamp(bw * 0.5 - abs(d - (rad - bw * 0.5)), 0.0, 1.0);
        col = mix(col, vec4(1.0), ring);
      } else {
        vec2 px = q * size;
        float edge = min(min(px.x, size.x - px.x), min(px.y, size.y - px.y));
        col = edge < bw ? vec4(1.0) : cam;
      }
    }
  } else {
    // stacked (videos): webcam across the top, wide gameplay band (uPip.x = band top, uPip.y = band height),
    // blurred gameplay underneath for the captions
    if (o.y < uPanel && uCamOn > 0.5) {
      col = img(uCam.xy + vec2(o.x, o.y / uPanel) * uCam.zw);
    } else if (o.y >= uPip.x && o.y < uPip.x + uPip.y) {
      col = img(uGame.xy + vec2(o.x, (o.y - uPip.x) / uPip.y) * uGame.zw);
    } else {
      float gAspect = (uGame.z * uSrcAspect) / uGame.w;
      vec2 p = vec2(0.5 + (o.x - 0.5) * uOutAspect / gAspect, o.y);
      col = vec4(blurred(uGame.xy + p * uGame.zw).rgb * 0.5, 1.0);
    }
    if (uCamOn > 0.5 && abs(o.y - uPanel) < 3.0 / 1920.0) col = vec4(0.0, 0.0, 0.0, 1.0);
  }
  gl_FragColor = vec4(col.rgb, 1.0);
}
"""
    }
}
