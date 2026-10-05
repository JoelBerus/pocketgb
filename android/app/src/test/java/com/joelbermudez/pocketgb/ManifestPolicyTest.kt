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
}
