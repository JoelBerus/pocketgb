package com.joelbermudez.pocketgb.video

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.joelbermudez.pocketgb.emulator.EmulatorSession

private class SessionSurfaceView(
    context: Context,
    private val session: EmulatorSession,
) : SurfaceView(context), SurfaceHolder.Callback {
    init {
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        session.attachSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        session.detachSurface()
    }

    fun release() {
        holder.removeCallback(this)
        session.detachSurface()
    }
}

@Composable
fun GameSurface(
    session: EmulatorSession,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context -> SessionSurfaceView(context, session) },
        modifier = modifier,
        onRelease = SessionSurfaceView::release,
    )
}
