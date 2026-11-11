# Paisa Log — manual test checklist

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

## Delete a transaction, Food tag, new icon
- Open any transaction (Inbox or All) → "Delete" (red, top right) → confirm. It disappears with its split, and Settings > Import older messages does not bring it back.
- The tag "Food Order" is now "Food" everywhere, including tagged spends and saved merchant rules.
- App icon: a white rupee sign on a teal coin; the name under it reads "Paisa Log".

## Refund received, Refunds tab, Owed scrolling, name
- Inbox always shows a "Refunds" tab, with a message when it is empty.
- "Refund received" is in a spend's ⋮ menu, in the transaction sheet, and on each card in Refunds. Full amount closes it; a smaller amount reduces what you spent.
- Owed page scrolls as one list; each person shows three bills, "Show all" opens the rest.
- App name is "Paisa Log".

## Compact cards, delete from lists
- A tagged spend shows tag and people in one row; all-yours shows "Mine".
- ⋮ on any card (Inbox spend, All list, own transfer) has Delete; own transfer deletes both halves.
- Long-press any card in All to start selecting; Select mode has Delete for the ticked ones.

## No duplicates
- The same bank SMS read twice (live, then Settings > Import older messages) gives one entry.
- Two alerts for one card swipe (same card, amount, similar shop, within 90 seconds) give one entry.
- Two genuine purchases of the same amount minutes apart stay two.

## Auto "mine" only when safe
- A shop is tagged mine automatically only after 2+ earlier spends there that were yours alone, never shared, and only if the new amount is no more than 25% above the largest earlier one. Otherwise it sits in the Inbox with an "As before: mine" shortcut.

## Stats page (Money Manager style)
- Summary tab: month navigator; Spent and Income; Budget card (overall + per tag, bars turn amber at 80% and red when over, "safe to spend a day" for the current month); donut chart with legend; last 6 months bars.
- Income = money received that you marked Salary, Gift or Other. Repayments, borrowed money and refunds are not income.
- Tap a budget row to change or remove it; budgets are included in backups.

## Calendar and home month card
- All tab → "Calendar": month grid with spent (red) and income (green) per day; tap a day to list its transactions; Prev/Next change month.
- Home shows "Spent in <month>", and with a budget set, a bar and "safe to spend today".

## Calculator, export, repeating entries, app lock
- Amount field: type `120+45×2` (or tap + − × ÷) → "= ₹210" shows below; Save uses the total.
- More ▸ Export to spreadsheet: saves a .csv; open in Sheets/Excel; commas in names stay in one cell.
- More ▸ Repeating entries: add "Rent ₹10,000 on the 5th"; it appears on the 5th (or the next app open) and never twice for a month; Stop removes it.
- More ▸ App lock: needs a phone screen lock; leave the app >30 s and return → asks for fingerprint/PIN.
- More ▸ Accounts / Home "My accounts" card open the same page.
