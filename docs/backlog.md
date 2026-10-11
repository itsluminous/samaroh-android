# v2+ Backlog

Deferred features, seeded at Wave 0. Plain feature descriptions only — this file must
never reference third-party products (see the legal-hygiene rule in AGENTS.md).

## Booking & sales

- **Enquiry / lead pipeline** — capture enquiries that are not bookings yet, with
  follow-up reminders so no lead is forgotten.
- **Quotation builder** — assemble a priced quote from food/decor/service packages and
  share it with the customer before a booking is confirmed.
- **Menu presets attached to bookings** — reusable food-menu templates that can be linked
  to a booking and printed/shared.
- **Customer 360° profile** — one page per customer showing past events, preferences and
  upcoming festivals relevant to them.
- **Multi-hall slotting** — model multiple halls/spaces within one business so the same
  date can be booked per hall.
- **Booking-source analytics deep-dive** — richer attribution on top of the v1 source tag.

## Communication

- **Passive daily WhatsApp summary** — an opt-in end-of-day summary message of bookings,
  payments and reminders, prepared for manual send.
- **Rule-based after-hours auto-reply** — configurable canned reply for enquiries that
  arrive outside business hours.
- **UPI collection links** — generate payment links for pending dues so customers can pay
  directly from the reminder message.

## Finance

- **GST invoice fields** — optional CGST/SGST split, GSTIN capture and tax breakdown on
  generated invoices.

## Platform

- **More languages** — extend the catalog beyond English + Hindi toward the 10-language
  target (Marathi, Gujarati, Telugu, Tamil, Kannada, Bengali, Punjabi, Malayalam);
  translation-only work by design.
- **Realtime sync channels** — push-based updates in addition to periodic pull (§8 notes
  periodic pull is sufficient for v1).
- **Stable shell nav graph** — `SamarohApp`'s root `NavHost` builder lambda captures unstable
  state (`pendingAppLink`, `pendingItemPhoto`, …), so every shell recomposition rebuilds the
  `NavGraph` and navigation-compose re-creates each feature host (inner nav stack and
  ViewModels lost — found while shipping ADR-098, reproduced with a dummy state tick). Fix by
  building the graph once (`remember`ed, stable `State` inputs) and have feature graphs read
  one-shot targets from holders like `BookingDeepLinkHolder`; then the ADR-033 sub-target
  parameters (`partyIdToOpen`, `openMasterlist`, `openSettings`) can migrate too.
