package cloud.dcompany.erp.core.alarm

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationalAlarmPlanTest {
    private val target = OperationalNotificationTarget.HeldOrder("order-1")

    @Test
    fun `already delivered unchanged deadline is not scheduled twice`() {
        val alarm = alarm(deadline = 1_000)

        val plan = planOperationalAlarmReconciliation(
            previousFingerprints = setOf(alarm.fingerprint),
            deliveredFingerprints = setOf(alarm.fingerprint),
            desired = listOf(alarm),
        )

        assertTrue(plan.cancelTags.isEmpty())
        assertTrue(plan.schedule.isEmpty())
        assertEquals(setOf(alarm.fingerprint), plan.deliveredAfterCleanup)
    }

    @Test
    fun `deadline change cancels old identity clears delivery and schedules replacement`() {
        val old = alarm(deadline = 1_000)
        val replacement = alarm(deadline = 2_000)

        val plan = planOperationalAlarmReconciliation(
            previousFingerprints = setOf(old.fingerprint),
            deliveredFingerprints = setOf(old.fingerprint),
            desired = listOf(replacement),
        )

        assertEquals(setOf(old.tag), plan.cancelTags)
        assertEquals(listOf(replacement), plan.schedule)
        assertTrue(plan.deliveredAfterCleanup.isEmpty())
    }

    @Test
    fun `resolved work cancels alarm and visible notification identity`() {
        val old = alarm(deadline = 1_000)

        val plan = planOperationalAlarmReconciliation(
            previousFingerprints = setOf(old.fingerprint),
            deliveredFingerprints = setOf(old.fingerprint),
            desired = emptyList(),
        )

        assertEquals(setOf(old.tag), plan.cancelTags)
        assertTrue(plan.schedule.isEmpty())
        assertTrue(plan.deliveredAfterCleanup.isEmpty())
    }

    @Test
    fun `boot forgets delivered reminders because Android removed their notifications`() {
        assertTrue(
            deliveredFingerprintsAfterSystemReschedule(
                action = Intent.ACTION_BOOT_COMPLETED,
                deliveredFingerprints = setOf("held-order-1|1000"),
            ).isEmpty(),
        )
    }

    @Test
    fun `package and permission reschedule do not duplicate delivered reminder`() {
        val delivered = setOf("held-order-1|1000")

        assertEquals(
            delivered,
            deliveredFingerprintsAfterSystemReschedule(Intent.ACTION_MY_PACKAGE_REPLACED, delivered),
        )
        assertEquals(
            delivered,
            deliveredFingerprintsAfterSystemReschedule(
                "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
                delivered,
            ),
        )
    }

    private fun alarm(deadline: Long) = OperationalAlarmSpec(
        kind = OperationalAlarmKind.HELD_ORDER,
        tag = "held-order-order-1",
        triggerAtMillis = deadline,
        title = "Held order",
        body = "Review",
        target = target,
    )
}
