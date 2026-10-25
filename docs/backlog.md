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

- **Event-party collision self-heal (ADR-106 §6)** — on a PostgREST 409 / `23505`
  (`uq_parties_biz_name`) for a queued `parties` insert, pull, re-resolve the event party
  (link → name), re-point the local expense rows and queued expense payloads at the winner,
  drop the losing party row and op. Today the op is held and retried (never dead-lettered);
  the race needs two devices creating the same event party offline before either syncs.

- **More languages** — extend the catalog beyond English + Hindi + Hinglish toward the
  10-language target (Marathi, Gujarati, Telugu, Tamil, Kannada, Bengali, Punjabi,
  Malayalam); translation-only work by design (ADR-110 is the recipe: catalog + `b+` dir if a
  script subtag is involved + `LocaleManager` + `locales_config` + name keys).
- **Per-app locale for notifications on API 26–32 (ADR-110 §6)** — the AppCompat backport
  applies the chosen language only to activity contexts; a reminder posted from a cold
  receiver/worker process renders in the DEVICE language until an activity has run. Persist
  the chosen tag in the settings DataStore and wrap receiver/worker contexts with it. Android
  13+ is unaffected (the framework applies the per-app locale process-wide).
- **Realtime sync channels** — push-based updates in addition to periodic pull (§8 notes
  periodic pull is sufficient for v1).
