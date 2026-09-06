package cloud.dcompany.erp.core.remote

import java.time.Instant
import kotlinx.coroutines.runBlocking
import cloud.dcompany.erp.ui.Destination
import cloud.dcompany.erp.ui.remote.remoteRouteKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteAssistancePolicyTest {

    @Test
    fun `heartbeat stays below half of backend online window`() {
        assertTrue(REMOTE_HEARTBEAT_INTERVAL_MILLIS * 2L < REMOTE_BACKEND_ONLINE_WINDOW_MILLIS)
        assertEquals(20_000L, REMOTE_HEARTBEAT_INTERVAL_MILLIS)
        assertEquals(45_000L, REMOTE_BACKEND_ONLINE_WINDOW_MILLIS)
    }

    @Test
    fun `frame cadence stays safely above backend decode throttle`() {
        assertEquals(4_000L, REMOTE_FRAME_INTERVAL_MILLIS)
        assertTrue(
            REMOTE_FRAME_INTERVAL_MILLIS >= REMOTE_BACKEND_FRAME_THROTTLE_MILLIS * 2L,
        )
    }

    @Test
    fun `one time and anytime grants each require an explicit prompt`() {
        val oneTime = pendingRemoteGrant(
            grants = listOf(grant(GRANT_ONE, "one_time", requestedByName = "Owner One")),
            serverTimeRaw = SERVER_TIME,
            expectedInstallationId = INSTALLATION_ID,
            expectedUserId = REQUESTED_FOR_USER_ID,
        )
        val anytime = pendingRemoteGrant(
            grants = listOf(grant(GRANT_TWO, "anytime", requestedByName = null)),
            serverTimeRaw = SERVER_TIME,
            expectedInstallationId = INSTALLATION_ID,
            expectedUserId = REQUESTED_FOR_USER_ID,
        )

        assertEquals(RemoteGrantKind.ONE_TIME, oneTime?.kind)
        assertEquals("Owner One", oneTime?.requesterName)
        assertEquals(RemoteGrantKind.ANYTIME, anytime?.kind)
        assertEquals("D Company owner", anytime?.requesterName)
    }

    @Test
    fun `decision replay is exact grant scoped and never inherited by a new request`() {
        val mutation = PendingRemoteGrantDecision(
            grantId = GRANT_ONE,
            decision = RemoteGrantDecisionWire.ACCEPTED,
            decisionId = DECISION_ID,
        )
        val state = PersistedRemoteAssistanceState(
            consent = RemoteConsentChoice.ALLOWED.storedValue,
            grantId = GRANT_ONE,
            grantKind = RemoteGrantKind.ONE_TIME.storedValue,
            grantExpiresAt = "2026-08-30T10:10:00Z",
            pendingDecision = mutation,
        )

        assertEquals(mutation, replayableGrantDecision(state, GRANT_ONE))
        assertNull(replayableGrantDecision(state, GRANT_TWO))
    }

    @Test
    fun `grant prompt rejects duration beyond the disclosed local consent cap`() {
        assertNull(
            pendingRemoteGrant(
                grants = listOf(
                    grant(GRANT_ONE, "one_time", requestedByName = "Owner").copy(
                        requestedAt = "2026-08-30T09:00:00Z",
                    ),
                ),
                serverTimeRaw = SERVER_TIME,
                expectedInstallationId = INSTALLATION_ID,
                expectedUserId = REQUESTED_FOR_USER_ID,
            ),
        )
        assertNull(
            pendingRemoteGrant(
                grants = listOf(
                    grant(GRANT_TWO, "anytime", requestedByName = "Owner").copy(
                        requestedAt = "2026-08-29T09:00:00Z",
                        expiresAt = "2026-08-30T10:10:00Z",
                    ),
                ),
                serverTimeRaw = SERVER_TIME,
                expectedInstallationId = INSTALLATION_ID,
                expectedUserId = REQUESTED_FOR_USER_ID,
            ),
        )
    }

    @Test
    fun `anytime prompt admits 86400 remaining seconds and rejects 86401 clock safely`() {
        val exactBoundary = grant(GRANT_TWO, "anytime", requestedByName = "Owner").copy(
            requestedAt = SERVER_TIME,
            expiresAt = "2026-08-31T10:01:00Z",
        )
        val serverRemainingOverBoundary = exactBoundary.copy(
            requestedAt = "2026-08-30T10:01:01Z",
            expiresAt = "2026-08-31T10:01:01Z",
        )

        assertNotNull(
            pendingRemoteGrant(
                listOf(exactBoundary),
                SERVER_TIME,
                expectedInstallationId = INSTALLATION_ID,
                expectedUserId = REQUESTED_FOR_USER_ID,
            ),
        )
        assertNull(
            pendingRemoteGrant(
                listOf(serverRemainingOverBoundary),
                SERVER_TIME,
                expectedInstallationId = INSTALLATION_ID,
                expectedUserId = REQUESTED_FOR_USER_ID,
            ),
        )
        assertNull(
            pendingRemoteGrant(
                listOf(exactBoundary),
                "not-a-server-time",
                expectedInstallationId = INSTALLATION_ID,
                expectedUserId = REQUESTED_FOR_USER_ID,
            ),
        )
        assertNull(
            pendingRemoteGrant(
                listOf(exactBoundary.copy(requestedAt = "2026-08-30T10:01:31Z")),
                SERVER_TIME,
                expectedInstallationId = INSTALLATION_ID,
                expectedUserId = REQUESTED_FOR_USER_ID,
            ),
        )
    }

    @Test
    fun `grant prompt is bound to the exact authenticated staff user`() {
        val forCurrentUser = grant(GRANT_ONE, "one_time", requestedByName = "Owner")

        assertNotNull(
            pendingRemoteGrant(
                grants = listOf(forCurrentUser),
                serverTimeRaw = SERVER_TIME,
                expectedInstallationId = INSTALLATION_ID,
                expectedUserId = REQUESTED_FOR_USER_ID,
            ),
        )
        assertNull(
            pendingRemoteGrant(
                grants = listOf(forCurrentUser),
                serverTimeRaw = SERVER_TIME,
                expectedInstallationId = INSTALLATION_ID,
                expectedUserId = OTHER_REQUESTED_FOR_USER_ID,
            ),
        )
        assertNull(
            pendingRemoteGrant(
                grants = listOf(forCurrentUser.copy(requestedForUserId = "not-a-uuid")),
                serverTimeRaw = SERVER_TIME,
                expectedInstallationId = INSTALLATION_ID,
                expectedUserId = REQUESTED_FOR_USER_ID,
            ),
        )
    }

    @Test
    fun `grant authorization enforces expiry and binds one time to exactly one session`() {
        val oneTime = PersistedRemoteAssistanceState(
            consent = RemoteConsentChoice.ALLOWED.storedValue,
            grantId = GRANT_ONE,
            grantKind = RemoteGrantKind.ONE_TIME.storedValue,
            grantExpiresAt = "2026-08-30T10:10:00Z",
        )

        assertEquals(
            RemoteGrantSessionAuthorization(true, bindOneTimeSession = true),
            evaluatePersistedGrantAuthorization(oneTime, GRANT_ONE, SESSION_ID, SERVER_TIME),
        )
        assertTrue(
            evaluatePersistedGrantAuthorization(
                oneTime.copy(oneTimeSessionId = SESSION_ID),
                GRANT_ONE,
                SESSION_ID,
                SERVER_TIME,
            ).authorized,
        )
        assertFalse(
            evaluatePersistedGrantAuthorization(
                oneTime.copy(oneTimeSessionId = SESSION_ID),
                GRANT_ONE,
                OTHER_SESSION_ID,
                SERVER_TIME,
            ).authorized,
        )
        assertFalse(
            evaluatePersistedGrantAuthorization(
                oneTime,
                GRANT_ONE,
                SESSION_ID,
                "2026-08-30T10:10:00Z",
            ).authorized,
        )
        assertTrue(
            evaluatePersistedGrantAuthorization(
                oneTime.copy(oneTimeSessionId = SESSION_ID),
                GRANT_ONE,
                SESSION_ID,
                "2026-08-30T10:10:00Z",
            ).authorized,
        )
        assertFalse(
            evaluatePersistedGrantAuthorization(
                oneTime.copy(oneTimeSessionId = SESSION_ID),
                GRANT_ONE,
                OTHER_SESSION_ID,
                "2026-08-30T10:10:00Z",
            ).authorized,
        )
        assertTrue(
            evaluatePersistedGrantAuthorization(
                oneTime.copy(grantKind = RemoteGrantKind.ANYTIME.storedValue),
                GRANT_ONE,
                OTHER_SESSION_ID,
                SERVER_TIME,
            ).authorized,
        )
    }

    @Test
    fun `session uses server time and elapsed deadline and rejects expiry or wrong device`() {
        val valid = evaluateRemoteSession(
            session = session(),
            serverTimeRaw = "2026-08-30T10:05:00Z",
            nowElapsedMillis = 50_000L,
            expectedInstallationId = INSTALLATION_ID,
        )

        assertEquals(SESSION_ID, valid.active?.sessionId)
        assertEquals(GRANT_ONE, valid.active?.grantId)
        assertEquals(350_000L, valid.active?.deadlineElapsedMillis)
        assertNull(
            evaluateRemoteSession(
                session = session(),
                serverTimeRaw = "2026-08-30T10:10:00Z",
                nowElapsedMillis = 50_000L,
                expectedInstallationId = INSTALLATION_ID,
            ).active,
        )
        assertEquals(
            "invalid_installation",
            evaluateRemoteSession(
                session = session(),
                serverTimeRaw = "2026-08-30T10:05:00Z",
                nowElapsedMillis = 50_000L,
                expectedInstallationId = OTHER_INSTALLATION_ID,
            ).rejectionReason,
        )
    }

    @Test
    fun `repeat polls can never extend an admitted session deadline`() {
        val original = RemoteActiveSession(
            sessionId = SESSION_ID,
            grantId = GRANT_ONE,
            expiresAt = Instant.parse("2026-08-30T10:10:00Z"),
            deadlineElapsedMillis = 600_000L,
        )
        val laterPoll = original.copy(
            expiresAt = Instant.parse("2026-08-30T10:15:00Z"),
            deadlineElapsedMillis = 900_000L,
        )

        assertEquals(original, clampRemoteSessionDeadline(original, laterPoll))
        val differentSession = laterPoll.copy(sessionId = OTHER_SESSION_ID)
        assertEquals(
            differentSession,
            clampRemoteSessionDeadline(original, differentSession),
        )
    }

    @Test
    fun `capture policy is closed and hides every sensitive ERP route`() {
        val audited = setOf(Destination.Help)
        Destination.entries.forEach { destination ->
            val expected = if (destination in audited) {
                RemoteCaptureDisposition.APP_WINDOW
            } else {
                RemoteCaptureDisposition.PRIVACY_PLACEHOLDER
            }
            assertEquals(
                "Unexpected capture decision for ${destination.name}",
                expected,
                remoteCaptureDisposition(
                    destination.remoteRouteKey(),
                    sensitiveOverlayVisible = false,
                    appForeground = true,
                ),
            )
        }
        for (route in listOf(null, "login", "payment", "void", "shift_close", "unknown")) {
            assertEquals(
                RemoteCaptureDisposition.PRIVACY_PLACEHOLDER,
                remoteCaptureDisposition(route, sensitiveOverlayVisible = false, appForeground = true),
            )
        }
        assertEquals(
            RemoteCaptureDisposition.PRIVACY_PLACEHOLDER,
            remoteCaptureDisposition("gaming", sensitiveOverlayVisible = false, appForeground = true),
        )
        assertEquals(
            RemoteCaptureDisposition.PRIVACY_PLACEHOLDER,
            remoteCaptureDisposition("gaming", sensitiveOverlayVisible = true, appForeground = true),
        )
        assertEquals(
            RemoteCaptureDisposition.PRIVACY_PLACEHOLDER,
            remoteCaptureDisposition("gaming", sensitiveOverlayVisible = false, appForeground = false),
        )
    }

    @Test
    fun `any route or privacy revision during PixelCopy discards captured bytes`() {
        val privacy = RemotePrivacySnapshot(blocked = false, revision = 4L)
        assertTrue(
            mustReplaceWithPrivacyPlaceholder(
                capturedPrivacyPlaceholder = false,
                beforeRoute = RemoteUiRouteSnapshot("gaming", 7L),
                afterRoute = RemoteUiRouteSnapshot("gaming", 9L),
                beforePrivacy = privacy,
                afterPrivacy = privacy,
                afterDisposition = RemoteCaptureDisposition.APP_WINDOW,
            ),
        )
        assertTrue(
            mustReplaceWithPrivacyPlaceholder(
                capturedPrivacyPlaceholder = false,
                beforeRoute = RemoteUiRouteSnapshot("gaming", 7L),
                afterRoute = RemoteUiRouteSnapshot("gaming", 7L),
                beforePrivacy = privacy,
                afterPrivacy = privacy.copy(revision = 5L),
                afterDisposition = RemoteCaptureDisposition.APP_WINDOW,
            ),
        )
        assertFalse(
            mustReplaceWithPrivacyPlaceholder(
                capturedPrivacyPlaceholder = false,
                beforeRoute = RemoteUiRouteSnapshot("gaming", 7L),
                afterRoute = RemoteUiRouteSnapshot("gaming", 7L),
                beforePrivacy = privacy,
                afterPrivacy = privacy,
                afterDisposition = RemoteCaptureDisposition.APP_WINDOW,
            ),
        )
    }

    @Test
    fun `command parser accepts only session-bound semantic allowlist`() {
        val active = RemoteActiveSession(
            sessionId = SESSION_ID,
            grantId = GRANT_ONE,
            expiresAt = Instant.parse("2026-08-30T10:10:00Z"),
            deadlineElapsedMillis = 600_000L,
        )
        val navigate = validateRemoteCommand(command(type = "navigate", module = "help"), active, SERVER_TIME, true)
        assertEquals(RemoteSemanticCommand.Navigate(RemoteErpModule.HELP), navigate.command)

        for ((type, module) in listOf(
            "tap" to null,
            "coordinate" to null,
            "end_session" to null,
            "sync_now" to null,
            "navigate" to "gaming",
            "navigate" to "finance",
            "refresh" to "gaming",
        )) {
            assertNull(validateRemoteCommand(command(type = type, module = module), active, SERVER_TIME, true).command)
        }
        assertEquals(
            "session_inactive",
            validateRemoteCommand(
                command(type = "collect_diagnostics", sessionId = OTHER_SESSION_ID),
                active,
                SERVER_TIME,
                true,
            ).rejectionReason,
        )
        assertEquals(
            "not_in_foreground",
            validateRemoteCommand(command(type = "collect_diagnostics"), active, SERVER_TIME, false).rejectionReason,
        )
        assertEquals(
            "permission_denied",
            validateRemoteCommand(
                command = command(type = "navigate", module = "gaming"),
                session = active,
                serverTimeRaw = SERVER_TIME,
                foreground = true,
                sensitiveOverlayVisible = true,
            ).rejectionReason,
        )
    }

    @Test
    fun `semantic UI and consent admission is help only and defers for sensitive overlays`() {
        Destination.entries.forEach { destination ->
            assertEquals(
                destination == Destination.Help,
                remoteSemanticUiAdmission(
                    routeKey = destination.remoteRouteKey(),
                    sensitiveOverlayVisible = false,
                ),
            )
        }
        assertFalse(remoteSemanticUiAdmission("help", sensitiveOverlayVisible = true))
        assertFalse(remoteSemanticUiAdmission("help", sensitiveOverlayVisible = false, appForeground = false))
        assertFalse(remoteSemanticUiAdmission("pos", sensitiveOverlayVisible = false))
        assertFalse(remoteSemanticUiAdmission("shift", sensitiveOverlayVisible = false))
        assertFalse(remoteSemanticUiAdmission("dashboard", sensitiveOverlayVisible = false))
        assertFalse(remoteSemanticUiAdmission("gaming", sensitiveOverlayVisible = false))
    }

    @Test
    fun `diagnostics admission rejects any route transition before completion`() {
        val admitted = RemoteUiRouteSnapshot("help", revision = 10L)
        assertTrue(remoteUiAdmissionRemainedStable(admitted, admitted))
        assertFalse(
            remoteUiAdmissionRemainedStable(
                admitted,
                admitted.copy(revision = 11L),
            ),
        )
        assertFalse(
            remoteUiAdmissionRemainedStable(
                admitted,
                RemoteUiRouteSnapshot("pos", revision = 11L),
            ),
        )
        val private = RemotePrivacySnapshot(blocked = false, revision = 3L)
        assertTrue(remotePrivacyAdmissionRemainedStable(private, private))
        assertFalse(
            remotePrivacyAdmissionRemainedStable(
                private,
                private.copy(revision = 4L),
            ),
        )
        assertFalse(remotePrivacyAdmissionRemainedStable(private, private.copy(blocked = true)))
    }

    @Test
    fun `multi command response executes nothing even when contiguous`() = runBlocking {
        val attempted = mutableListOf<Long>()
        val first = command(type = "refresh", commandId = COMMAND_ID, sequence = 1L)
        val second = command(type = "collect_diagnostics", commandId = OTHER_COMMAND_ID, sequence = 2L)

        processRemoteCommandsInOrder(listOf(second, first), expectedSequence = 1L) { command ->
            attempted += command.sequence
            command.sequence != 1L
        }

        assertTrue(attempted.isEmpty())
    }

    @Test
    fun `missing or duplicate expected command sequence executes nothing past the gap`() = runBlocking {
        val attempted = mutableListOf<Long>()
        val missingFirst = listOf(command(type = "collect_diagnostics", sequence = 2L))
        assertFalse(remoteCommandBatchHasContiguousSequence(missingFirst, expectedSequence = 1L))
        processRemoteCommandsInOrder(
            commands = missingFirst,
            expectedSequence = 1L,
        ) { command ->
            attempted += command.sequence
            true
        }
        assertTrue(attempted.isEmpty())

        val duplicateFirst = listOf(
            command(type = "refresh", commandId = COMMAND_ID, sequence = 1L),
            command(type = "collect_diagnostics", commandId = OTHER_COMMAND_ID, sequence = 1L),
        )
        assertFalse(remoteCommandBatchHasContiguousSequence(duplicateFirst, expectedSequence = 1L))
        processRemoteCommandsInOrder(
            commands = duplicateFirst,
            expectedSequence = 1L,
        ) { command ->
            attempted += command.sequence
            true
        }
        assertTrue(attempted.isEmpty())

        val replayBelowAcknowledged = listOf(
            command(type = "refresh", commandId = COMMAND_ID, sequence = 1L),
            command(type = "collect_diagnostics", commandId = OTHER_COMMAND_ID, sequence = 2L),
        )
        assertFalse(
            remoteCommandBatchHasContiguousSequence(
                commands = replayBelowAcknowledged,
                expectedSequence = 2L,
                expectedSessionId = SESSION_ID,
            ),
        )
        assertFalse(
            remoteCommandBatchHasContiguousSequence(
                commands = listOf(command(type = "refresh", sessionId = OTHER_SESSION_ID)),
                expectedSequence = 1L,
                expectedSessionId = SESSION_ID,
            ),
        )
    }

    @Test
    fun `command flood batches of two or one hundred fail closed`() {
        val two = listOf(
            command(type = "refresh", commandId = COMMAND_ID, sequence = 1L),
            command(type = "collect_diagnostics", commandId = OTHER_COMMAND_ID, sequence = 2L),
        )
        val hundred = List(100) { index ->
            command(
                type = "refresh",
                commandId = "command-${index + 1}",
                sequence = index + 1L,
            )
        }

        assertFalse(remoteCommandBatchHasContiguousSequence(two, expectedSequence = 1L))
        assertFalse(remoteCommandBatchHasContiguousSequence(hundred, expectedSequence = 1L))
        assertTrue(
            remoteCommandBatchHasContiguousSequence(
                listOf(command(type = "refresh", sequence = 1L)),
                expectedSequence = 1L,
                expectedSessionId = SESSION_ID,
            ),
        )
    }

    @Test
    fun `stopped expired or unindicated session cannot continue a command`() {
        val active = RemoteActiveSession(
            sessionId = SESSION_ID,
            grantId = GRANT_ONE,
            expiresAt = Instant.parse("2026-08-30T10:10:00Z"),
            deadlineElapsedMillis = 2_000L,
        )
        assertTrue(remoteCommandExecutionAllowed(active, active, true, true, 1_000L))
        assertFalse(remoteCommandExecutionAllowed(active, null, true, true, 1_000L))
        assertFalse(remoteCommandExecutionAllowed(active, active, false, true, 1_000L))
        assertFalse(remoteCommandExecutionAllowed(active, active, true, false, 1_000L))
        assertFalse(remoteCommandExecutionAllowed(active, active, true, true, 2_000L))
    }

    @Test
    fun `frame dimensions remain low resolution and preserve aspect ratio`() {
        val (width, height) = boundedFrameDimensions(2_560, 1_600)

        assertTrue(width <= REMOTE_FRAME_MAX_WIDTH)
        assertTrue(height <= REMOTE_FRAME_MAX_HEIGHT)
        assertEquals(864, width)
        assertEquals(540, height)
        assertTrue(remoteFrameDimensionsAccepted(256, 540))
        assertFalse(remoteFrameDimensionsAccepted(239, 540))
        assertFalse(remoteFrameDimensionsAccepted(960, 179))
    }

    @Test
    fun `notification loss rejects the next frame before capture or upload`() {
        assertTrue(
            remoteFrameAdmissionAllowed(
                appForeground = true,
                notificationVisible = true,
                nowElapsedMillis = 1_000L,
                deadlineElapsedMillis = 2_000L,
                activeSessionMatches = true,
            ),
        )
        assertFalse(
            remoteFrameAdmissionAllowed(
                appForeground = true,
                notificationVisible = false,
                nowElapsedMillis = 1_000L,
                deadlineElapsedMillis = 2_000L,
                activeSessionMatches = true,
            ),
        )
    }

    @Test
    fun `command receipt replacement is bounded and idempotent by command id`() {
        val original = receipt(COMMAND_ID, sequence = 1L, outcome = "acknowledged")
        val replacement = receipt(COMMAND_ID, sequence = 1L, outcome = "rejected", reason = "execution_failed")
        val second = receipt(OTHER_COMMAND_ID, sequence = 2L, outcome = "acknowledged")

        val result = appendBoundedRemoteReceipt(listOf(original, second), replacement, maximum = 2)

        assertEquals(2, result.size)
        assertEquals(replacement, result.last())
        assertEquals(1, result.count { it.commandId == COMMAND_ID })
        assertNotNull(validRemoteAssistanceState(PersistedRemoteAssistanceState(receipts = result)))
    }

    private fun grant(id: String, kind: String, requestedByName: String?) = RemoteGrantResponse(
        id = id,
        installationId = INSTALLATION_ID,
        kind = kind,
        status = "requested",
        requestedByUserId = REQUESTER_ID,
        requestedByName = requestedByName,
        requestedForUserId = REQUESTED_FOR_USER_ID,
        requestedForName = "Staff One",
        requestedAt = "2026-08-30T09:59:00Z",
        expiresAt = "2026-08-30T10:10:00Z",
    )

    private fun session() = RemoteSessionResponse(
        id = SESSION_ID,
        installationId = INSTALLATION_ID,
        grantId = GRANT_ONE,
        status = "active",
        durationSeconds = 600,
        requestedByUserId = REQUESTER_ID,
        requestedAt = "2026-08-30T09:58:00Z",
        requestExpiresAt = "2026-08-30T10:00:00Z",
        startedAt = "2026-08-30T10:00:00Z",
        expiresAt = "2026-08-30T10:10:00Z",
        nextSequence = 2L,
    )

    private fun command(
        type: String,
        module: String? = null,
        sessionId: String = SESSION_ID,
        commandId: String = COMMAND_ID,
        sequence: Long = 1L,
    ) = RemoteCommandResponse(
        commandId = commandId,
        sessionId = sessionId,
        sequence = sequence,
        type = type,
        module = module,
        status = "pending",
        issuedByUserId = REQUESTER_ID,
        issuedAt = "2026-08-30T10:00:30Z",
    )

    private fun receipt(
        commandId: String,
        sequence: Long,
        outcome: String,
        reason: String? = null,
    ) = RemoteCommandReceipt(
        sessionId = SESSION_ID,
        commandId = commandId,
        sequence = sequence,
        state = RECEIPT_COMPLETED,
        outcome = outcome,
        reasonCode = reason,
    )

    private companion object {
        const val SERVER_TIME = "2026-08-30T10:01:00Z"
        const val INSTALLATION_ID = "11111111-1111-4111-8111-111111111111"
        const val OTHER_INSTALLATION_ID = "11111111-1111-4111-8111-111111111112"
        const val GRANT_ONE = "22222222-2222-4222-8222-222222222222"
        const val GRANT_TWO = "33333333-3333-4333-8333-333333333333"
        const val SESSION_ID = "44444444-4444-4444-8444-444444444444"
        const val OTHER_SESSION_ID = "44444444-4444-4444-8444-444444444445"
        const val COMMAND_ID = "55555555-5555-4555-8555-555555555555"
        const val OTHER_COMMAND_ID = "55555555-5555-4555-8555-555555555556"
        const val REQUESTER_ID = "66666666-6666-4666-8666-666666666666"
        const val REQUESTED_FOR_USER_ID = "68686868-6868-4868-8868-686868686868"
        const val OTHER_REQUESTED_FOR_USER_ID = "69696969-6969-4969-8969-696969696969"
        const val DECISION_ID = "77777777-7777-4777-8777-777777777777"
    }
}
