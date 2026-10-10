package com.joelbermudez.pocketgb.saves

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * N6 · regla dura 6 frente a la muerte del proceso a mitad de «cargar/recuperar un momento»: un JVM hijo crea momentos e
 * instala su partida en bucle ([MomentKillWorker]) y se mata con SIGKILL en instantes pseudoaleatorios, 120 veces. Tras
 * cada muerte y la recuperación de la apertura: `.sav` presente, completo y entre la última versión confirmada y la
 * última iniciada; la partida de antes de la última instalación sigue en el `.sav`, en los backups o en el anillo
 * «Antes de cargar» (nunca se pierde); el índice de momentos se lee y no quedan temporales ni archivos sin confirmar.
 */
class MomentKillTest {
    @get:Rule val tmp = TemporaryFolder()

    private val fp = "ab".repeat(32)

    private fun classpath(): String {
        val parts = LinkedHashSet<String>()
        System.getProperty("java.class.path")?.split(File.pathSeparator)?.filter { it.isNotBlank() }?.let(parts::addAll)
        for (c in listOf(MomentKillWorker::class.java, SaveStore::class.java, Unit::class.java, kotlinx.serialization.json.Json::class.java)) {
            c.protectionDomain?.codeSource?.location?.let { parts += File(it.toURI()).path }
        }
        return parts.joinToString(File.pathSeparator)
    }

    @Test fun killedWhileLoadingAMomentNeverLosesTheGame() {
        val root = tmp.newFolder()
        val save = SaveStore(File(root, "saves"), fp)
        save.save(ProcessKillWorker.encode(0))
        val java = File(System.getProperty("java.home"), "bin/java").path
        val cp = classpath()
        val seed = System.nanoTime()
        val rnd = Random(seed)
        var completedTotal = 0
        var inLoop = 0
        var lastConfirmed = 0
        repeat(120) { iteration ->
            val label = "iteración $iteration (semilla $seed)"
            val process = ProcessBuilder(java, "-XX:TieredStopAtLevel=1", "-cp", cp, MomentKillWorker::class.java.name, root.path, fp)
                .redirectErrorStream(true).start()
            val output = StringBuilder()
            val reader = Thread {
                val buf = ByteArray(4096)
                while (true) {
                    val n = process.inputStream.read(buf)
                    if (n < 0) break
                    synchronized(output) { output.append(String(buf, 0, n)) }
                }
            }.apply { isDaemon = true; start() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (synchronized(output) { !output.contains("ready") } && System.nanoTime() < deadline) Thread.sleep(2)
            assertTrue("$label: el hijo no arrancó: $output", synchronized(output) { output.contains("ready") })
            if (iteration % 4 == 0) {
                val confirm = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (synchronized(output) { !output.contains("\nc") } && System.nanoTime() < confirm) Thread.sleep(2)
            }
            Thread.sleep(rnd.nextLong(0, 120), rnd.nextInt(0, 1_000_000))
            process.destroyForcibly()
            assertTrue("$label: el hijo no murió", process.waitFor(10, TimeUnit.SECONDS))
            reader.join(5_000)
            val lines = synchronized(output) { output.toString() }.substringBeforeLast('\n', "").lines()
            val started = lines.filter { it.startsWith("s") }.mapNotNull { it.drop(1).toIntOrNull() }
            val completed = lines.filter { it.startsWith("c") }.mapNotNull { it.drop(1).toIntOrNull() }
            if (started.isNotEmpty()) inLoop++
            completedTotal += completed.size
            completed.maxOrNull()?.let { lastConfirmed = it }
            // Lo que había como partida justo antes de la última instalación iniciada.
            val beforeLast = (started.maxOrNull()?.let { it - 1 }) ?: lastConfirmed

            // Apertura: recuperación de temporales de la partida y de los momentos.
            save.recoverOrphans(setOf(ProcessKillWorker.SIZE))
            val moments = MomentStore(File(root, "moments"), fp)
            moments.recoverOrphans()

            assertTrue("$label: falta el .sav", save.saveFile.exists())
            val sav = ProcessKillWorker.decode(save.saveFile.readBytes())
            assertNotNull("$label: .sav truncado o mezclado", sav)
            assertTrue("$label: .sav v$sav anterior a la confirmada v$lastConfirmed", sav!! >= lastConfirmed)
            started.maxOrNull()?.let { assertTrue("$label: .sav v$sav posterior a la iniciada v$it", sav <= it) }

            val snapshot = moments.snapshot()
            val ringVersions = snapshot.beforeLoad.mapNotNull { e ->
                moments.loadSram(MomentStore.Kind.BEFORE_LOAD, e.id)?.let { ProcessKillWorker.decode(it) ?: error("$label: anillo truncado") }
            }
            for (m in snapshot.moments) {
                val data = moments.loadSram(MomentStore.Kind.MOMENT, m.id)
                assertNotNull("$label: momento sin su partida", data)
                assertNotNull("$label: partida de momento truncada", ProcessKillWorker.decode(data!!))
            }
            val backupVersions = (1..SaveStore.KEEP_BACKUPS).mapNotNull { n ->
                save.backupFile(n).takeIf { it.exists() }?.let { ProcessKillWorker.decode(it.readBytes()) ?: error("$label: backup truncado") }
            }
            assertTrue(
                "$label: la partida de antes (v$beforeLast) se perdió: sav=$sav anillo=$ringVersions backups=$backupVersions",
                beforeLast == sav || beforeLast in ringVersions || beforeLast in backupVersions,
            )
            for (dir in listOf(File(root, "saves"), File(root, "saves/backups"), moments.directory)) {
                assertTrue("$label: quedan .tmp en $dir: ${dir.names()}", dir.names().none { it.endsWith(".tmp") })
            }
            val known = snapshot.moments.map { "m-${it.id}" } + snapshot.beforeLoad.map { "b-${it.id}" }
            assertTrue(
                "$label: archivos sin confirmar: ${moments.directory.names()}",
                moments.directory.names().filter { it != "index.json" }.all { it.substringBeforeLast('.') in known },
            )
        }
        println("MomentKillTest: semilla=$seed, kills en bucle=$inLoop, instalaciones completadas=$completedTotal")
        assertTrue("el hijo debía llegar al bucle casi siempre ($inLoop/120)", inLoop >= 100)
        assertTrue("debía completarse alguna instalación ($completedTotal)", completedTotal > 20)
    }
}
