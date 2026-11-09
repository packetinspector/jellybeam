package tv.jellybeam.visualizer.lab

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES30
import android.os.Debug
import android.os.HandlerThread
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * docs/30 §9 T23 control: native heap growth from the platform alone when a thread creates an ES 3
 * context on a pbuffer, compiles one shader program and tears everything down. Logged next to the
 * engine's own number, so a driver-side cost isn't read as the engine's leak.
 */
@RunWith(AndroidJUnit4::class)
class EglBaselineTest {
    private fun cycle() {
        val t = HandlerThread("egl-baseline").apply { start() }
        val done = java.util.concurrent.CountDownLatch(1)
        android.os.Handler(t.looper).post {
            val d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            EGL14.eglInitialize(d, IntArray(1), 0, IntArray(1), 0)
            val configs = arrayOfNulls<EGLConfig>(1)
            EGL14.eglChooseConfig(d, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, 0x40, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE), 0, configs, 0, 1, IntArray(1), 0)
            val ctx = EGL14.eglCreateContext(d, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            val surf = EGL14.eglCreatePbufferSurface(d, configs[0], intArrayOf(EGL14.EGL_WIDTH, 64, EGL14.EGL_HEIGHT, 64, EGL14.EGL_NONE), 0)
            EGL14.eglMakeCurrent(d, surf, surf, ctx)
            val vs = GLES30.glCreateShader(GLES30.GL_VERTEX_SHADER)
            GLES30.glShaderSource(vs, "#version 300 es\nvoid main(){gl_Position=vec4(0.0);}")
            GLES30.glCompileShader(vs)
            val fs = GLES30.glCreateShader(GLES30.GL_FRAGMENT_SHADER)
            GLES30.glShaderSource(fs, "#version 300 es\nprecision mediump float;out vec4 o;void main(){o=vec4(1.0);}")
            GLES30.glCompileShader(fs)
            val p = GLES30.glCreateProgram()
            GLES30.glAttachShader(p, vs)
            GLES30.glAttachShader(p, fs)
            GLES30.glLinkProgram(p)
            GLES30.glDeleteShader(vs)
            GLES30.glDeleteShader(fs)
            GLES30.glDeleteProgram(p)
            EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(d, surf)
            EGL14.eglDestroyContext(d, ctx)
            EGL14.eglReleaseThread()
            done.countDown()
        }
        done.await()
        t.quitSafely()
        t.join()
    }

    @Test
    fun bareContextCyclesBaseline() {
        repeat(20) { cycle() }
        System.gc()
        System.runFinalization()
        val heap0 = Debug.getNativeHeapAllocatedSize()
        repeat(100) { cycle() }
        System.gc()
        System.runFinalization()
        Log.i("VizLeak", "bare EGL baseline growth over 100 cycles: ${Debug.getNativeHeapAllocatedSize() - heap0}")
    }
}
