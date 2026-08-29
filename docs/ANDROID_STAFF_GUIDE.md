# D Company Android staff shift guide

Use this guide for the native Android app (`cloud.dcompany.erp`). Screens shown
to each employee depend on their role. If a required action is missing, ask the
shift owner or manager; do not share accounts or try to bypass permissions.

GST verification is outside the current app acceptance scope. Staff should
still check the final amount and payment method on every receipt, but this guide
does not certify tax configuration.

## 1. Start the tablet and sign in

1. Confirm the tablet has power and the expected Wi-Fi/mobile connection.
   After a reboot, unlock the tablet once so Android can reopen its protected
   local database and restore operational reminders.
2. Open **D Company** and sign in with your own staff account.
3. If Android asks, allow notifications. If the app shows an alarm-access card,
   open Android settings and allow the exact-alarm access used for gaming and
   overdue-bill reminders.
4. Confirm the shop name shown in the app is correct. The active Gaming Centre
   build uses one Hybrid workspace automatically, so it does not ask staff to
   choose between POS and Gaming terminals. Stop and ask a manager if setup
   verification fails.
5. Read any offline, pending-sync, update-required, or access-changed notice
   before taking money.

Never uninstall the app or clear its storage when work is waiting to sync.

## 2. Open the shift

1. Open **Shift**. If a shift is already open, check **Opened by** and confirm it
   is the intended shift for this shop.
2. If no shift is open, count the cash already in the drawer.
3. Enter that amount as **Opening float** and tap **Open shift**.
4. Wait for success or a clear saved-offline notice. POS and Gaming money actions
   use this same verified Hybrid-workspace shift; never create a replacement
   shift merely because the server is slow.

## 3. Take a seated food or shisha order

1. Open **Tables** and select the guest's table.
2. Add the first round. Use `+`/`−` for quantity and add a special request such
   as `no sugar` or `less spicy` to the correct line.
3. Review the table and estimate, then tap **Send round to Kitchen**.
4. Use **Add another round** for later items. Do not open a second table bill for
   the same guests.
5. To remove a released item, tap **Cancel item**, enter the real reason, and
   confirm. The cancelled line remains visible until Kitchen acknowledges it.
6. When the guests request the bill and all billable items are correct, tap
   **Send to POS**. The table bill becomes read-only while the cashier settles
   the held order.

If you back out with an unsent round, choose **Keep editing** to preserve it or
**Discard round** only when you intentionally want to remove the unsent work.

## 4. Work the Kitchen Display System

1. Open **Kitchen**. New table rounds appear as tickets with quantities, round
   numbers, special requests, and waiting time.
2. Advance each ticket in order: **New → Preparing → Ready → Served**.
3. For a red cancellation request, stop preparing that item, read the reason,
   and tap **Acknowledge cancellation**. Do not acknowledge before Kitchen has
   acted on it.
4. Use **Refresh** or the saved-update review when the app reports a sync issue;
   do not repeat status taps blindly.
5. Tap **Exit KDS** to return to the normal app navigation.

## 5. Run a gaming/lounge session

1. Open **Gaming** and select the correct PS5, VR, simulator, streaming, or
   shisha station.
2. Tap **Start**, choose the booked/open duration, add the member phone only when
   applicable, and verify the timer starts.
3. Keep the app's notification/alarm access enabled. An alarm is a reminder, not
   a substitute for checking the active-session screen.
   If notification access is denied, Android cannot show the reminder. If
   precise-alarm access is denied or later revoked, the app keeps an inexact
   backup, but Android may deliver it late. Follow the red recovery card in
   Gaming or POS instead of assuming the timer stopped.
4. When service ends, tap **Stop** and verify the billable minutes and amount.
5. Tap **Send to POS**. The cashier receives an unpaid held order.
6. If the stopped session should not be charged, use **Cancel / void** and enter
   the genuine reason instead. Never send it to POS and also recreate it as a
   manual product.

The timer must continue after reopening the app. If a station looks active on
another device but unavailable here, refresh and ask a manager before starting
a duplicate session.

Alarm details are intentionally hidden on a secured lock screen. Unlock the
tablet to see the table or station. Do not disable battery optimisation for the
app unless the owner has tested and approved that device setting; Android's
allow-while-idle alarm path is used without requesting a blanket power exemption.

## 6. Bill in POS

For a table or gaming bill:

1. Open **POS** and select the matching card under **Held orders**.
2. Verify the source/table/station, line items, quantities, and final server
   total before collecting payment.
3. Choose **Cash**, **UPI**, or **Card**.
4. For cash, enter the actual amount received and give only the change shown.
5. Confirm once and wait for the success, pending, or failure message. Do not tap
   again or collect again while confirmation is pending.

For a direct counter sale, add products in POS, adjust quantity with `+`/`−`,
review the cart, and follow the same payment steps.

The final checkout can differ from a simple line estimate because the backend
confirms discounts, membership benefits, rounding, and configured taxes. Check
the final receipt total and payment method. If the server refuses a saved
payment, stop, keep the evidence on screen, and ask an owner to reconcile it;
do not create a second sale.

## 7. Work safely offline

The app stores supported actions on the tablet and retries them when the server
returns. Offline does not mean every action is unrestricted.

- Read the offline banner and saved-action count.
- Continue only when the app explicitly says the action was saved on this
  tablet.
- Never collect the same payment again because its invoice number has not yet
  appeared.
- Do not sign out, switch account, uninstall, clear storage, or
  factory-reset the tablet with pending work.
- When the connection returns, leave the app open until the waiting count clears
  and the server-confirmed outcome appears.
- Review any rejected item. Retry only when the app offers a retry and the cause
  has been corrected; otherwise escalate to an owner.

After reconnection, confirm the sale appears once in order history/reporting.
Duplicate-looking payments or a price-changed-offline warning require manager
review before more money is collected.

## 8. Close the shift

Before closing, confirm all of these are resolved:

- no active gaming or lounge session;
- no table bill still waiting to be sent or paid;
- no unpaid held order in POS;
- no Kitchen cancellation awaiting acknowledgement;
- no pending/rejected money action or sync warning.

Then:

1. Open **Shift** and tap **Refresh** while online.
2. Check **Opened by**, opening float, **Expected in drawer**, gross collections,
   settled refunds, and net collections. POS receipts can include non-cash
   payments; **Expected in drawer** is the cash count target.
3. Physically count the drawer and enter the quantity of each denomination. The
   app calculates the counted amount and shows `balanced`, `over`, or `short`.
4. Investigate a difference; do not adjust the count merely to force `balanced`.
5. Tap **Close shift**, recheck the counted total in the confirmation, and
   confirm once.
6. Wait for **Shift closed** from the server. If the close is saved offline or
   rejected, the shift is not finished: reconnect or resolve the stated blocker
   and retry through the app.

## 9. Sign out

Sign out only after the shift is server-confirmed closed and the pending-sync
count is zero. The sign-out dialog correctly warns that signing out does not
close an open shift. If another employee is taking over, follow the manager's
shift handover procedure rather than sharing the current login.

## When to stop and call a manager

Stop taking new payments on that tablet when the shop/workspace cannot be verified, more
than one open shift is reported, a payment outcome is ambiguous or rejected,
the app says access changed, totals do not match the intended bill, or saved work
cannot sync. Keep the tablet and app data intact so the manager can reconcile the
original action and audit trail.
