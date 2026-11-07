# Kharcha — manual test checklist

Tick each line as you try it on your phone. "Expect" is what should happen.

## 0. Install & first launch
- [ ] Install the new APK over the old one. Expect: opens, data still there.
- [ ] (Fresh install only) Welcome box asks "From which date should I track?" Expect: choices Last 7 days / This month / Last 30 / Last 90 / Pick a date; chosen date is shown; Start imports messages from that day into Inbox quietly (no flood of notifications).
- [ ] Send yourself a test card SMS later. Expect: a notification; if you swipe it away, the item is still in Inbox.

## 1. Today (Home)
- [ ] After a card statement SMS: a "You need to pay" card lists card, total, minimum, due date and "N days left".
- [ ] Colour: due in ≤3 days = amber, overdue = red, otherwise green.
- [ ] Tap a bill → "Mark as paid" removes it.
- [ ] When the bank's "payment received" SMS arrives for that card (≥ minimum) → bill disappears by itself.
- [ ] A newer statement for the same card replaces the older unpaid one.
- [ ] Zero-balance statements ("Total due 0") do not create a bill.
- [ ] "Others owe you", clarity ring, streak, accounts still show.

## 2. Inbox
- [ ] Top: underlined tabs Spent / Received / Auto / Refunds with counts. Below Spent: a joined switch All | Card | UPI. They look like two different levels.
- [ ] Search by name, amount, message text.
- [ ] Tap a person chip, then ✓ → tagged. Undo works from the snackbar.
- [ ] "More…" opens the full page with the people you already tapped pre-selected.
- [ ] In More, "As before" is NOT highlighted unless it is what's selected.
- [ ] Select → tick several → choose Tag and/or people → one ✓ applies to all. Summary line says what will happen before you press ✓.
- [ ] ✓ is disabled until you pick a tag or a person. Cancel clears the selection.
- [ ] "+ Similar" ticks the same merchant's other bills; "All" ticks everything shown.
- [ ] Press Back while selecting → selection clears (app stays open).

## 3. All (Transactions)
- [ ] Search an amount (e.g. 250) → matching bills show. Select several… → pick Me / a person / tag → ✓ → all update at once.
- [ ] Already-tagged bills can be re-assigned this way; money already settled stays settled.
- [ ] Filters: Type, Date, Tag, Status; Clear ✕ resets.

## 4. Owed
- [ ] Tick individual bills for a person and mark just those paid.
- [ ] Remind opens WhatsApp straight to the saved contact (no second contact pick).

## 5. Settings (gear icon)
- [ ] Opens full screen with sections: Payments, Tags, Appearance, Backup & restore, Import older messages, Remembered merchants, (Problem report if any).
- [ ] Typing in UPI ID / name words saves by itself (close and reopen to confirm). No Save button.
- [ ] Appearance Auto/Light/Dark switches immediately.
- [ ] Save backup → file saved. Restore asks "Restore this backup?" before replacing. Bills come back after restore.
- [ ] Remembered merchants collapsed by default; Show → Forget one.

## 6. Back button & other apps
- [ ] Back on Home closes the app (normal Android behaviour, not a crash). Back on another tab goes to Home first.
- [ ] Open the app, then open WhatsApp/Chrome, come back several times → state fine.
- [ ] Open the app in a floating window / split screen, resize it, rotate → no crash, nothing resets.
- [ ] If anything ever goes wrong: Settings → Problem report → Copy and send it to me.

## 7. Regression spot checks
- [ ] Statement check (Home) still reads your PDFs.
- [ ] Refund matching, failed payments, custom tags (edit/delete/hide) still work.
- [ ] Tag a merchant once → next SMS from it is auto-sorted.

## Safety and review fixes
- `StrictMatchTest` – automatic settling needs one clear name match (shared surname or ties never match).
- `DiagnosticsTest` – which unread messages are worth listing under Settings > Messages I couldn't read.
- Restore runs in one database transaction and keeps `before_restore.json` first; the database file is copied before an upgrade.
- Manual checks on phone: deny SMS permission (banner shows), first launch (welcome, then permissions, then name), "Wrong match? Undo" on an auto-matched payment.

## Complete backups
- `BackupCompleteTest` – many splits for one person survive, every setting (UPI id, tags, saved phone numbers, groups, start date) and flag is stored, and the "what is inside" summary shown before restoring.
- Saving reads the file back and compares counts; restoring checks counts inside the same all-or-nothing step.
- Manual check on phone: save a backup, read the summary in the confirmation when restoring it.

## Notifications, mail previews and swiping
- `NotificationReaderTest`, `CrossSourceTest` – UPI-app wording, messages-app and mail-preview reading, only listed apps are read, and one payment seen by SMS + notification + bank app is kept once (the SMS takes over).
- Manual checks on phone: Settings > Read notifications too > allow; pay with GPay/PhonePe and see one entry; swipe left/right between Home, Inbox, Owed, All, Summary.
