package cloud.dcompany.erp.core.sync

import cloud.dcompany.erp.core.net.ApiException

internal const val STAFF_AUTHORITY_REVOKED_NOTICE =
    "A saved Staff change was cancelled because this account no longer has Staff management permission. Nothing changed on the server."

/**
 * A normal 403 (for example trying to change a protected owner) must remain a
 * visible rejected row. Only the backend's exact permission-revocation
 * refusal is safe to discard: the write definitively did not apply, the Staff
 * management screen is about to disappear after `/auth/me` refresh, and
 * retaining that invisible non-financial row would permanently block sign-out.
 */
internal fun isStaffWriteAuthorityRevoked(failure: ApiException): Boolean =
    failure.status == 403 &&
        failure.message.orEmpty().contains("missing permission: staff.write", ignoreCase = true)
