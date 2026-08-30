import { ConfirmModal } from '@/components/ui/ConfirmDialog';
import {
  DeviceCentreLoadError,
  DeviceCentreSkeleton,
} from './DeviceCentrePrimitives';
import { DeviceCentreView } from './DeviceCentreView';
import { DeviceKeyApprovalModal } from './DeviceKeyPairingPanel';
import { useDeviceCentreController } from './useDeviceCentreController';

export default function DeviceCentreScreen() {
  const controller = useDeviceCentreController();

  if (controller.loading && !controller.rows) return <DeviceCentreSkeleton />;

  if (!controller.rows) {
    return (
      <DeviceCentreLoadError
        message={controller.loadError || 'The protected device service did not return a response.'}
        onRetry={() => void controller.refresh()}
      />
    );
  }

  const selectedRow = controller.selectedRow;
  const confirmAction = controller.confirmAction;

  return (
    <>
      <DeviceCentreView
        rows={controller.rows}
        selectedId={controller.selectedId}
        refreshing={controller.refreshing}
        refreshError={controller.refreshError}
        actionError={controller.actionError}
        frame={controller.frame}
        grantKind={controller.grantKind}
        busyAction={controller.busyAction}
        onSelect={controller.selectDevice}
        onRefresh={() => void controller.refresh(true)}
        onGrantKindChange={controller.setGrantKind}
        onRequestGrant={() => {
          if (controller.grantKind === 'anytime') {
            controller.setConfirmAction({ type: 'request_anytime' });
          } else {
            void controller.runRequestGrant();
          }
        }}
        onConnect={() => void controller.runConnect()}
        onEnd={() => {
          if (selectedRow?.session) {
            controller.setConfirmAction({ type: 'end', sessionId: selectedRow.session.id });
          }
        }}
        onRevoke={() => {
          const grantId = selectedRow?.device.current_grant_id
            ?? selectedRow?.grant?.id
            ?? selectedRow?.session?.grant_id;
          if (grantId) controller.setConfirmAction({ type: 'revoke', grantId });
        }}
        onReviewPairing={controller.openPairing}
        onRevokeKey={(keyId) => controller.setConfirmAction({ type: 'revoke_key', keyId })}
        onCommand={(command) => void controller.runCommand(command)}
      />

      {confirmAction?.type === 'approve_key' ? (
        <DeviceKeyApprovalModal
          replacement={confirmAction.replacement}
          busy={controller.busyAction === 'approve_key'}
          error={controller.pairingError}
          retryAvailable={controller.pairingRetryAvailable}
          onSubmit={(pairingCode) => {
            void controller.runApproveDeviceKey(confirmAction.keyId, pairingCode);
          }}
          onCancel={controller.dismissConfirmAction}
        />
      ) : null}

      {confirmAction?.type === 'revoke_key' ? (
        <ConfirmModal
          title="Revoke this tablet key?"
          confirmLabel="Revoke key and stop access"
          danger
          onConfirm={() => void controller.runRevokeDeviceKey(confirmAction.keyId)}
          onCancel={controller.dismissConfirmAction}
          busy={controller.busyAction === 'revoke_key'}
          message={(
            <div className="space-y-3 text-sm text-fg-muted">
              <p>
                This removes cryptographic trust for this tablet and immediately ends its current
                access grant, requested or active session, and latest transient frame.
              </p>
              <p className="font-medium text-fg">
                This cannot be undone. Staff must enroll a new key on the physical tablet, then an
                owner must approve its new 12-character code before remote assistance can resume.
              </p>
            </div>
          )}
        />
      ) : null}

      {confirmAction?.type === 'request_anytime' ? (
        <ConfirmModal
          title="Request anytime access?"
          confirmLabel="Request anytime access"
          onConfirm={() => void controller.runRequestGrant()}
          onCancel={controller.dismissConfirmAction}
          busy={controller.busyAction === 'request'}
          message={(
            <div className="space-y-3 text-sm text-fg-muted">
              <p>
                The employee must approve this on the tablet. Once approved, an owner can start
                multiple 15-minute ERP-only sessions for up to 24 hours or until revoked.
              </p>
              <p className="font-medium text-fg">
                Use one-time access for normal support. Anytime access is for an agreed support
                window and remains visible and auditable.
              </p>
            </div>
          )}
        />
      ) : null}

      {confirmAction?.type === 'end' ? (
        <ConfirmModal
          title="End this assistance session?"
          confirmLabel="End session"
          danger
          onConfirm={() => void controller.runEnd(confirmAction.sessionId)}
          onCancel={controller.dismissConfirmAction}
          busy={controller.busyAction === 'end'}
          message="The live ERP view and all session controls will stop. An active anytime grant remains available until it expires or is revoked."
        />
      ) : null}

      {confirmAction?.type === 'revoke' ? (
        <ConfirmModal
          title="Emergency stop and revoke access?"
          confirmLabel="Stop and revoke"
          danger
          onConfirm={() => void controller.runRevoke(confirmAction.grantId)}
          onCancel={controller.dismissConfirmAction}
          busy={controller.busyAction === 'revoke'}
          message="This immediately revokes the employee-approved grant and terminates any requested or active assistance session. A new explicit approval will be required."
        />
      ) : null}
    </>
  );
}
