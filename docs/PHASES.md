# PHASES — NightVenue

Pendamping: `PRD.md` (FR/BR), `DECISIONS.md`, `AI_PERAN.md`, `CONTEXT.md`. Rencana ini **target**, bukan komitmen kalender (ADR-017). Aturan potong ada di bagian 4.

Setiap fase: satu atau lebih PR; penulis ≠ reviewer; test hijau; output test dilampirkan; PR bertanda kritis wajib dibaca dan dapat dijelaskan Owner (human understanding gate).

---

## 1. Ringkasan

| Fase | Fokus | Tier | Review | Status |
|---|---|:-:|---|---|
| 0 | Dokumentasi, keputusan, konteks | — | PM + Owner | PARTIAL |
| 1 | Fondasi repo, CI, spike hosting, pin versi | 1 | Standar | PARTIAL |
| 2 | Skema dasar, seed, BusinessDateService | 1 | Standar | IMPLEMENTED - TESTED LOCALLY |
| 3 | Auth dan role | 1 | Kritis | IMPLEMENTED - TESTED LOCALLY |
| 4 | Katalog, resep, inventory, ledger | 1 | Kritis | IMPLEMENTED - TESTED LOCALLY |
| 5 | Order, item, Stock-at-Order, VOID | 1 | Kritis | PARTIAL |
| 6 | Pembayaran dan webhook | 1 | Kritis | IN PROGRESS - M6 pending closure |
| 7 | Bottle keeping | 1 | Kritis | IN PROGRESS — pending owner full regression |

| 8 | Cashier shift | 1 | Kritis | IN PROGRESS — implementation and targeted tests completed; full regression and PR approval pending |
| 9 | ML forecasting | 2 | Kritis (evaluasi) | NOT STARTED |
| 10 | Hybrid chat | 2 | Kritis (privasi key) | IN PROGRESS |
| 11 | Bukti, hardening, README | — | PM + Owner | NOT STARTED |

Status di atas merujuk checklist tiap fase; status Fase 8 dan Fase 9 mengikuti baris tabel dan tidak diubah oleh pembaruan ini. Fase 10 berstatus IN PROGRESS: sub-fase 10.1 dan 10.2 (Round A sampai D) diterima PM, sedangkan sub-fase 10.3 sampai 10.7 belum dimulai. Fase 11: NOT STARTED. Fondasi shift (buka shift dan stale `OPEN → MANAGER_REVIEW`) sudah masuk Fase 6 dan dibuktikan `ShiftIntegrationTests`; itu bukan checklist Fase 8. Status Fase 0, 1, 2, 3, 4, dan 5 diturunkan dari bukti Butir di bawah; item yang tidak dapat diverifikasi ditandai eksplisit, bukan diasumsikan.

Pemetaan minggu (target): M1 = Fase 0–2 (+ awal 3); M2 = Fase 3–5; M3 = Fase 6–8; M4 = Fase 9–11.

---

## 2. Rincian Fase

### Fase 0 — Dokumentasi
**DoD**
- [ ] `PRD`, `AI_PERAN`, `PHASES`, `APPLICATION_ARCHITECTURE`, `DATABASE`, `UML`, `DECISIONS`, `CONTEXT` konsisten (audit lintas-berkas lulus) - NOT VERIFIED: no evidence located
- [x] Open decision OD-3 s.d. OD-11 punya jawaban atau default tertulis - Evidence: `docs/PRD.md:403-411` (OD-3..OD-11 dengan default tertulis); commit `8577b51` (OD-3), `91fcc08` (OD-11)
- [ ] PM menyetujui v2.1 dan menyatakan `BACKEND_READY_FOR_PHASE_1 = YES`; Owner membaca DECISIONS - NOT VERIFIED: no evidence located

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
- [ ] `docker compose up` menyalakan seluruh stack lokal - NOT VERIFIED: no execution evidence located
- [ ] CI hijau di kedua repo - NOT VERIFIED: no evidence located
- [x] Versi terpilih tercatat di `CONTEXT.md` - Evidence: `CONTEXT.md:16` (JDK 21, Spring Boot 4.1.2, Maven 3.9.9, Python 3.12, FastAPI 0.162.0, PostgreSQL 17.4-alpine, Redis 7.4.2-alpine); commit `8577b51`
- [ ] Spike hosting menghasilkan keputusan; ADR-019 diperbarui - NOT VERIFIED: no evidence located
- [ ] Tidak ada secret di repo - NOT VERIFIED: no execution evidence located

### Fase 2 — Skema dasar dan seed
**Lingkup**: migrasi tabel Fase 2 (`DATABASE.md` bagian 2), `BusinessDateService` (ADR-001), seeder idempotent, riwayat konsumsi sintetis 90 hari di ledger (`reference_type = SEED`).
**DoD**
- [x] Migrasi berjalan dari DB kosong di Testcontainers - Evidence: `Phase2DatabaseTests.migrationsValidateAndCreatePhaseFiveATables`
- [x] Tabel batas business date lulus (02:30 → hari sebelumnya; 05:59:59 → sebelumnya; 06:00:00 → sama; transisi DST/zona venue diuji) - Evidence: `BusinessDateServiceTests.appliesJakartaCutoffBoundaries`, `springForwardUsesWallClockHours`, `fallBackCutoffBoundaryUsesTheTransitionDate`
- [x] Seeder dijalankan dua kali tanpa duplikat - Evidence: `Phase2DatabaseTests.seederIsIdempotentAndItsLedgerBalances`
- [x] Rantai `balance_after` konsisten dengan stok akhir - Evidence: `Phase2DatabaseTests.seederIsIdempotentAndItsLedgerBalances`, `fixedSeedProducesIdenticalLedgersInTwoFreshDatabases`
- [x] Data sintetis ditandai di dokumentasi - Evidence: `docs/DATABASE.md` bagian 9 "Seed dan Data Sintetis" dan `README.md` "Known limitations"; commit `8577b51`, `216f840`

### Fase 3 — Auth dan role
**FR**: AUTH-01..05. `FR-BD-01` selesai di Fase 2; `FR-BD-02..03` diverifikasi saat tabel bisnis Fase 4/5 dibuat.
**DoD**
- [x] Login, refresh dengan rotasi, reuse terdeteksi → semua sesi dicabut - Evidence: `AuthIntegrationTests.refreshRotatesAndReuseRevokesAllFamiliesAndAudits`
- [x] Refresh token `HttpOnly`; access token dikembalikan di body (penyimpanan hanya di memori FE diverifikasi pada fase frontend) - Evidence: `AuthIntegrationTests.seededDemoCredentialsAuthenticateWithHttpOnlyCookie`, `loginMeAndBadBearerTokensFollowContract`
- [x] Otorisasi role dan scoping venue diuji (akses lintas-venue ditolak) - Evidence: `AuthIntegrationTests.roleChecksAreExplicitAndVenueAssignmentsAreLoadedFromDatabase`, `rolesHaveOnlyTheirExplicitAuthorityAndCustomerHasNoVenues`
- [x] Rate limit pada `/auth/*` - Evidence: `AuthIntegrationTests.rateLimitReturnsProblemAndRetryAfter`, `rateLimitsAreIndependentForLoginRefreshLogoutAndMe`
- [x] Tidak ada password/token di log - Evidence: `AuthIntegrationTests.sensitiveAuthenticationValuesNeverAppearInCapturedLogs`

### Fase 4 — Katalog, resep, inventory
**FR**: CAT-01..05.
**DoD**
- [x] CRUD produk, bahan, resep; satu lokasi BAR per venue ditegakkan - Evidence: `ProductRecipeIntegrationTests.productAndRecipeEndpointsEnforceRoleMatrix`, `StockLocationIntegrationTests.postgresEnforcesOneBarPerVenueAndNameUniqueness`
- [x] Adjust stok menulis ledger dengan alasan wajib - Evidence: `InventoryIntegrationTests.adjustmentReasonRequired422`, `purchaseInIncreasesQuantityAndWritesLedgerBalanceAfter`
- [x] CHECK stok ≥ 0 diuji di level DB - Evidence: `InventoryIntegrationTests.postgresEnforcesQuantityNonNegativeAndItemUniqueness`, `OrderItemIntegrationTests.directJdbcNegativeQuantityViolatesCheckConstraint`
- [x] Cache menu Redis dengan invalidasi - Evidence: `MenuCacheIntegrationTests.cashierCacheMissPopulatesRedisKey`, `productCreateEvictsCacheForVenue`, `cacheInvalidationOnlyAfterCommitNotOnRollback`

### Fase 5 — Order, Stock-at-Order, VOID
**FR**: POS-01..06, VOID-01..06. **Kritis.**
**DoD**
- [x] Satu order aktif per meja (unique index) diuji - Evidence: `OrderIntegrationTests.databaseEnforcesActiveTableAndOrderNumberUniquenessAndChecks`, `simultaneousOrdersForOneTableProduceOneSuccessAndSevenConflicts`
- [x] Conditional UPDATE; urutan ID asc; rollback penuh saat stok kurang - Evidence: `OrderItemIntegrationTests.insufficientStockRollsBackEverything`, `InventoryConcurrencyTests.internalDeductUsesIngredientIdAscending`, `twoDeductsInOneTransactionSecondFailsFirstRollsBack`
- [x] **Bukti A**: uji konkurensi (N thread, stok terbatas) → stok negatif = 0, jumlah sukses = stok awal / kebutuhan; hasil dilampirkan - Evidence: `OrderItemIntegrationTests.evidenceAConcurrencyContentionOnInventoryRow`, `InventoryConcurrencyTests.fiftyInternalDeductsStockForTenExactlyTenSuccessFortyConflict`
- [x] Idempotency-Key wajib pada buka order (ORDER_OPEN), tambah item, dan VOID; add-item mengunci baris order dan `order_item` dibuat sebelum ledger - Evidence: `OrderIntegrationTests.sameKeyDifferentPayloadReturns422`, `missingAndInvalidHeaderReturn400`; `OrderItemIntegrationTests.replaySameKeyReturnsIdenticalBodyWithoutExtraDeductions`; `OrderLifecycleIntegrationTests.voidValidatesReasonAndIdempotencyHeader`
- [x] VOID whole order: RESTORED vs WASTED, ledger-based exactly-once; SERVE one ORDERED item only for OPEN/PARTIALLY_PAID/PAID parents - Evidence: `OrderLifecycleIntegrationTests.restoredVoidAndServePreserveLedgerAndSnapshots`, `servedItemIsWastedAndServeIsAudited`, `serveParentStatusMatrix`
- [x] VOID idempotency, manager approval precedence, audit, table release and full rollback invariants - Evidence: `OrderLifecycleIntegrationTests.differentKeyDoubleVoidOneSucceedsOneConflicts`, `cashierWastedPrecedenceDoesNotReserveIdempotency`, `cashierApprovalFailureRollsBackReservationAndManagerCanRetry`, `zeroRowTableReleaseRollsBackWholeVoid`
- [ ] Payment guard for PENDING/PAID transactions: deferred to Phase 6. Not implemented in Phase 5C. Update 2026-11-13: delivered in Phase 6 (BR-20); tracked in the Phase 6 DoD. Evidence: `OrderLifecycleService.voidOrder()` rejects VOID with `Conflict("Order has active payments")` while holding order row lock and before OPEN-status gate, via `PaymentRepository.existsActiveByOrder()` (`PaymentRepository.java:42`, `status IN ('PENDING','PAID')`). Integration tests `PaymentIntegrationTests.void_withPaidPayment_409` and `void_withPendingPayment_409` (`PaymentIntegrationTests.java:696,708`) confirm VOID is rejected with 409 when PENDING or PAID payment exists.
- [x] Ambang approval Manager dan batasan WASTED diuji - Evidence: `OrderLifecycleIntegrationTests.cashierAtExactThresholdCanVoid`, `managerApprovalRecordedOnlyAboveThreshold`, `cashierWastedPrecedenceDoesNotReserveIdempotency`
- [ ] Owner dapat menjelaskan alur potong-stok dan kompensasi - NOT VERIFIED: no evidence located

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
- [ ] Payment-versus-VOID race: both paths lock the order row, but no dedicated latch-based test interleaving payment creation and VOID was found (the only latch-based VOID concurrency test, `OrderLifecycleIntegrationTests.concurrentOpenDuringPausedReleaseConflictsThenCanOpenAfterVoid`, interleaves VOID with order open, not with payment creation). Full lock-order proof (`orders` → `payments` → `inventory_items`): VERIFY. Recorded as an accepted documented test limitation (PM decision, 2026-11-13).
- [x] CASH and CARD_EDC payment core; Order 1:N Payment and split-by-amount: `PaymentIntegrationTests` partial/multiple/split tests.
- [x] BR-28 order-row serialization and reservation bound: `PaymentService` / `QrisCreateTransactions` lock the order and sum PAID + PENDING; `PaymentIntegrationTests.twoParallelPayments_differentKeys_neverExceedTotal`, `PaymentConcurrencyIntegrationTests.t1a_qrisPlusCashOvershoot_exactlyOneReservationLoser422`, and `t1b_exactBoundary_both201_thenOneRupiah422`.
- [x] M3-B calls the provider outside the order lock; TX1 → HTTP → TX2 uses conditional CAS fencing: `QrisPaymentCreator`, `QrisCreateTransactions`; `PaymentCreateXenditIntegrationTests.providerCallHoldsNoRowLock_tx1CommittedBeforeHttp`, `fencedPayment_409_stateConflict_idempotencyNotCompleted_noOverwrite`, and `tx2Failure_rollsBackPayment_rowStaysSending_noSnapshot`; `PaymentConcurrencyIntegrationTests.t5_webhookDuringCreateWindow_tx2Fenced409`.
- [x] Ambiguous Create remains business status PENDING, holds its reservation and BR-20 protection, and never automatically retries: `PaymentCreateXenditIntegrationTests.ambiguous_*` tests verify one provider call/no automatic retry and blocked VOID.
- [x] Webhook token authentication, correlation, and independent Status API verification: `XenditWebhookController` and `XenditWebhookIntegrationTests.invalidTokenAndMalformedPayload_makeNoWrites`, `acceptedPaymentCanSettle`, `statusApiFailuresNeverWriteOrChangePayment`, and correlation tests.
- [x] Duplicate webhook delivery has one settlement/event effect: `XenditWebhookIntegrationTests.duplicateDeliveryHasOneEffectAndOneCanonicalEvent`, `concurrentDuplicateDeliveryHasOneCanonicalSettlement`.
- [x] Terminal payment state is not resurrected; settlement for a VOIDED order records PAID payment while leaving order VOIDED and recording anomaly evidence: `XenditWebhookIntegrationTests.terminalPaymentsAreRejectedAndNeverResurrected`, `voidedOrderSettlementPaysPaymentAndKeepsOrderVoided`; reconciliation equivalents are covered in `PaymentReconciliationIntegrationTests`.
- [ ] Out-of-order webhook behavior as `IGNORED_STALE`: `PaymentConcurrencyIntegrationTests.t4_webhookRacesReconciliation_settlesExactlyOnce` asserts `IGNORED_STALE` for the reconciliation event in a webhook/reconciliation race (cross-source); `PaymentEventRepositoryIntegrationTests` only covers the repository-level insert of that result. No test asserts `IGNORED_STALE` for an out-of-order webhook event. Recorded as an accepted documented test limitation (PM decision, 2026-11-13).
- [x] Minimum-spend check in the CASH/CARD_EDC payment path: guard di `PaymentService.java:113-119` (`minimumSpendSnapshot` > 0 dan `subtotal − discountAmount` < minimum → `MINIMUM_SPEND_NOT_MET`); `PaymentIntegrationTests.minimumSpend_exactlyAtMinimum_ok`, `minimumSpend_belowMinimum_exhaustingPayment_422`, `minimumSpend_partialNotExhausting_ok`, `minimumSpend_discountIncludedInBase`, `minimumSpend_zeroSnapshotDisabled`.
- [ ] CONFIRMED GAP - FIX approved by PM 2026-11-13, pending separate milestone (BR-05): minimum-spend is not enforced on the QRIS create path (`PaymentController.java:40-42` dispatch QRIS ke `QrisPaymentCreator`, sehingga `PaymentService` dilewati; `QrisCreateTransactions.begin:105-113` hanya memeriksa status order dan sisa tagihan). The existing `minimumSpend_*` tests cover CASH/CARD_EDC only. To be delivered and tested in a separate PM-approved milestone (not part of M6); until then the QRIS path has no minimum-spend guard. A settlement-time guard is not permitted (customer may already have paid).
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
- BR-05 QRIS gap: PM decision 2026-11-13 = FIX in a separate PM-approved milestone; it must be delivered and tested before Phase 6 is closed.
- Accepted documented test limitations (PM decision, 2026-11-13): no dedicated latch-based payment-versus-VOID race test; no out-of-order webhook test asserting `IGNORED_STALE`.
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
**FR**: BTL-01..07. **Kritis.**
**DoD**
- [x] Deposit hanya dari order PAID dan customer `age_verified`; tanpa nomor identitas - Evidence: `BottleIntegrationTests.validDepositReturns201AndCreatesDbRows`, `rejectsOrdersNotPaid`, `rejectsUnverifiedCustomer`, `piiColumnsAbsentFromSchema`

- [x] Deposit exactly-once: `source_order_item_id` UNIQUE, `Idempotency-Key`, produk botol `quantity = 1` (FR-BTL-07); uji deposit paralel → satu vault - Evidence: `BottleIntegrationTests.duplicateDepositOnSameItemWithDifferentKeyReturns409`, `BottleIntegrationTests.replayReturnsSnapshotWithoutToken`, `BottleIntegrationTests.rejectsQuantityNotOne`, `BottleConcurrencyTests.concurrentDepositsOnSameItemResultInExactlyOneSuccessAndRemainingConflicts`
- [x] Token acak 128-bit; hanya hash disimpan; token mentah dikembalikan sekali - Evidence: `BottleIntegrationTests.validDepositReturns201AndCreatesDbRows`, `replayReturnsSnapshotWithoutToken`, `piiColumnsAbsentFromSchema`

- [x] Redeem: token valid + `order.customer_id` cocok + `ownerConfirmed`; conditional UPDATE - Evidence: `BottleRedeemIntegrationTests.partialRedeemAddsZeroValueServedItemWithoutChangingOrderTotals`, `otherCustomersOrderReturns404`, `ownerConfirmedFalseReturns422NoStateChange`, `leakRawTokenAbsentFromLogsAuditIdempotencyAndResponse`

- [x] Uji konkurensi redeem: sisa volume tidak pernah < 0 - Evidence: `BottleRedeemConcurrencyTests.parallelRedeemsNeverOverdrawVault`

- [x] Job expiry; customer hanya melihat botolnya sendiri - Evidence: `BottleExpiryIntegrationTests.expiresOnlyActiveWithPastExpiry`, `expiryDateEqualToBusinessDateNotExpired`, `rerunIsIdempotent`; `BottleExpiryConfigurationTests.disabledByDefault`, `independenceBothWays`; `BottleQueryIntegrationTests.customerSeesOnlyOwnBottles`

- [x] Dokumentasi menyebut batas: bukan autentikasi kuat (ADR-020) - Evidence: `README.md` bagian "Bottle keeping".


### Fase 8 — Cashier shift
**FR**: SHF-01..06. **Kritis.**
**DoD**
- [x] Satu shift OPEN per kasir (partial unique) - Note: implemented in Phase 6 (evidence: V8 migration `uq_cashier_shifts_one_open_per_cashier` partial unique index on `cashier_id` WHERE `status = 'OPEN'` at `V8__payment_and_shift_foundation.sql:16-19`; `ShiftIntegrationTests.duplicateOpenOnSameBusinessDate_409`, `staleOpenMovesToManagerReview_withForcedReviewAudit_thenOpensAtomically`); Phase 8 retains submit/review/variance/RECONCILED.
- [x] Payment CASH butuh shift OPEN milik pemroses dan business date berlaku, untuk semua role (409 SHIFT_NOT_OPEN); setelah fase ini CASH tanpa shift ditolak, data uji Fase 6 dibuat ulang - Note: implemented in Phase 6 (evidence: `PaymentService.java:128-132` calls `findOpenShiftForPayment` and throws `SHIFT_NOT_OPEN` if absent; `ShiftIntegrationTests.cashierOpensOwnShift_201_andAudited`, `managerAndAdminEachOpenTheirOwnShift`); Phase 8 retains submit/review/variance/RECONCILED.
- [x] Reviewer tidak boleh pemilik shift (403) - Evidence: `ShiftService.reviewShift` (enforces caller != cashier_id); `ShiftReviewIntegrationTests.shiftOwnerCannotReviewTheirOwnShift`, `ShiftReviewIntegrationTests.adminCannotReviewOwnShift`, `ShiftConcurrencyIntegrationTests.s6_ownerManagerSelfReviewVsOtherManager`; database constraint `ck_cashier_shifts_reviewed_by_not_owner`.
- [x] `expected_cash` dari `payments.shift_id`; variance dihitung; catatan wajib bila ≠ 0 - Evidence: `ShiftRepository.sumPaidCash`; `ShiftService.submitShift`; `ShiftService.reviewShift`; `ShiftSubmitIntegrationTests.submitsExpectedCashAndWritesAudit`, `ShiftSubmitIntegrationTests.sumsOnlyCashPaidOnThisShiftAndSupportsAllVarianceSigns`, `ShiftReviewIntegrationTests.reconcilesManagerReviewByCalculatingPaidCashAfterLock`, `ShiftReviewIntegrationTests.nonzeroVarianceRequiresReviewNote`.
- [x] Job cutoff memaksa `MANAGER_REVIEW` + audit - Note: implemented in Phase 6 as `ShiftRepository.markStaleShiftForReview` (`ShiftRepository.java:48-58`, conditional update `OPEN` → `MANAGER_REVIEW` with `forced_review_at`); `ShiftIntegrationTests.staleOpenMovesToManagerReview_withForcedReviewAudit_thenOpensAtomically`, `staleShiftInAnotherVenueResolvedAgainstItsOwnVenue`, `staleShiftOfAnotherVenueFlipsByItsOwnVenueThenNewShiftOpens`); this is a service method, not a scheduled job; Phase 8 retains submit/review/variance/RECONCILED. Phase 8 independent scheduled cutoff implemented: `ShiftCutoffService`, `ShiftCutoffScheduler`, `ShiftCutoffSchedulingConfiguration`; flag `nightvenue.shift.cutoff.enabled`; evidence: `ShiftCutoffIntegrationTests`, `ShiftCutoffSchedulingConfigurationTests`, `ShiftConcurrencyIntegrationTests.s4_submitVsRunCutoffOnStaleShift`, `ShiftConcurrencyIntegrationTests.s5_runCutoffVsOpenShift`, `ShiftConcurrencyIntegrationTests.s8_parallelRunCutoffThreeStaleShifts`.
- [ ] Migrasi menambah `payments.shift_id` dengan aman terhadap data yang ada - Note: implemented in Phase 6 (evidence: V8 migration adds `shift_id BIGINT REFERENCES cashier_shifts(id) ON DELETE RESTRICT` at `V8__payment_and_shift_foundation.sql:25`, with constraint `ck_payments_shift_method` enforcing `shift_id` for CASH and NULL for others at `:47-48`); Phase 8 retains submit/review/variance/RECONCILED.

### Fase 9 — ML forecasting (Tier 2)
**FR**: ML-01..06.
**DoD**
- [x] Minimal 28 hari data; kurang → 422
- [x] Model dibandingkan baseline (MAE); keduanya ditampilkan
- [x] **Bukti C**: laporan evaluasi dengan data sintetis berlabel
- [ ] Timeout 10 dtk; kegagalan ML → 503 untuk fitur ini saja — NOT VERIFIED: `MlClient` configures 10,000 ms connect/read timeouts, but its read timeout only bounds waiting for response headers, not the full response body. Forecast failure tests assert 503 and no forecast persistence, but do not call an unrelated feature after an ML failure.
- [x] Token internal; port ML tidak dipublikasikan

Status Phase 9: IN PROGRESS — implementation committed through 9e; documentation (9d) and local regression verification complete; remote push, GitHub Actions verification, and final PM acceptance pending.

**Bukti implementasi dan tes terarah** (hash commit diverifikasi Owner):
- 9a — `270461d`; pytest: 22.
- 9b — `88d7f55`; pytest: 27.
- 9c-1 — `0225286`.
- 9c-2 — `d5735c9`.
- 9c-3a — `f5c615e`; `ForecastRepositoryIntegrationTests`: 6.
- 9c-3b — `72a4fe1`; `ForecastSeriesBuilderTests`: 15.
- 9c-3c Round A — `6bbae68`; `ForecastIntegrationTests`: 13; run terarah gabungan: 34 tes, BUILD SUCCESS.
- 9c-3c Round B — `caac2e4`; 11 tes dalam 6 kelas, BUILD SUCCESS.

Verifikasi lokal (Owner): Maven 784 tes, 0 gagal, 0 error, 1 skip (`XenditSmokeTest`, opt-in dengan `-Dxendit.smoke=true` dan `XENDIT_SECRET_KEY` non-blank); Ruff lulus; pytest 27 passed. Tidak ada perubahan pada `ml-service` setelah `0d5a573`. Branch belum di-push; GitHub Actions belum diverifikasi.

**Evidence C** (`ml-service/reports/evidence_c_forecast_evaluation.md`): evaluasi offline pada data sintetis; seed `20260101`, 126 hari mulai `2026-01-01`. Horizon 7 hari; training hari 1–84, validation 85–105, held-out 106–126; origin validation 84, 91, 98 dan held-out 105, 112, 119. Angka MAE di bawah adalah mean daily MAE Regression / Baseline / Selected:

| Split sintetis | A (ML) | B (GRAM) | C (PIECE) |
|---|---:|---:|---:|
| Validation | 30.773 / 91.715 / 30.773 | 31.318 / 27.991 / 30.287 | 4.104 / 5.832 / 4.104 |
| Held-out | 34.551 / 121.205 / 34.551 | 37.706 / 37.335 / 38.413 | 2.647 / 6.640 / 2.647 |

| Ingredient | Unit | Planted slope | Recovered OLS slope |
|---|---|---:|---:|
| A | ML | 2.000 | 2.003 |
| B | GRAM | 0.000 | -0.240 |
| C | PIECE | 0.100 | 0.078 |

Data Evidence C sintetis; angka tersebut bukan akurasi produksi. Pada B (GRAM) held-out, Selected MAE 38.413 lebih tinggi daripada Regression 37.706 dan Baseline 37.335.

Evidence C held-out MAE berasal dari evaluasi offline pada tiga window sintetis yang ditahan untuk pengujian; angka itu berbeda dari field API. Dalam `ml-service/app/forecast.py`, fungsi `calculate_forecast` menghitung `modelMae` dan `baselineMae` sebagai mean absolute error terhadap `horizonDays` observasi terakhir pada seri input, menggunakan observasi input sebelumnya untuk melatih masing-masing metode.

**Batasan forecasting**
1. Spring mengambil ledger dari `asOfBusinessDate - 89 hari` sampai asOf inklusif. Builder mengisi nol hanya dari baris pertama di jendela sampai asOf; jendela maksimal 90 hari dan observasi minimum `max(28, 21 + horizonDays)`. Gap di dalam rentang menjadi 0; builder tidak mengisi sebelum baris pertama atau sesudah asOf. Alur ini mengasumsikan ledger mencatat semua pergerakan relevan; tidak ada tabel kalender buka/tutup venue.
2. Builder mengubah net harian negatif menjadi 0. Ledger tetap utuh. `VOID_RETURN` dicatat pada business date void, bukan tanggal `SALE_DEDUCT` awal; void belakangan tidak membalik tanggal penjualan pada seri. Ini penyederhanaan input, bukan rekonsiliasi akuntansi. Perbaikan kelak dapat mengaitkan void lewat `void_logs.order_item_id`.
3. `predictedQuantity` adalah jumlah kumulatif prediksi harian non-negatif selama horizon.
4. `modelName` adalah nama metode pemenang (`ols_weekday_trend` atau `baseline_dow_mean`); `selectedModel` adalah `REGRESSION` atau `BASELINE`. MAE dihitung pada observasi input terakhir sebanyak horizon hari; nilai lebih rendah memilih metode dan jika MAE sama (tie), REGRESSION dipilih. Evidence C menunjukkan hasil pilihan dapat lebih buruk dari kedua metode pada held-out B (GRAM): 38.413 dibanding Regression 37.706 dan Baseline 37.335.
5. Error memakai ProblemDetail tanpa properti `code`. Pre-check lokal menghasilkan 422 dengan detail `INSUFFICIENT_DATA: available=X required=Y`; upstream 422 dengan `INSUFFICIENT_DATA` atau `INVALID_SERIES` menghasilkan detail yang sama dengan kode upstream. Kegagalan upstream/transport lain, termasuk timeout, connection error, 5xx, 401/404, malformed atau incomplete 200, dan 422 tanpa kode, menghasilkan 503 dengan detail `Forecast service unavailable`. Body upstream dan nilai internal token tidak dikembalikan.
6. Skala JSON number tidak dijamin (`12.5` dapat muncul sebagai `12.500`); database menyimpan `predicted_quantity` sebagai `NUMERIC(14,3)`.
7. Route hanya `POST /api/v1/forecasts`. Tidak ada UNIQUE atau idempotensi untuk forecast; dua request sukses membuat dua baris.

### Fase 10 — Hybrid chat (Tier 2)
**FR**: FR-ML-01, FR-ML-02, FR-CHAT-01..12, BR-24.

**Status per sub-fase:**

| Sub-fase | Cakupan | Status | Komit |
|---|---|---|---|
| 10.1 | Fondasi database/skema chat | ACCEPTED | `58f6063` |
| 10.2 Round A | Tahap persiapan classifier (hash tidak dicatat di dokumen ini) | ACCEPTED | - |
| 10.2 Round B1 | Tahap persiapan classifier (hash tidak dicatat di dokumen ini) | ACCEPTED | - |
| 10.2 Round B2 | Model K, evaluator, dan laporan validasi B2 resmi | CLOSED (PM) | `6f37e80`, `1a555f9` |
| 10.2 Round C | Endpoint internal `POST /ml/chat/classify` di ml-service | ACCEPTED (PM) | `ce0598c` |
| 10.2 Round D | Packaging Docker dan gerbang CI; CI run #5 hijau | ACCEPTED (PM) | `3e1f2a5`, `f27f056` |
| 10.3 | Spring chat API, integrasi classifier, implementasi routing policy ADR-011, kontrak OpenAPI, threshold milik Spring | NOT STARTED | - |
| 10.4 | BYOK Gemini/Groq | NOT STARTED | - |
| 10.5 | Telemetry | NOT STARTED | - |
| 10.6 | Integrasi, security, acceptance, dan independent heldout | NOT STARTED | - |
| 10.7 | Regresi akhir dan review README akhir | NOT STARTED | - |

> [!IMPORTANT] Sub-fase 10.1 hanya fondasi database/skema chat; itu bukan Spring chat API, routing, atau integrasi (10.3). Endpoint classifier sudah ada di ml-service (10.2 Round C), tetapi Spring belum memanggilnya.

**val_accept_b2.jsonl adalah validation set** (dipakai untuk seleksi model dan theta), **bukan blind test**. Set held-out independen (Fase 10.6) belum ada di repositori ini.

**Theta canonical**: MENUNGGU KEPUTUSAN PM. Nilai evaluasi B2: Python 3.10.6 menghasilkan `0.2663504950761665`; Python 3.12.9 menghasilkan `0.2663504950761663`. Perbedaan ada pada digit ke-16 (floating-point platform). Nilai tidak dibulatkan; keputusan nilai kanonik untuk konfigurasi Spring diserahkan ke PM.

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
