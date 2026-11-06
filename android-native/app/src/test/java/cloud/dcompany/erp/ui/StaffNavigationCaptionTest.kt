package cloud.dcompany.erp.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class StaffNavigationCaptionTest {
    @Test fun attendanceOnlyMoreCaptionDoesNotPromiseEmployeeManagement() {
        assertEquals("Clock in/out and view attendance", workspaceDestinationDescription(Destination.Staff, true))
        assertEquals(Destination.Staff.description, workspaceDestinationDescription(Destination.Staff, false))
        assertEquals(Destination.Gaming.description, workspaceDestinationDescription(Destination.Gaming, true))
    }
}
