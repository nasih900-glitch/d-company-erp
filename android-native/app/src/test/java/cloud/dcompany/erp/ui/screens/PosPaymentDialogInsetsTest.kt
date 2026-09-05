package cloud.dcompany.erp.ui.screens

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PosPaymentDialogInsetsTest {
    @Test
    fun `money dialogs expose system insets and preserve root keyboard padding`() {
        assertFalse(posImeAwareDialogProperties.usePlatformDefaultWidth)
        assertFalse(posImeAwareDialogProperties.decorFitsSystemWindows)
        val root = listOf("src/main/java", "app/src/main/java", "android-native/app/src/main/java")
            .map(::File).first { it.isDirectory }
        val source = root.resolve("cloud/dcompany/erp/ui/screens/PosScreen.kt").readText()
        assertTrue(source.contains(".statusBarsPadding().navigationBarsPadding().imePadding()"))
        for (name in listOf("HeldOrderReviewDialog", "DirectCheckoutReviewDialog", "PayDialog")) {
            val body = source.substringAfter("private fun $name(").substringBefore("\n@Composable")
            assertTrue(name, body.contains("modifier = Modifier.posPaymentDialogInsets()"))
            assertTrue(name, body.contains("properties = posImeAwareDialogProperties"))
            assertTrue(name, body.contains(".verticalScroll(rememberScrollState())"))
        }
    }
}
