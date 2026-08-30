package cloud.dcompany.erp.core.remote

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteDeviceKeyStoreDeviceTest {
    private val keys = RemoteDeviceKeyStore()

    @Before
    fun clearBefore() {
        keys.delete(KEY_ID)
    }

    @After
    fun clearAfter() {
        keys.delete(KEY_ID)
    }

    @Test
    fun p256PrivateKeyIsNonExportableAndSignatureVerifiesWithStablePublicSpki() {
        val spki = keys.generate(KEY_ID)
        val restoredSpki = requireNotNull(keys.publicKeySpki(KEY_ID))
        val publicKey = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(spki)) as ECPublicKey
        val statement = "deterministic remote device proof".encodeToByteArray()
        val signature = keys.sign(KEY_ID, statement)

        assertEquals(256, publicKey.params.curve.field.fieldSize)
        assertArrayEquals(spki, restoredSpki)
        assertTrue(keys.privateKeyIsNonExportable(KEY_ID))
        assertTrue(
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey)
                update(statement)
                verify(signature)
            },
        )

        assertTrue(keys.delete(KEY_ID))
        assertNull(keys.publicKeySpki(KEY_ID))
    }

    private companion object {
        const val KEY_ID = "12345678-1234-4567-89ab-1234567890ab"
    }
}
