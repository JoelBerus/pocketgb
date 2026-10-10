package com.joelbermudez.pocketgb.travel

import com.joelbermudez.pocketgb.progress.GameProgress
import com.joelbermudez.pocketgb.progress.Milestone

/**
 * ND20 (i) · fusión de los metadatos de un paquete importado con los de aquí (P9/ND12: son por dispositivo y viajan):
 * etiquetas = unión; alias = se conserva el local si existe, si no el del paquete; tiempo de juego = el máximo; hitos =
 * unión por `id` (marcado gana). Pura: la interfaz aplica el resultado.
 */
object MetadataMerge {
    data class Merged(val alias: String?, val tags: List<String>, val playTimeMs: Long, val milestones: List<Milestone>)

    fun merge(localAlias: String?, localTags: List<String>, progress: GameProgress, meta: PgbmMeta): Merged {
        val alias = localAlias?.takeIf { it.isNotBlank() } ?: meta.alias?.takeIf { it.isNotBlank() }
        val tags = (localTags + meta.tags.orEmpty()).distinctBy { it.lowercase() }
        val play = maxOf(progress.playTimeMs, meta.playTimeMs ?: 0L)
        val byId = LinkedHashMap<String, Milestone>()
        progress.milestones.forEach { byId[it.id] = it }
        meta.milestones.orEmpty().forEach { m ->
            val mine = byId[m.id]
            byId[m.id] = when {
                mine == null -> Milestone(m.id, m.title.take(GameProgress.MAX_TITLE), m.done)
                m.done && !mine.done -> mine.copy(done = true)
                else -> mine
            }
        }
        return Merged(alias, tags, play, byId.values.take(GameProgress.MAX_MILESTONES))
    }
}
