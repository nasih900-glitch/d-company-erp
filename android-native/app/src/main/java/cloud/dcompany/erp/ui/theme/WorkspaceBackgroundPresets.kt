package cloud.dcompany.erp.ui.theme

import androidx.annotation.DrawableRes
import cloud.dcompany.erp.R

internal data class WorkspaceBackgroundPreset(
    val id: String,
    val label: String,
    @DrawableRes val resource: Int,
    val width: Int,
    val height: Int,
    val contentId: String,
    val assetType: String,
)

internal object WorkspaceBackgroundPresets {
    const val DefaultId = "gta_vi"

    val entries: List<WorkspaceBackgroundPreset> = listOf(
        WorkspaceBackgroundPreset(
            id = DefaultId,
            label = "Grand Theft Auto VI",
            resource = R.drawable.workspace_shared_default,
            width = 3_840,
            height = 2_160,
            contentId = "1cb71c65397fb9337887a052ae3b8ca513878d67157bf911941f586ab8288b60",
            assetType = "Publisher wallpaper",
        ),
        WorkspaceBackgroundPreset(
            id = "call_of_duty",
            label = "Call of Duty: Modern Warfare",
            resource = R.drawable.workspace_preset_call_of_duty,
            width = 3_840,
            height = 2_160,
            contentId = "9ae6c50c9097a0e48c1c7850a029cddad1bedaa1f42b97851d0cb92508315ef3",
            assetType = "Publisher wallpaper",
        ),
        WorkspaceBackgroundPreset(
            id = "assassins_creed",
            label = "Assassin’s Creed Mirage",
            resource = R.drawable.workspace_preset_assassins_creed,
            width = 3_840,
            height = 2_160,
            contentId = "96564ae5a3b0add567a06e9f319c13e4754e02613fdc1ad8846453f67dd50790",
            assetType = "Publisher fan-kit wallpaper",
        ),
        WorkspaceBackgroundPreset(
            id = "fc27",
            label = "EA SPORTS FC 27",
            resource = R.drawable.workspace_preset_fc27,
            width = 3_840,
            height = 2_160,
            contentId = "ab51cde3444c1081e615a84a4305cdb56104d7cbd7d0422389f5bd363036cdd1",
            assetType = "Publisher key art",
        ),
        WorkspaceBackgroundPreset(
            id = "wukong",
            label = "Black Myth: Wukong",
            resource = R.drawable.workspace_preset_wukong,
            width = 3_840,
            height = 2_160,
            contentId = "464620be4c5f5a50a69ef9261666d2c1e1ae274c50863da76a7caddbc539455e",
            assetType = "Official 4K gameplay screenshot",
        ),
        WorkspaceBackgroundPreset(
            id = "ghost_of_tsushima",
            label = "Ghost of Tsushima",
            resource = R.drawable.workspace_preset_ghost_of_tsushima,
            width = 3_840,
            height = 2_160,
            contentId = "6ae5b41dfdd4ab381f9a4bd69f5fc121116819552c5561bbfe48d43ea3751bc7",
            assetType = "Publisher wallpaper",
        ),
    )

    private val byId = entries.associateBy(WorkspaceBackgroundPreset::id)

    val default: WorkspaceBackgroundPreset get() = requireNotNull(byId[DefaultId])

    fun find(id: String?): WorkspaceBackgroundPreset? = id?.let(byId::get)
}

internal enum class WorkspaceBackgroundSelectionKind {
    PRESET,
    CUSTOM_WORKSPACE,
    CUSTOM_LEGACY_HOME,
}

internal data class WorkspaceBackgroundSelection(
    val kind: WorkspaceBackgroundSelectionKind,
    val presetId: String? = null,
    val generation: Long = 0L,
    val expectedCustomContentId: String? = null,
) {
    companion object {
        fun default(generation: Long = 0L) = WorkspaceBackgroundSelection(
            kind = WorkspaceBackgroundSelectionKind.PRESET,
            presetId = WorkspaceBackgroundPresets.DefaultId,
            generation = generation,
        )
    }
}
