package com.joelbermudez.pocketgb.saves

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Regla dura 6 frente a la muerte del proceso: un JVM hijo guarda versiones numeradas en bucle con el
 * [AtomicSaveWriter] real y se mata con `destroyForcibly()` (SIGKILL) en instantes pseudoaleatorios, 200 veces.
 * Tras cada muerte, `recoverOrphans` debe dejar el `.sav` presente y igual a alguna versión completa
 * escrita, sin `.tmp`, con ≤5 backups y cada backup una versión anterior completa.
 *
 * Alcance: cubre la muerte del PROCESO (el kernel conserva lo ya escrito), NO un corte de corriente
 * (que depende de fsync y del almacenamiento y no se puede probar desde un test JVM).
 */
class ProcessKillTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun classpath(): String {
        val parts = LinkedHashSet<String>()
        System.getProperty("java.class.path")?.split(File.pathSeparator)?.filter { it.isNotBlank() }?.let(parts::addAll)
        for (c in listOf(ProcessKillWorker::class.java, SaveStore::class.java, Unit::class.java)) {
            c.protectionDomain?.codeSource?.location?.let { parts += File(it.toURI()).path }
        }
        return parts.joinToString(File.pathSeparator)
    }

    private fun versionOf(file: File, label: String): Int {
        val v = ProcessKillWorker.decode(file.readBytes())
        assertNotNull("$label: ${file.name} está truncado o mezclado", v)
        return v!!
    }

    @Test fun killedWriterNeverLeavesAPartialOrMissingSave() {
        val dir = File(tmp.newFolder(), "saves")
        val store = SaveStore(dir, TEST_FP)
        store.save(ProcessKillWorker.encode(0)) // la partida ya existía: el invariante es "nunca desaparece"
        val java = File(System.getProperty("java.home"), "bin/java").path
        val cp = classpath()
        val seed = System.nanoTime()
        val rnd = Random(seed)
        var inLoopKills = 0
        var completedTotal = 0
        var midWriteKills = 0
        repeat(200) { iteration ->
            val label = "iteración $iteration (semilla $seed)"
            val process = ProcessBuilder(
                java, "-XX:TieredStopAtLevel=1", "-Xshare:auto", "-cp", cp,
                ProcessKillWorker::class.java.name, dir.path, TEST_FP,
            ).redirectErrorStream(true).start()
            val output = StringBuilder()
            val reader = Thread {
                val buf = ByteArray(4096)
                while (true) {
                    val n = process.inputStream.read(buf)
                    if (n < 0) break
                    synchronized(output) { output.append(String(buf, 0, n)) }
                }
            }.apply { isDaemon = true; start() }
            // Espera a que arranque el bucle y mata en un instante pseudoaleatorio.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (synchronized(output) { !output.contains("ready") } && System.nanoTime() < deadline) Thread.sleep(2)
            assertTrue("$label: el hijo no arrancó: $output", synchronized(output) { output.contains("ready") })
            // Una de cada cuatro muertes espera a que haya al menos un guardado confirmado: así la prueba siempre es
            // significativa (≥ 50 guardados completos) aunque el anfitrión esté saturado; las demás matan al azar.
            if (iteration % 4 == 0) {
                val confirmDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (synchronized(output) { !output.contains("\nc") } && System.nanoTime() < confirmDeadline) Thread.sleep(2)
            }
            Thread.sleep(rnd.nextLong(0, 90), rnd.nextInt(0, 1_000_000))
            process.destroyForcibly()
            assertTrue("$label: el hijo no murió", process.waitFor(10, TimeUnit.SECONDS))
            reader.join(5_000)

            // Solo líneas completas (la última puede haber quedado cortada por el SIGKILL).
            val text = synchronized(output) { output.toString() }
            val lines = text.substringBeforeLast('\n', "").lines()
            val started = lines.filter { it.startsWith("s") }.mapNotNull { it.drop(1).toIntOrNull() }
            val completed = lines.filter { it.startsWith("c") }.mapNotNull { it.drop(1).toIntOrNull() }
            val maxStarted = started.maxOrNull()
            val maxCompleted = completed.maxOrNull()
            if (maxStarted != null) inLoopKills++
            completedTotal += completed.size

            // Muerte a mitad de escritura: había temporales sin recuperar.
            if ((dir.names() + File(dir, "backups").names()).any { it.endsWith(".tmp") }) midWriteKills++

            // Recuperación al arrancar.
            store.recoverOrphans(setOf(ProcessKillWorker.SIZE))

            assertTrue("$label: falta el .sav", store.saveFile.exists())
            val sav = versionOf(store.saveFile, label)
            if (maxCompleted != null) assertTrue("$label: el .sav ($sav) es anterior a la última versión confirmada ($maxCompleted)", sav >= maxCompleted)
            if (maxStarted != null) assertTrue("$label: el .sav ($sav) es posterior a la última iniciada ($maxStarted)", sav <= maxStarted)
            for (d in listOf(dir, File(dir, "backups"))) {
                assertTrue("$label: quedan .tmp en ${d.name}: ${d.names()}", d.names().none { it.endsWith(".tmp") })
            }
            val backups = (1..SaveStore.KEEP_BACKUPS).filter { store.backupFile(it).exists() }
            assertTrue("$label: más de 5 backups", backups.size <= SaveStore.KEEP_BACKUPS)
            assertEquals("$label: archivos inesperados en backups", backups.size, File(dir, "backups").names().size)
            var previous = sav
            for (n in backups) {
                val v = versionOf(store.backupFile(n), label)
                // Entre el paso 4 y el 5 el .1 es una copia del .sav aún vigente: solo ahí se admite la igualdad.
                val ok = if (n == backups.first()) v <= previous else v < previous
                assertTrue("$label: el backup $n (v$v) no es anterior (v$previous)", ok)
                previous = v
            }
        }
        println("ProcessKillTest: semilla=$seed, kills en bucle=$inLoopKills, con .tmp huérfano=$midWriteKills, guardados completados=$completedTotal")
        assertTrue("el hijo debía llegar al bucle casi siempre ($inLoopKills/200)", inLoopKills >= 180)
        // Solo comprueba que la prueba es significativa (hubo guardados completos entre las muertes); el rendimiento del hijo
        // depende de la carga del anfitrión (92 en un Mac con la memoria saturada, >400 en uno normal).
        assertTrue("debía completarse algún guardado en total ($completedTotal)", completedTotal > 40)
    }
}
