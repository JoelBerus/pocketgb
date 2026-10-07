package com.joelbermudez.pocketgb.video

import android.content.Context
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.joelbermudez.pocketgb.emulator.EmulatorSession
import com.joelbermudez.pocketgb.emulator.ScaleMode

/** Destino de la superficie (la sesión real; las pruebas lo envuelven para contar adjuntos). */
internal interface SurfaceSink {
    fun attach(surface: Surface)
    fun detach()
    fun applyScale(mode: ScaleMode)
}

private class SessionSink(private val session: EmulatorSession) : SurfaceSink {
    override fun attach(surface: Surface) = session.attachSurface(surface)
    override fun detach() = session.detachSurface()
    override fun applyScale(mode: ScaleMode) = session.setScaleMode(mode)
}

private class SessionSurfaceView(
    context: Context,
    private val sink: SurfaceSink,
) : SurfaceView(context), SurfaceHolder.Callback {
    /** Hay una superficie adjunta a la sesión: nunca se adjunta una segunda sin soltar la primera (A7 invariante 4). */
    private var attached = false

    init {
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        attach(holder)
    }

    /**
     * Rotar o cambiar de tamaño sin recrear la actividad (`configChanges`, A7 R12) llega aquí. El nativo calcula el
     * viewport con el tamaño real del búfer en cada fotograma; basta con reaplicar el escalado vigente (K3) para que se
     * recalcule con el modo del usuario. Si el fabricante destruyó y recreó la superficie, `surfaceCreated` ya la adjuntó.
     */
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (!attached) attach(holder)
        scaleMode?.let(sink::applyScale)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        detach()
    }

    fun release() {
        holder.removeCallback(this)
        detach()
    }

    var scaleMode: ScaleMode? = null

    private fun attach(holder: SurfaceHolder) {
        if (attached) detach()
        sink.attach(holder.surface)
        attached = true
    }

    private fun detach() {
        if (!attached) return
        attached = false
        sink.detach()
    }
}

@Composable
fun GameSurface(
    session: EmulatorSession,
    modifier: Modifier = Modifier,
    /** Escalado; `null` deja el que tenga la sesión. Se aplica en caliente (K3). */
    scaleMode: ScaleMode? = null,
) {
    val sink = remember(session) { SessionSink(session) }
    GameSurface(sink, modifier, scaleMode)
}

@Composable
internal fun GameSurface(sink: SurfaceSink, modifier: Modifier = Modifier, scaleMode: ScaleMode? = null) {
    LaunchedEffect(sink, scaleMode) { scaleMode?.let(sink::applyScale) }
    AndroidView(
        factory = { context -> SessionSurfaceView(context, sink) },
        update = { it.scaleMode = scaleMode },
        modifier = modifier,
        onRelease = SessionSurfaceView::release,
    )
}
