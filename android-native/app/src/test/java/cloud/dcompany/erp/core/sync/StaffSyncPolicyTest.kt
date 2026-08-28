package cloud.dcompany.erp.core.sync

import cloud.dcompany.erp.core.net.ApiException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaffSyncPolicyTest {

    @Test
    fun `only exact staff write revocation may discard hidden nonfinancial write`() {
        assertTrue(
            isStaffWriteAuthorityRevoked(
                ApiException("missing permission: staff.write", status = 403, code = "forbidden"),
            ),
        )
        assertFalse(
            isStaffWriteAuthorityRevoked(
                ApiException("Only the protected owner can change owner access.", status = 403, code = "forbidden"),
            ),
        )
        assertFalse(
            isStaffWriteAuthorityRevoked(
                ApiException("missing permission: staff.write", status = null),
            ),
        )
    }
}
