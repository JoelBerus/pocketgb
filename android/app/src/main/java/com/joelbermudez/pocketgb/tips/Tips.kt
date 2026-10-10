package com.joelbermudez.pocketgb.tips

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * N9 · Consejos descartables en puntos clave (el equivalente Android de TipKit en iOS). El [id] es estable: es la clave
 * del descarte guardado; no se renombra.
 */
enum class Tip(val id: String) {
    /** Pausa › Momentos. */
    MOMENTS("moments-pause"),
    /** Ajustes del juego › Categoría. */
    CATEGORY("category-move"),
    /** Detalle del juego: Enviar a otro dispositivo / Intercambio. */
    SEND("send-exchange"),
    /** Ajustes › Controles con «Flechas separadas». */
    ARROWS("split-arrows"),
}

/** Dónde se guarda qué consejos se descartaron. */
interface TipsStorage {
    fun dismissed(): Set<String>
    fun save(dismissed: Set<String>)
}

class InMemoryTipsStorage(initial: Set<String> = emptySet()) : TipsStorage {
    private var value = initial
    override fun dismissed() = value
    override fun save(dismissed: Set<String>) { value = dismissed }
}

/** Preferencias privadas de la app (`shared_prefs/tips.xml`): si no se pueden leer, se muestran los consejos. */
class SharedPreferencesTipsStorage(context: Context) : TipsStorage {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    override fun dismissed(): Set<String> = runCatching { prefs.getStringSet(KEY, emptySet()).orEmpty().toSet() }.getOrDefault(emptySet())
    override fun save(dismissed: Set<String>) {
        prefs.edit().putStringSet(KEY, dismissed).apply()
    }

    companion object {
        const val FILE = "tips"
        const val KEY = "dismissed"
    }
}

/** Estado observable de los consejos: un consejo descartado no vuelve a salir (también tras reiniciar la app). */
class TipsState(private val storage: TipsStorage) {
    private var dismissed by mutableStateOf(storage.dismissed())

    fun isVisible(tip: Tip): Boolean = tip.id !in dismissed

    fun dismiss(tip: Tip) {
        if (tip.id in dismissed) return
        dismissed = dismissed + tip.id
        runCatching { storage.save(dismissed) }
    }

    /** Ajustes › Guía › «Volver a mostrar los consejos». */
    fun resetAll() {
        dismissed = emptySet()
        runCatching { storage.save(emptySet()) }
    }

    val anyDismissed: Boolean get() = dismissed.isNotEmpty()
}

/** `null` (por defecto): sin consejos, para pantallas aisladas y pruebas que no los esperan. La app los da en `PocketGBApp`. */
val LocalTips = staticCompositionLocalOf<TipsState?> { null }
