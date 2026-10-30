package cloud.dcompany.erp.ui.theme

import android.content.ContentResolver
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry

internal enum class WorkspaceBackgroundFixtureRecipe {
    ALPHA_PNG,
    VALID_JPEG,
    PORTRAIT_JPEG,
    BROKEN_PNG,
    TRUNCATED_JPEG,
    TRUNCATED_PNG,
    ANIMATED_PNG,
    TOO_SMALL_JPEG,
    EXIF_ROTATED_JPEG,
    DELAYED_VALID_JPEG,
    RETRY_VALID_JPEG,
    PERSISTENCE_HOME_JPEG,
    PERSISTENCE_WORKSPACE_PNG,
    EDGE_4096_JPEG,
    EDGE_4097_JPEG,
}

/** Resolver helper runs in instrumentation/target code. The manifest provider itself is Java-only. */
internal object WorkspaceBackgroundFixtures {
    const val Authority = "cloud.dcompany.erp.background-fixtures"
    private const val CreateMethod = "create-fixture"
    private const val ResultUriKey = "uri"

    fun create(
        recipe: WorkspaceBackgroundFixtureRecipe,
        resolver: ContentResolver = InstrumentationRegistry.getInstrumentation().context.contentResolver,
    ): Uri {
        val result = checkNotNull(resolver.call(Authority, CreateMethod, recipe.name, null))
        return Uri.parse(requireNotNull(result.getString(ResultUriKey)))
    }
}
