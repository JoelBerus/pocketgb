package com.joelbermudez.pocketgb

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestPolicyTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun manifestHasLauncherActivity() {
        assertTrue(manifest.contains(".MainActivity"))
        assertTrue(manifest.contains("android.intent.action.MAIN"))
    }

    @Test
    fun backupRulesExcludeTheFolderTreeUri() {
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
        for (name in listOf("backup_rules.xml", "data_extraction_rules.xml")) {
            val rules = File("src/main/res/xml/$name").readText()
            assertTrue("$name debe excluir library_folder", rules.contains("""path="library_folder.xml""""))
        }
        // El URI de la carpeta no puede viajar en ninguna de las dos secciones de Android 12+.
        val extraction = File("src/main/res/xml/data_extraction_rules.xml").readText()
        assertTrue(extraction.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>").contains("library_folder.xml"))
        assertTrue(extraction.substringAfter("<device-transfer>").substringBefore("</device-transfer>").contains("library_folder.xml"))
    }

    @Test
    fun manifestHasNoNetworkPermission() {
        assertFalse(manifest.contains("android.permission.INTERNET"))
        assertFalse(manifest.contains("android.permission.ACCESS_NETWORK_STATE"))
    }

    private fun rules(name: String): String =
        File("src/main/res/xml/$name").readText().replace(Regex("(?s)<!--.*?-->"), "")

    private fun section(xml: String, tag: String): String =
        xml.substringAfter("<$tag>").substringBefore("</$tag>")

    /** J3: la nube no sube los backups rotativos ni los estados; la transferencia entre teléfonos lleva todo. */
    @Test
    fun cloudBackupExcludesSaveBackupsAndStatesButTransferKeepsThem() {
        val extraction = rules("data_extraction_rules.xml")
        val cloud = section(extraction, "cloud-backup")
        assertTrue(cloud.contains("""domain="file" path="saves/backups/""""))
        assertTrue(cloud.contains("""domain="file" path="states/""""))
        assertTrue(cloud.contains("""path="library_folder.xml""""))
        val transfer = section(extraction, "device-transfer")
        assertTrue(transfer.contains("""path="library_folder.xml""""))
        assertFalse("la transferencia incluye partidas y estados", transfer.contains("saves"))
        assertFalse("la transferencia incluye partidas y estados", transfer.contains("states"))
        // Android 11 e inferior: solo existe una regla; se aplica la de la nube (la más conservadora).
        val legacy = rules("backup_rules.xml")
        assertTrue(legacy.contains("""domain="file" path="saves/backups/""""))
        assertTrue(legacy.contains("""domain="file" path="states/""""))
    }

    /** A6 K9: las portadas capturadas son derivadas y regenerables: fuera de la copia en la nube (solo `<exclude>`). */
    @Test
    fun artworkIsExcludedFromCloudBackupAndLegacyBackup() {
        val cloud = section(rules("data_extraction_rules.xml"), "cloud-backup")
        assertTrue(cloud.contains("""<exclude domain="file" path="artwork/" />"""))
        assertTrue(rules("backup_rules.xml").contains("""<exclude domain="file" path="artwork/" />"""))
    }

    /** Las partidas (los `.sav` de saves) nunca se excluyen enteras: perderlas en una restauración es el peor bug. */
    @Test
    fun savesDirectoryItselfIsNeverExcluded() {
        for (name in listOf("backup_rules.xml", "data_extraction_rules.xml")) {
            val excluded = Regex("""<exclude[^>]*path="([^"]*)"""").findAll(rules(name)).map { it.groupValues[1] }.toList()
            assertFalse("$name excluye saves entero", excluded.any { it.trimEnd('/') == "saves" })
        }
    }

    /** Un solo `<include>` convierte el backup en lista blanca y dejaría fuera todo lo demás. */
    @Test
    fun backupRulesNeverUseInclude() {
        for (name in listOf("backup_rules.xml", "data_extraction_rules.xml")) {
            assertFalse("$name no debe usar <include>", rules(name).contains("<include"))
        }
    }

    /** A7 R12: rotar no recrea la actividad (y por tanto no pausa); `fontScale` no está: sigue recreando. */
    @Test
    fun mainActivityHandlesRotationAndSizeChangesItself() {
        val activity = manifest.substringAfter("android:name=\".MainActivity\"").substringBefore(">")
        val value = Regex("""android:configChanges="([^"]*)"""").find(activity)?.groupValues?.get(1)
            ?: error("MainActivity sin configChanges")
        val flags = value.split("|").toSet()
        for (flag in listOf("orientation", "screenSize", "screenLayout", "smallestScreenSize", "uiMode", "density")) {
            assertTrue("falta $flag", flag in flags)
        }
        assertFalse("fontScale debe seguir recreando", "fontScale" in flags)
    }

    @Test
    fun manifestAddsNoPermissions() {
        assertFalse(manifest.contains("<uses-permission"))
    }
}
