import SavedOwnerRecoveryLink from './SavedOwnerRecoveryLink';
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { paidBillWorkReadiness, readPaidBillEnvelope, subscribePaidBillWork } from '@/lib/paid-bill-journal';
import { currentPaidEndpoint } from '@/lib/paid-bill-api';
import { useAuth } from '@/modules/auth/AuthContext';

/** Generic device-private work status; never publishes an Owner command or reason. */
export default function PaidBillWorkNotice({ showColdEntry = true }: { showColdEntry?: boolean }) {
  const { me, terminalId } = useAuth();
  const [state, setState] = useState(() => paidBillWorkReadiness());
  useEffect(() => subscribePaidBillWork(() => setState(paidBillWorkReadiness())), []);
  if (state.verified && !state.pending && !state.busy) return null;
  let recoveryLink = '/operations?tab=orders';
  if (me?.audit_access && me.effective_permissions?.includes('finance.receipt.correct')) {
    try {
      const pending = readPaidBillEnvelope().pending;
      if (pending && pending.scope.endpoint === currentPaidEndpoint() && pending.scope.companyId === me.company_id && pending.scope.userId === me.user_id && pending.scope.terminalId === terminalId && (!me.branch_id || me.branch_id === pending.scope.branchId)) recoveryLink = '/operations?paid_bill_recovery=1&receipt=' + encodeURIComponent(pending.body.order_id);
    } catch { /* Unknown evidence stays retained and the generic history link remains. */ }
  }
  return <aside role="status" className="mb-3 rounded-xl border border-accent-gold/40 bg-accent-gold/10 p-3 text-sm">
    {showColdEntry && <SavedOwnerRecoveryLink/>}
    <strong>{!state.verified ? 'Owner bill-change recovery could not be verified.' : state.pending ? 'A saved Owner bill change needs recovery.' : 'A protected workspace operation is in progress.'}</strong>
    <p>Keep browser data. Use the original server, Owner and terminal; do not create another refund or replacement bill.</p>
    {me?.audit_access && me.effective_permissions?.includes('finance.receipt.correct') && <Link className="underline" to={recoveryLink}>Open receipt history for Owner review</Link>}
  </aside>;
}
