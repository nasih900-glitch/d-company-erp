package cloud.dcompany.erp.ui.theme

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Properties
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceBackgroundPersistenceUiTest {
    private val base: Context = ApplicationProvider.getApplicationContext()
    private val root = File(base.filesDir, PersistenceNamespace)
    private val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = root
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
            base.getSharedPreferences("$PersistenceNamespace-$name", mode)
    }
    private val marker = File(root, "committed-chain.properties")

    @Test
    fun stageAImportBothSlotsAndPersist() {
        runBlocking {
            val chain = requiredChainArguments()
            root.deleteRecursively()
            assertTrue(root.mkdirs())
            val store = WorkspaceBackgroundStore(context)
            val custom = store.import(
                WorkspaceBackgroundSlot.WORKSPACE,
                WorkspaceBackgroundFixtures.create(WorkspaceBackgroundFixtureRecipe.PERSISTENCE_WORKSPACE_PNG),
            ).image
            assertCommitted(custom, WorkspaceBackgroundSlot.WORKSPACE)
            val selected = store.selectPreset("wukong")
            assertEquals(WorkspaceBackgroundSelectionKind.PRESET, selected.selection.kind)
            assertEquals("wukong", selected.selection.presetId)
            assertTrue(File(requireNotNull(custom.privatePath)).isFile)

            val properties = Properties().apply {
                putAll(chain)
                setProperty("stage_a_pid", Process.myPid().toString())
                setProperty("stage_a_start_uptime", Process.getStartUptimeMillis().toString())
                setProperty("custom_content_id", custom.contentId)
                setProperty("custom_dimensions", "${custom.width}x${custom.height}")
                setProperty("selected_preset_id", requireNotNull(selected.selection.presetId))
                setProperty("selected_generation", selected.selection.generation.toString())
            }
            val temporary = File(root, ".committed-chain.tmp")
            FileOutputStream(temporary).use { output ->
                properties.store(output, "Unified workspace background persistence chain; no provider URI or source filename")
                output.fd.sync()
            }
            assertTrue(temporary.renameTo(marker))
            assertTrue(marker.isFile)
        }
    }

    @Test
    fun stageBVerifyBothSlotsAfterProcessRestart() {
        runBlocking {
            val chain = requiredChainArguments()
            assertTrue("Stage A marker is missing", marker.isFile)
            val properties = Properties().apply { FileInputStream(marker).use(::load) }
            chain.forEach { (name, expected) -> assertEquals("chain pin $name", expected, properties.getProperty(name)) }
            assertTrue(properties.getProperty("stage_a_pid").toInt() != Process.myPid())
            assertTrue(properties.getProperty("stage_a_start_uptime").toLong() != Process.getStartUptimeMillis())

            val store = WorkspaceBackgroundStore(context)
            val loaded = store.load()
            assertEquals(null, loaded.error)
            assertEquals(WorkspaceBackgroundSelectionKind.PRESET, loaded.selection.kind)
            assertEquals(properties.getProperty("selected_preset_id"), loaded.selection.presetId)
            assertEquals(properties.getProperty("selected_generation").toLong(), loaded.selection.generation)
            assertTrue(loaded.savedCustomAvailable)
            val restored = store.useSavedCustom()
            assertEquals(WorkspaceBackgroundSelectionKind.CUSTOM_WORKSPACE, restored.selection.kind)
            assertEquals(properties.getProperty("custom_content_id"), restored.image.contentId)
            assertEquals(properties.getProperty("custom_dimensions"), "${restored.image.width}x${restored.image.height}")
            assertEquals(restored.image.contentId, File(requireNotNull(restored.image.privatePath)).sha256())

            store.reset(WorkspaceBackgroundSlot.WORKSPACE)
            assertTrue("Selecting default must preserve the saved custom bytes", File(requireNotNull(restored.image.privatePath)).isFile)
            assertTrue(marker.delete())
            root.deleteRecursively()
            base.getSharedPreferences(
                "$PersistenceNamespace-dcompany_workspace_backgrounds",
                Context.MODE_PRIVATE,
            ).edit().clear().commit()
        }
    }

    private fun assertCommitted(image: WorkspaceBackgroundImage, slot: WorkspaceBackgroundSlot) {
        assertEquals(slot, image.slot)
        assertEquals(WorkspaceBackgroundSource.IMPORTED, image.source)
        assertEquals(2560, image.width)
        assertEquals(1080, image.height)
        val file = File(requireNotNull(image.privatePath))
        assertTrue(file.isFile)
        assertEquals(image.contentId, file.sha256())
    }

    private fun requiredChainArguments(): Map<String, String> {
        val arguments = InstrumentationRegistry.getArguments()
        fun required(name: String, length: Int): String {
            val value = requireNotNull(arguments.getString(name)) { "$name is required" }
            require(value.matches(Regex("[0-9a-fA-F]{$length}"))) { "$name must be $length hexadecimal characters" }
            return value.lowercase()
        }
        return linkedMapOf(
            "persistence_run_id" to required("dcompany.persistenceRunId", 32),
            "source_inventory_sha256" to required("dcompany.sourceInventorySha256", 64),
            "target_apk_sha256" to required("dcompany.targetApkSha256", 64),
            "test_apk_sha256" to required("dcompany.testApkSha256", 64),
        )
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(64 * 1_024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val PersistenceNamespace = "workspace-background-persistence-test"
    }
}
