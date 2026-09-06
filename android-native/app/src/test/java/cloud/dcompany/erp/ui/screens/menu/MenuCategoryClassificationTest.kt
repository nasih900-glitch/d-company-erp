package cloud.dcompany.erp.ui.screens.menu

import cloud.dcompany.erp.core.db.LocalMenuCategoryEntity
import cloud.dcompany.erp.core.db.MenuCategoryEntity
import cloud.dcompany.erp.core.db.MenuWriteState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuCategoryClassificationTest {
    @Test
    fun `new category is hidden from gaming unless owner enables it`() {
        val editor = CategoryEditor()

        assertFalse(editor.isGamingCentreCatalog ?: true)
        assertFalse(CategoryCreateBody(name = "Future cafe menu").isGamingCentreCatalog)
    }

    @Test
    fun `editing presentation fields preserves the existing classification`() {
        val editor = CategoryEditor(
            id = "category-1",
            name = "Soft Drinks",
            sortOrder = 5,
            isGamingCentreCatalog = true,
        )

        assertTrue(editor.copy(name = "Cold cabinet").isGamingCentreCatalog == true)
    }

    @Test
    fun `create and update bodies use backend 0070 field name`() {
        val createJson = Json.encodeToString(
            CategoryCreateBody(
                name = "Packaged counter",
                isGamingCentreCatalog = true,
            ),
        )
        val updateJson = Json.encodeToString(
            CategoryUpdateBody(isGamingCentreCatalog = false),
        )
        val legacyPreservingUpdateJson = Json.encodeToString(
            CategoryUpdateBody(name = "Cold cabinet"),
        )

        assertTrue(createJson.contains("\"is_gaming_centre_catalog\":true"))
        assertTrue(updateJson.contains("\"is_gaming_centre_catalog\":false"))
        assertFalse(legacyPreservingUpdateJson.contains("is_gaming_centre_catalog"))
    }

    @Test
    fun `pending edits preserve cached classification unless explicitly changed`() {
        val cached = MenuCategoryEntity(
            id = "category-1",
            name = "Soft Drinks",
            sortOrder = 5,
            isGamingCentreCatalog = true,
        )
        val pendingRename = LocalMenuCategoryEntity(
            localId = "pending-1",
            serverId = cached.id,
            name = "Cold cabinet",
            createdAtMillis = 1_000,
            state = MenuWriteState.PENDING,
        )

        val preserved = mergeCategories(listOf(cached), listOf(pendingRename)).single()
        assertTrue(preserved.isGamingCentreCatalog == true)

        val explicitlyHidden = mergeCategories(
            listOf(cached),
            listOf(pendingRename.copy(isGamingCentreCatalog = false)),
        ).single()
        assertFalse(explicitlyHidden.isGamingCentreCatalog ?: true)
    }
}
