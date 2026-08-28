package cloud.dcompany.erp.core.auth

import org.junit.Assert.assertThrows
import org.junit.Test

class BranchScopedPayloadTest {

    @Test
    fun `matching branch is accepted after normalization`() {
        verifyBranchScopedPayload(" branch-a ", "branch-a", "report")
    }

    @Test
    fun `missing active branch and mismatched response fail closed`() {
        assertThrows(BranchScopeMismatchException::class.java) {
            verifyBranchScopedPayload(null, "branch-a", "report")
        }
        assertThrows(BranchScopeMismatchException::class.java) {
            verifyBranchScopedPayload("branch-a", "branch-b", "analytics response")
        }
    }
}
