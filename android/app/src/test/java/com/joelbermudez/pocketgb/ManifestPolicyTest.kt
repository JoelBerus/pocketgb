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
    fun manifestHasNoNetworkPermission() {
        assertFalse(manifest.contains("android.permission.INTERNET"))
        assertFalse(manifest.contains("android.permission.ACCESS_NETWORK_STATE"))
    }
}
