package com.joelbermudez.pocketgb.saves

/**
 * Cuándo vaciar la SRAM a disco (SPEC §5.2): debounce de 1 s tras cada "el juego guardó" y red de seguridad
 * de 60 s (un juego puede escribir la SRAM y dejarla habilitada sin generar el flanco). Pura, con reloj
 * inyectado (milisegundos monótonos). Se sondea desde el hilo que atiende la emulación.
 */
class SramFlushPolicy(
    private val clock: () -> Long,
    private val debounceMs: Long = 1_000,
    private val safetyNetMs: Long = 60_000,
) {
    private var dirtyAt: Long? = null
    private var lastFlushAt: Long = clock()

    /**
     * @param dirty el juego guardó desde el último sondeo (cada guardado reinicia la espera).
     * @return `true` si hay que vaciar ahora; el llamador debe avisar luego con [onFlushFailed] si falla.
     */
    fun poll(dirty: Boolean): Boolean {
        val now = clock()
        if (dirty) dirtyAt = now
        val d = dirtyAt
        val due = (d != null && now - d >= debounceMs) || now - lastFlushAt >= safetyNetMs
        if (due) onFlushDone()
        return due
    }

    /** Un fallo reprograma el reintento un debounce más tarde (no se pierde ni se repite sin pausa). */
    fun onFlushFailed() {
        dirtyAt = clock()
    }

    /** Un vaciado por otra vía (síncrono al pausar/salir) reinicia los dos plazos. */
    fun onFlushDone() {
        dirtyAt = null
        lastFlushAt = clock()
    }
}
