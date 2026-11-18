import { useEffect, useRef, useState } from 'react';
import Modal from '@/components/ui/Modal';
import { inr } from '@/lib/inr';
import {
  pos, type OrderDTO, type RetainedMistakeCorrectionRequest, type RetainedMistakeReviewDTO,
} from '@/lib/erp-api';
import { createOperationKey, isBusinessRuleApiError, isCheckoutClaimRejection } from '@/lib/retry-drafts';

type Review = { order: OrderDTO; evidence: RetainedMistakeReviewDTO; claimToken: string };

export default function RetainedMistakeCorrectionDialog({ order, onClose, onCorrected }: {
  order: OrderDTO;
  onClose: () => void;
  onCorrected: (orderId: string) => void;
}) {
  const [review, setReview] = useState<Review | null>(null);
  const [reason, setReason] = useState('');
  const [attested, setAttested] = useState(false);
  const [busy, setBusy] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [submitted, setSubmitted] = useState(false);
  const key = useRef(createOperationKey());
  const attempt = useRef<RetainedMistakeCorrectionRequest | null>(null);
  const inFlight = useRef(false);

  useEffect(() => {
    let active = true;
    let token: string | null = null;
    void (async () => {
      try {
        const current = await pos.getOrder(order.id);
        if (!active) return;
        if (current.status !== 'held' || current.checkout_version !== order.checkout_version
          || current.total_minor !== order.total_minor
          || !current.gaming_void_provenance?.mistake_correction_available) {
          throw new Error('This bill changed. Close this review and reload POS; nothing was cancelled.');
        }
        const claim = await pos.claimCheckout(order.id);
        token = claim.claim_token;
        if (!active) return;
        const evidence = await pos.reviewRetainedMistake(order.id, token);
        if (!active) return;
        if (claim.order_id !== order.id || claim.order_version !== current.checkout_version
          || claim.order_total_minor !== current.total_minor || claim.paid_minor !== 0
          || evidence.order_id !== current.id || evidence.expected_checkout_version !== current.checkout_version
          || evidence.total_minor !== current.total_minor || evidence.item_count !== current.lines.length
          || !/^[0-9a-f]{64}$/.test(evidence.review_hash)
          || evidence.original_receipt_id !== current.gaming_void_provenance.original_receipt_id
          || evidence.gaming_session_id !== current.gaming_void_provenance.gaming_session_id) {
          throw new Error('The retained bill changed during review. Close this review and reload POS.');
        }
        setReview({ order: current, evidence, claimToken: token });
      } catch (failure) {
        if (active) setError((failure as Error).message);
      } finally {
        if (active) setBusy(false);
        else if (token) void pos.releaseCheckout(order.id, token).catch(() => undefined);
      }
    })();
    return () => {
      active = false;
      if (token && !attempt.current) void pos.releaseCheckout(order.id, token).catch(() => undefined);
    };
  }, [order.id, order.checkout_version, order.total_minor]);

  async function confirm() {
    if (!review || inFlight.current || (!attempt.current && (!attested || reason.trim().length < 3))) return;
    inFlight.current = true;
    attempt.current ??= {
      original_receipt_id: review.evidence.original_receipt_id,
      expected_checkout_version: review.evidence.expected_checkout_version,
      review_hash: review.evidence.review_hash,
      all_items_mistaken_not_supplied: true,
      reason: reason.trim(),
    };
    setSubmitted(true);
    setBusy(true);
    setError(null);
    try {
      const result = await pos.correctRetainedMistake(order.id, attempt.current, review.claimToken, key.current);
      if (result.order_id !== order.id || result.order_status !== 'void' || !result.correction_id
        || result.original_receipt_id !== review.evidence.original_receipt_id
        || result.gaming_session_id !== review.evidence.gaming_session_id) {
        throw new Error('The correction receipt could not be verified. Retry this same cancellation or reload POS.');
      }
      onCorrected(order.id);
    } catch (failure) {
      const refused = isBusinessRuleApiError(failure) || isCheckoutClaimRejection(failure);
      setError(`${(failure as Error).message}${refused
        ? ' The server refused this cancellation. Close and refresh POS before another action.'
        : ' The result is unresolved. Retry this same cancellation; collect no money. If you close this review, refresh POS before another action.'}`);
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }

  return (
    <Modal open locked={busy} title="Cancel mistaken items" onClose={() => { if (!busy) onClose(); }}>
      <div className="space-y-4">
        <p className="text-sm text-fg-muted">Use this only when every retained item was added by mistake and none were supplied. Original Gaming and bill history stays intact. No payment, refund or stock adjustment is recorded.</p>
        {review && <div className="rounded-xl border border-bg-border p-3">
          <p className="font-semibold">{review.order.source_label || 'Retained Gaming bill'}</p>
          {review.order.lines.map((line, index) => <div key={line.id ?? `${line.menu_item_id}:${index}`} className="flex justify-between gap-3 text-sm"><span>{line.qty} × {line.name}</span><span>{inr(line.line_total_minor)}</span></div>)}
          <p className="mt-2 font-semibold">Whole bill: {inr(review.order.total_minor)}</p>
        </div>}
        <label className="block text-sm">Reason
          <textarea className="input mt-1 w-full" maxLength={500} value={reason} disabled={busy || submitted}
            onChange={(event) => setReason(event.target.value)} />
        </label>
        <label className="flex items-start gap-2 text-sm"><input type="checkbox" checked={attested} disabled={busy || submitted}
          onChange={(event) => setAttested(event.target.checked)} />Every retained item was added by mistake and none were supplied.</label>
        {error && <p role="alert" className="text-sm text-accent-bad">{error}</p>}
        <div className="flex flex-wrap justify-end gap-2">
          <button type="button" className="btn btn-ghost" disabled={busy} onClick={onClose}>{submitted ? 'Close' : 'Keep bill'}</button>
          <button type="button" className="btn btn-ghost text-accent-bad" disabled={busy || !review || !attested || reason.trim().length < 3}
            onClick={() => void confirm()}>{busy ? 'Verifying…' : submitted ? 'Retry same cancellation' : 'Cancel mistaken items'}</button>
        </div>
      </div>
    </Modal>
  );
}
