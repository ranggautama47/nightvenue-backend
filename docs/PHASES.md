# PHASES — NightVenue

Pendamping: `PRD.md` (FR/BR), `DECISIONS.md`, `AI_PERAN.md`, `CONTEXT.md`. Rencana ini **target**, bukan komitmen kalender (ADR-017). Aturan potong ada di bagian 4.

Setiap fase: satu atau lebih PR; penulis ≠ reviewer; test hijau; output test dilampirkan; PR bertanda kritis wajib dibaca dan dapat dijelaskan Owner (human understanding gate).

---

## 1. Ringkasan

| Fase | Fokus | Tier | Review |
|---|---|:-:|---|
| 0 | Dokumentasi, keputusan, konteks | — | PM + Owner |
| 1 | Fondasi repo, CI, spike hosting, pin versi | 1 | Standar |
| 2 | Skema dasar, seed, BusinessDateService | 1 | Standar |
| 3 | Auth dan role | 1 | Kritis |
| 4 | Katalog, resep, inventory, ledger | 1 | Kritis |
| 5 | Order, item, Stock-at-Order, VOID | 1 | Kritis |
| 6 | Pembayaran dan webhook | 1 | Kritis |
| 7 | Bottle keeping | 1 | Kritis |
| 8 | Cashier shift | 1 | Kritis |
| 9 | ML forecasting | 2 | Kritis (evaluasi) |
| 10 | Hybrid chat | 2 | Kritis (privasi key) |
| 11 | Bukti, hardening, README | — | PM + Owner |

Pemetaan minggu (target): M1 = Fase 0–2 (+ awal 3); M2 = Fase 3–5; M3 = Fase 6–8; M4 = Fase 9–11.

---

## 2. Rincian Fase

### Fase 0 — Dokumentasi
**DoD**
- [ ] `PRD`, `AI_PERAN`, `PHASES`, `APPLICATION_ARCHITECTURE`, `DATABASE`, `UML`, `DECISIONS`, `CONTEXT` konsisten (audit lintas-berkas lulus)
- [ ] Open decision OD-3 s.d. OD-11 punya jawaban atau default tertulis
- [ ] PM menyetujui v2.1 dan menyatakan `BACKEND_READY_FOR_PHASE_1 = YES`; Owner membaca DECISIONS

**Status per jalur**

| Jalur | Syarat | Membuka |
|---|---|---|
| Dokumentasi inti + arsitektur backend | Audit lintas-berkas lulus, v2.1 disetujui PM | Fase 1 backend |
| Kontrak desain frontend | `DESIGN_SYSTEM.md` ditulis DeepSeek, direview Gemini, disetujui PM (**bukan Claude**, OD-8) | Implementasi frontend |

`PHASE_0_FULLY_CLOSED` = kedua jalur selesai. Backend tidak menunggu jalur desain.

### Fase 1 — Fondasi
**Gerbang**: backend mulai setelah `BACKEND_READY_FOR_PHASE_1 = YES`. Skeleton frontend menunggu `DESIGN_SYSTEM.md` disetujui.
**Lingkup**: dua repo, struktur folder, Docker Compose (api, ml-service, postgres, redis), CI dasar (build + test + lint), gitleaks, health check, `.env.example`, salinan `CONTEXT.md`, pin versi (ADR-018), **spike hosting** (ADR-019).
**DoD**
- [ ] `docker compose up` menyalakan seluruh stack lokal
- [ ] CI hijau di kedua repo
- [ ] Versi terpilih tercatat di `CONTEXT.md`
- [ ] Spike hosting menghasilkan keputusan; ADR-019 diperbarui
- [ ] Tidak ada secret di repo

### Fase 2 — Skema dasar dan seed
**Lingkup**: migrasi tabel Fase 2 (`DATABASE.md` bagian 2), `BusinessDateService` (ADR-001), seeder idempotent, riwayat konsumsi sintetis 90 hari di ledger (`reference_type = SEED`).
**DoD**
- [ ] Migrasi berjalan dari DB kosong di Testcontainers
- [ ] Tabel batas business date lulus (02:30 → hari sebelumnya; 05:59:59 → sebelumnya; 06:00:00 → sama; transisi DST/zona venue diuji)
- [ ] Seeder dijalankan dua kali tanpa duplikat
- [ ] Rantai `balance_after` konsisten dengan stok akhir
- [ ] Data sintetis ditandai di dokumentasi

### Fase 3 — Auth dan role
**FR**: AUTH-01..05. `FR-BD-01` selesai di Fase 2; `FR-BD-02..03` diverifikasi saat tabel bisnis Fase 4/5 dibuat.
**DoD**
- [ ] Login, refresh dengan rotasi, reuse terdeteksi → semua sesi dicabut
- [ ] Refresh token `HttpOnly`; access token dikembalikan di body (penyimpanan hanya di memori FE diverifikasi pada fase frontend)
- [ ] Otorisasi role dan scoping venue diuji (akses lintas-venue ditolak)
- [ ] Rate limit pada `/auth/*`
- [ ] Tidak ada password/token di log

### Fase 4 — Katalog, resep, inventory
**FR**: CAT-01..05.
**DoD**
- [ ] CRUD produk, bahan, resep; satu lokasi BAR per venue ditegakkan
- [ ] Adjust stok menulis ledger dengan alasan wajib
- [ ] CHECK stok ≥ 0 diuji di level DB
- [ ] Cache menu Redis dengan invalidasi

### Fase 5 — Order, Stock-at-Order, VOID
**FR**: POS-01..06, VOID-01..06. **Kritis.**
**DoD**
- [ ] Satu order aktif per meja (unique index) diuji
- [ ] Conditional UPDATE; urutan ID asc; rollback penuh saat stok kurang
- [ ] **Bukti A**: uji konkurensi (N thread, stok terbatas) → stok negatif = 0, jumlah sukses = stok awal / kebutuhan; hasil dilampirkan
- [ ] Idempotency-Key wajib pada buka order (ORDER_OPEN), tambah item, dan VOID; add-item mengunci baris order dan `order_item` dibuat sebelum ledger
- [ ] VOID whole order: RESTORED vs WASTED, ledger-based exactly-once; SERVE one ORDERED item only for OPEN/PARTIALLY_PAID/PAID parents
- [ ] VOID idempotency, manager approval precedence, audit, table release and full rollback invariants
- [ ] Payment guard for PENDING/PAID transactions: deferred to Phase 6. Not implemented in Phase 5C.
- [ ] Ambang approval Manager dan batasan WASTED diuji
- [ ] Owner dapat menjelaskan alur potong-stok dan kompensasi

### Fase 6 — Pembayaran
**FR**: PAY-01..08. **Kritis.**

Payment workstream M3-A, M3-B, M4-A, M4-B, M5, and M6 are internal payment milestones; they are **not** the project month labels M1–M4 in section 1. Phase 6 establishes payment and shift foundations. Cashier shift submit/review/variance/reconciliation workflow remains Phase 8. Refund remains deferred (section 3), outside M6.

**Payment milestone status** (hashes verified in Git history):

| Milestone | Description | Status | Evidence |
|---|---|---|---|
| M3-A | Payment core and GET Payment | IMPLEMENTED / TESTED LOCALLY | `b012c69` |
| V9 | Xendit QRIS schema and schema tests | IMPLEMENTED / TESTED LOCALLY | `21644d2` |
| M3-B | Xendit Payment Requests v3 Create | IMPLEMENTED / TESTED LOCALLY; PROVIDER VALIDATION PENDING | `d803ba7`, `9677693`, `d6f03b5`, `6593f98` |
| M4-A | Authenticated webhook and settlement | IMPLEMENTED / TESTED LOCALLY; PROVIDER VALIDATION PENDING | `8b76539`, `43effb1`, `58a3c73` |
| M4-B | Opt-in reconciliation | IMPLEMENTED / TESTED LOCALLY; PROVIDER VALIDATION PENDING | `ad0e35a`, `123f318`, `1fc868e`, `3904ac5` |
| M5-A | Deterministic concurrency tests | CLOSED — PM ACCEPTED | `6e3c837`, `585b5b2` |
| M5-B | Exception-boundary analysis | CLOSED — PM ACCEPTED; report-only, no production fix | No implementation commit |
| M5-C | Operations runbook and diagnostic SQL tests | CLOSED — PM ACCEPTED | `6fcbd75`, `5340e79`, `655ad57`, `5140c17` |
| M5-D | Final evidence audit and corrective documentation | CLOSED — PM ACCEPTED | `ec3cd9e` |
| M6 | Documentation closure | IN PROGRESS | No closure commit yet |

Regression evidence measured at baseline `ec3cd9e` on 2026-11-13 (owner-verified; not generated by later M6 commits): 570 tests, 0 failures, 0 errors, 1 skipped; `BUILD SUCCESS`, Maven exit code 0. `XenditSmokeTest` is conditional opt-in via `@EnabledIfSystemProperty` and `assumeTrue`, not `@Disabled`. This regression result does not replace the item-specific evidence below. No live/provider validation is implied.

**DoD**
- [x] BR-20: VOID is rejected for orders with PENDING or PAID payments; `PaymentIntegrationTests.void_withPaidPayment_409` and `void_withPendingPayment_409`. The historical Phase 5C deferral remains recorded in the Phase 5 DoD above; it is not an open Phase 6 task.
- [ ] Payment-versus-VOID race: both paths lock the order row, but no dedicated latch-based test interleaving payment creation and VOID was found. Full lock-order proof (`orders` → `payments` → `inventory_items`): VERIFY.
- [x] CASH and CARD_EDC payment core; Order 1:N Payment and split-by-amount: `PaymentIntegrationTests` partial/multiple/split tests.
- [x] BR-28 order-row serialization and reservation bound: `PaymentService` / `QrisCreateTransactions` lock the order and sum PAID + PENDING; `PaymentIntegrationTests.twoParallelPayments_differentKeys_neverExceedTotal`, `PaymentConcurrencyIntegrationTests.t1a_qrisPlusCashOvershoot_exactlyOneReservationLoser422`, and `t1b_exactBoundary_both201_thenOneRupiah422`.
- [x] M3-B calls the provider outside the order lock; TX1 → HTTP → TX2 uses conditional CAS fencing: `QrisPaymentCreator`, `QrisCreateTransactions`; `PaymentCreateXenditIntegrationTests.providerCallHoldsNoRowLock_tx1CommittedBeforeHttp`, `fencedPayment_409_stateConflict_idempotencyNotCompleted_noOverwrite`, and `tx2Failure_rollsBackPayment_rowStaysSending_noSnapshot`; `PaymentConcurrencyIntegrationTests.t5_webhookDuringCreateWindow_tx2Fenced409`.
- [x] Ambiguous Create remains business status PENDING, holds its reservation and BR-20 protection, and never automatically retries: `PaymentCreateXenditIntegrationTests.ambiguous_*` tests verify one provider call/no automatic retry and blocked VOID.
- [x] Webhook token authentication, correlation, and independent Status API verification: `XenditWebhookController` and `XenditWebhookIntegrationTests.invalidTokenAndMalformedPayload_makeNoWrites`, `acceptedPaymentCanSettle`, `statusApiFailuresNeverWriteOrChangePayment`, and correlation tests.
- [x] Duplicate webhook delivery has one settlement/event effect: `XenditWebhookIntegrationTests.duplicateDeliveryHasOneEffectAndOneCanonicalEvent`, `concurrentDuplicateDeliveryHasOneCanonicalSettlement`.
- [x] Terminal payment state is not resurrected; settlement for a VOIDED order records PAID payment while leaving order VOIDED and recording anomaly evidence: `XenditWebhookIntegrationTests.terminalPaymentsAreRejectedAndNeverResurrected`, `voidedOrderSettlementPaysPaymentAndKeepsOrderVoided`; reconciliation equivalents are covered in `PaymentReconciliationIntegrationTests`.
- [ ] Out-of-order webhook behavior as `IGNORED_STALE`: `PaymentConcurrencyIntegrationTests.t4_webhookRacesReconciliation_settlesExactlyOnce` asserts `IGNORED_STALE` for the reconciliation event in a webhook/reconciliation race; no test asserts it for an out-of-order webhook event.
- [x] Minimum-spend check in the CASH/CARD_EDC payment path: `PaymentService`; `PaymentIntegrationTests.minimumSpend_*` covers boundary, rejection, discount, and partial-payment behavior.
- [ ] KNOWN GAP — minimum-spend (BR-05) is not enforced on the QRIS Create path: `PaymentController` dispatches QRIS to `QrisPaymentCreator` before `PaymentService`, where the guard resides. Documented, not fixed in M6; PM decision item.
- [x] Provider HTTP calls do not hold database transactions/order locks: create evidence above; `PaymentReconciliationIntegrationTests.noHttpCallWhileAOrderRowLockIsHeldElsewhere` covers reconciliation.
- [x] V9 migration/schema constraints and legacy Midtrans distinction: `PaymentSchemaMigrationTests.migrate_fromV8WithLegacyMidtransPayment_succeeds`, Xendit/Midtrans constraint tests; V1–V8 remain unchanged.
- [x] M4-B candidate selection, age/batch limits, missing request ID handling, provider mismatch, scheduler disabled by default: `PaymentReconciliationIntegrationTests` targeted cases and `schedulerIsDisabledByDefaultInTheNormalTestContext`; defaults are `enabled=false`, `fixed-delay=PT5M`, `min-age=PT1H`, `batch-size=50`, `max-per-cycle=10`.
- [x] M5-A deterministic payment concurrency evidence: `PaymentConcurrencyIntegrationTests` t1a/t1b/t2–t5 and commits listed above.
- [x] M5-C read-only runbook queries Q1–Q6: `PaymentRunbookQueriesIntegrationTests` has 8 integration tests; includes SELECT-only/forbidden-token checks and parity with documented SQL.

**Open provider gates and limitations**
- Xendit sandbox smoke test has not been run. Provider-validated behavior and empirical 401/403 classification remain pending. The smoke test is a merge-to-main gate and a gate for claims of empirically validated provider behavior/401/403 classification; it does not block Phase 7 start.
- Reconciliation is opt-in (`enabled=false` by default). PENDING rows without `provider_request_id` have no approved automatic release path; TX2 failure can leave SENDING/PENDING without persisted QR data or provider request ID.
- In-flight idempotency keys can remain unresolved while no cleanup job exists.
- Provider `CANCELED` is currently handled as a no-op with warning. There is no automatic local QRIS expiry; `payment_request.expiry` is acknowledged without state change.
- `provider_transaction_id` ↔ Xendit `payment_id` mapping remains OPEN/VERIFY. Callback URL/local deployment/tunnel behavior remains VERIFY.
- Accepted M5 deviations: M5-C history has four original/revision commits; `6fcbd75` is not independently self-sufficient. Surefire “kill self fork JVM” warning appeared in green and red runs; further impact is unverified.
- Proposed documented limitations for PM review, not yet approved: no dedicated latch-based payment-versus-VOID race test; no out-of-order webhook test asserting `IGNORED_STALE`; QRIS Create does not enforce BR-05 minimum spend.
- Provider QRIS `channel_properties`, response action/payload shape, requested expiry, Create idempotency guarantee, ambiguous recovery without a received `payment_request_id`, and provider status semantics remain OPEN until provider-validated.

**Payment closure decisions**
- New QRIS uses Xendit Payment Requests v3; existing legacy Midtrans records remain supported as `QRIS_MIDTRANS`.
- A webhook is a trigger; independently verified provider Status API response supplies settlement evidence. Correlation and duplicate protection are required before state changes.
- Create is TX1 → external HTTP → TX2 with conditional CAS fencing. Application idempotency keys do not resolve an ambiguous provider outcome; ambiguous Create is not retried automatically.
- BR-20 blocks VOID while a payment is PENDING/PAID; BR-21 records settlement for a VOIDED order without reopening it; BR-28 serializes payment creation and reserves PAID + PENDING amounts against `total_due`.
- Reconciliation remains opt-in. Operational runbook Q1–Q6 is in ADR-023.

**M5 CLOSED — PM ACCEPTED.**

**M6: documentation in progress; pending final PM approval.**

### Fase 7 — Bottle keeping
**FR**: BTL-01..06. **Kritis.**
**DoD**
- [ ] Deposit hanya dari order PAID dan customer `age_verified`; tanpa nomor identitas
- [ ] Deposit exactly-once: `source_order_item_id` UNIQUE, `Idempotency-Key`, produk botol `quantity = 1` (FR-BTL-07); uji deposit paralel → satu vault
- [ ] Token acak 128-bit; hanya hash disimpan; token mentah dikembalikan sekali
- [ ] Redeem: token valid + `order.customer_id` cocok + `ownerConfirmed`; conditional UPDATE
- [ ] Uji konkurensi redeem: sisa volume tidak pernah < 0
- [ ] Job expiry; customer hanya melihat botolnya sendiri
- [ ] Dokumentasi menyebut batas: bukan autentikasi kuat (ADR-020)

### Fase 8 — Cashier shift
**FR**: SHF-01..06. **Kritis.**
**DoD**
- [ ] Satu shift OPEN per kasir (partial unique)
- [ ] Payment CASH butuh shift OPEN milik pemroses dan business date berlaku, untuk semua role (409 SHIFT_NOT_OPEN); setelah fase ini CASH tanpa shift ditolak, data uji Fase 6 dibuat ulang
- [ ] Reviewer tidak boleh pemilik shift (403)
- [ ] `expected_cash` dari `payments.shift_id`; variance dihitung; catatan wajib bila ≠ 0
- [ ] Job cutoff memaksa `MANAGER_REVIEW` + audit
- [ ] Migrasi menambah `payments.shift_id` dengan aman terhadap data yang ada

### Fase 9 — ML forecasting (Tier 2)
**FR**: ML-01..06.
**DoD**
- [ ] Minimal 28 hari data; kurang → 422
- [ ] Model dibandingkan baseline (MAE); keduanya ditampilkan
- [ ] **Bukti C**: laporan evaluasi dengan data sintetis berlabel
- [ ] Timeout 10 dtk; kegagalan ML → 503 untuk fitur ini saja
- [ ] Token internal; port ML tidak dipublikasikan

### Fase 10 — Hybrid chat (Tier 2)
**FR**: CHAT-01..12.
**DoD**
- [ ] 11 intent; θ dipilih dari validation set; held-out test hanya dipakai sekali
- [ ] Laporan P/R/F1 per kelas, confusion matrix, baseline keyword, aturan anti-leakage
- [ ] Alur: rate limit → validasi → classify → lokal / refusal / BYOK / ADVANCED_AI_REQUIRED
- [ ] Fallback Spring maksimal 5 kategori
- [ ] Key hanya header; tidak disimpan/dicatat (diuji lewat pengecekan log); UI tidak berkata "key tidak keluar dari browser"
- [ ] `chat_route_logs` tanpa isi pesan; **Bukti D**: biaya/latensi per rute diukur
- [ ] Demo chat tanpa key berfungsi; video + transkrip jalur LLM tersedia

### Fase 11 — Bukti, hardening, README
**DoD**
- [ ] Bukti A–D memuat angka hasil pengukuran sebenarnya (ADR-016); tidak ada angka contoh
- [ ] README: arsitektur, cara jalan, trade-off, keterbatasan jujur (BYOK, bottle QR, data sintetis)
- [ ] Review index (`EXPLAIN`) pada query kritis
- [ ] Deploy publik + health check pasca-deploy
- [ ] Audit lintas-berkas akhir; narasi README oleh Owner + PM, agent menyuplai bukti

---

## 3. Ditunda (Fase 12+, butuh persetujuan PM)
Reservasi, recipe versioning, last call, void anomaly, reaktivasi botol email, simulasi what-if, loyalty, transfer stok, password recovery, refund, laporan penjualan, split per item, VOID per item, re-issue QR token.

---

## 4. Urutan Potong
1. Tier 1 **tidak pernah** dipotong.
2. Potong Tier 2 dari belakang: Fase 10 sebelum Fase 9.
3. Bukti A dan B tidak boleh dilewati.
4. Bila ragu, potong fitur, bukan test.

Kesiapan: `CORE_PORTFOLIO_READY` = Tier 1 + Bukti A/B + deployment + README + understanding gate. `FULL_NIGHTVENUE_READY` = core + Tier 2 + Bukti C/D. Memotong Tier 2 tidak membatalkan status core.
