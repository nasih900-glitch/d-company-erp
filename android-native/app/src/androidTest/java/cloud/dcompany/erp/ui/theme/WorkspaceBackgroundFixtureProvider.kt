package cloud.dcompany.erp.ui.theme

import android.content.ContentResolver
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

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
    CONTROLLED_VALID_JPEG,
    RETRY_VALID_JPEG,
    PERSISTENCE_HOME_JPEG,
    PERSISTENCE_WORKSPACE_PNG,
    EDGE_4096_JPEG,
    EDGE_4097_JPEG,
}

internal data class ControlledWorkspaceFixtureState(
    val entered: Boolean,
    val released: Boolean,
    val exited: Boolean,
    val failed: Boolean,
)

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

    fun controlledState(uri: Uri): ControlledWorkspaceFixtureState =
        controlledCall("controlled-fixture-state", uri)

    suspend fun awaitProviderEntry(uri: Uri) {
        withTimeout(5_000L) {
            while (!controlledState(uri).entered) delay(10L)
        }
    }

    fun releaseProvider(uri: Uri) {
        controlledCall("release-controlled-fixture", uri)
    }

    fun disposeControlled(uri: Uri) {
        controlledCall("dispose-controlled-fixture", uri)
    }

    private fun controlledCall(method: String, uri: Uri): ControlledWorkspaceFixtureState {
        require(uri.scheme == "content" && uri.authority == Authority)
        require(uri.pathSegments.size == 2 && uri.pathSegments[0] == "fixture")
        val token = uri.pathSegments[1]
        require(token.matches(Regex("[0-9a-f]{32}")))
        val resolver = InstrumentationRegistry.getInstrumentation().context.contentResolver
        val result = checkNotNull(resolver.call(Authority, method, token, null))
        return ControlledWorkspaceFixtureState(
            entered = result.getBoolean("entered"),
            released = result.getBoolean("released"),
            exited = result.getBoolean("exited"),
            failed = result.getBoolean("failed"),
        )
    }
}
