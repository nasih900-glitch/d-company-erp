package cloud.dcompany.erp.core.remote

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/** Non-exportable P-256 signing keys dedicated to remote-assistance device proof. */
internal class RemoteDeviceKeyStore {
    private val lock = Any()

    fun generate(keyId: String): ByteArray = synchronized(lock) {
        require(isCanonicalUuidV4(keyId)) { "keyId must be a canonical UUID v4" }
        val alias = alias(keyId)
        val keyStore = loadKeyStore()
        require(!keyStore.containsAlias(alias)) { "Remote device key already exists" }
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE).apply {
            initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec(P256_CURVE))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
        }.generateKeyPair()
        validatedPublicKey(keyStore, alias).encoded.copyOf()
    }

    fun publicKeySpki(keyId: String): ByteArray? = synchronized(lock) {
        if (!isCanonicalUuidV4(keyId)) return@synchronized null
        val keyStore = loadKeyStore()
        if (!keyStore.containsAlias(alias(keyId))) return@synchronized null
        runCatching { validatedPublicKey(keyStore, alias(keyId)).encoded.copyOf() }.getOrNull()
    }

    fun sign(keyId: String, statement: ByteArray): ByteArray = synchronized(lock) {
        require(isCanonicalUuidV4(keyId)) { "keyId must be a canonical UUID v4" }
        val keyStore = loadKeyStore()
        val alias = alias(keyId)
        val privateKey = keyStore.getKey(alias, null) as? PrivateKey
            ?: error("Remote device key is unavailable")
        check(privateKey.encoded == null) { "Remote device private key must remain non-exportable" }
        Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(privateKey)
            update(statement)
            sign()
        }
    }

    fun privateKeyIsNonExportable(keyId: String): Boolean = synchronized(lock) {
        if (!isCanonicalUuidV4(keyId)) return@synchronized false
        val privateKey = loadKeyStore().getKey(alias(keyId), null) as? PrivateKey
            ?: return@synchronized false
        privateKey.encoded == null
    }

    fun delete(keyId: String): Boolean = synchronized(lock) {
        if (!isCanonicalUuidV4(keyId)) return@synchronized false
        val keyStore = loadKeyStore()
        val alias = alias(keyId)
        if (!keyStore.containsAlias(alias)) return@synchronized true
        runCatching { keyStore.deleteEntry(alias) }.isSuccess
    }

    private fun validatedPublicKey(keyStore: KeyStore, alias: String): ECPublicKey {
        val publicKey = keyStore.getCertificate(alias)?.publicKey as? ECPublicKey
            ?: error("Remote device key is not an EC key")
        check(publicKey.params.curve.field.fieldSize == P256_FIELD_BITS) {
            "Remote device key is not P-256"
        }
        return publicKey
    }

    private fun loadKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply {
        load(null)
    }

    private fun alias(keyId: String): String = "$ALIAS_PREFIX$keyId"

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val ALIAS_PREFIX = "dcompany_erp_remote_device_v1_"
        const val P256_CURVE = "secp256r1"
        const val P256_FIELD_BITS = 256
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    }
}
