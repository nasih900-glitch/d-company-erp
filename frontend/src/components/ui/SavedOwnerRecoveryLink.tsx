import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { paidBillWorkReadiness, subscribePaidBillWork } from '@/lib/paid-bill-journal';
import { HISTORICAL_PAID_ROUTE } from '@/lib/paid-bill-historical-api';

/** Public entry only; never renders a journal field before fresh authentication. */
export default function SavedOwnerRecoveryLink() {
  const [ready, setReady] = useState(() => paidBillWorkReadiness());
  useEffect(() => subscribePaidBillWork(() => setReady(paidBillWorkReadiness())), []);
  return !ready.verified || ready.pending ? <Link className="block text-sm underline" to={HISTORICAL_PAID_ROUTE}>Recover saved Owner work</Link> : null;
}
