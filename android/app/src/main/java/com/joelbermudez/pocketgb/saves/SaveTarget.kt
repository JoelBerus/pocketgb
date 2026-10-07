package com.joelbermudez.pocketgb.saves

/**
 * Destino de los guardados de una sesión: la copia local (obligatoria, síncrona para el llamador) y el
 * espejo (opcional) con su propio canal serie. El espejo nunca bloquea un flush local ni lo hace fallar.
 *
 * @param mirrorPending el espejo falta o está desfasado: se marca para reintentar (`retryMirrorIfNeeded`).
 * @param mirrorWriter inyección para probar un proveedor lento o bloqueado; por defecto `mirror.write`.
 *   Sin espejo no hay canal: un escritor inyectado nunca escribe un espejo que la sesión decidió no tocar.
 */
class SaveTarget(
    val local: SaveStore,
    val mirror: SaveMirror?,
    mirrorPending: Boolean = false,
    mirrorWriter: ((ByteArray) -> Long?)? = null,
    registry: MirrorChannelRegistry = MirrorChannelRegistry.shared,
) {
    private val writer: ((ByteArray) -> Long?)? = if (mirror == null) null else mirrorWriter ?: { data -> mirror.write(data) }
    private val channel: MirrorChannel? = if (mirror == null) null else registry.channel(local.fingerprint)

    init {
        if (mirrorPending) channel?.markNeedsRetry()
    }

    /** Verdadero mientras hay una copia en curso, pendiente o fallida. */
    val mirrorPending: Boolean get() = channel?.pending ?: false

    /** Guarda primero la local atómica (lanza si falla: el espejo no se encola). Después encola el espejo. */
    fun persistLocal(data: ByteArray) {
        local.save(data)
        val w = writer ?: return
        channel?.enqueue(MirrorChannel.Request(data, local, w))
    }

    /** Reintenta en la cola del espejo, también al abrir un juego. Si ya hay una escritura en curso, esa drenará el último valor. */
    fun retryMirrorIfNeeded(data: ByteArray) {
        val w = writer ?: return
        channel?.retryIfNeeded(MirrorChannel.Request(data, local, w))
    }

    /** Se invoca cuando no queda una escritura de espejo en vuelo (un fallo también libera). */
    fun whenMirrorIdle(callback: () -> Unit) {
        val ch = channel
        if (ch == null) callback() else ch.whenIdle(callback)
    }
}
