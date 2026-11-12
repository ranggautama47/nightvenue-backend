# DECISIONS — Architecture Decision Records NightVenue

Satu-satunya tempat ADR. `APPLICATION_ARCHITECTURE.md` hanya menunjuk ke sini.

**Aturan membuka ulang keputusan** (dari PM sync): hanya untuk (1) celah keamanan, (2) risiko korupsi data, (3) batasan deployment yang mustahil, (4) kegagalan performa yang *terukur*, (5) requirement yang saling bertentangan. Alasan seperti "framework lain lebih keren" tidak valid. ADR baru diajukan tertulis ke PM sebelum diimplementasikan.

| ADR | Judul | Status |
|---|---|---|
| 001 | Business Day Cutoff | Accepted |
| 002 | Cashier Shift dan Batas Business Day | Accepted |
| 003 | Stock-at-Order + atomic conditional UPDATE | Accepted |
| 004 | VOID sebagai kompensasi (disposition + exactly-once) | Accepted |
| 005 | Model pembayaran: Order 1:N Payment | Accepted; provider-specific clauses partially superseded by proposed ADR-022 |
| 006 | Webhook: signature vs idempotency | Accepted; provider-specific clauses partially superseded by proposed ADR-022 |
| 007 | Uang dan kuantitas | Accepted |
| 008 | Dua repo, modular monolith Spring, ML di repo backend | Accepted |
| 009 | Same-origin lewat Next.js rewrites, refresh token cookie | Accepted |
| 010 | Customer terpisah dari user | Accepted |
| 011 | Hybrid chat routing | Accepted |
| 012 | Spring deterministic fallback (bukan classifier kedua) | Accepted |
| 013 | BYOK: jalur key, penyimpanan, teks UI | Accepted |
| 014 | Batas FastAPI dan aturan evaluasi ML | Accepted |
| 015 | Forecasting dibandingkan baseline | Accepted |
| 016 | Kebijakan metrik jujur | Accepted |
| 017 | Scope Month-1, deferred scope, urutan potong | Accepted |
| 018 | Kebijakan versi framework | Accepted |
| 019 | Target hosting backend | **Proposed** (diputuskan setelah spike Phase 1) |
| 020 | Verifikasi kepemilikan botol (QR token) | Accepted |
| 021 | Phase 5C VOID dan SERVE | Accepted |
| 022 | Xendit Payment Requests v3, M3-A/V9 scope, and M4 contract notes | **Approved with binding notes** |

---

## PM Decision — Idempotency-Key on POST /orders (Phase 5A)

Approved for Phase 5A. `POST /api/v1/orders` requires a UUID `Idempotency-Key` for both table and walk-in orders. Scope is `ORDER_OPEN`, with identity `(scope, user_id, key)`; the cashier identity comes only from the JWT. The normalized request hash uses `venueId|tableId|customerId`, with null values represented by `-`.

An identical retry returns HTTP 201 and the original creation-time response snapshot without creating another order, consuming another sequence value, or touching the table. Reusing the key with a different normalized payload returns 422. The snapshot is stored and replayed through the normal response serializer.

Records are retained for at least 24 hours. Cleanup is deferred; after a record is actually removed, replay of an old key is no longer guaranteed. This decision reverses the earlier required-list that omitted order opening and extends item 4 of the Phase 5 Definition of Done in PHASES.md.

---

## PM Decision — Tax Base and Financial Calculations (Phase 5B)

The service-charge and tax calculation base is a NightVenue product rule decided by PM. It is not sourced from PRD/ADR-007 and is not a statement about Indonesian tax law.

The approved formula is:

```text
base = subtotal - discount_amount
service_charge_amount = applyBps(base, service_charge_bps)
tax_amount = applyBps(base, tax_rate_bps)
total_due = base + service_charge_amount + tax_amount
```

`MoneyMath.applyBps(base, bps)` uses BigDecimal, divides by 10000 with `RoundingMode.HALF_UP`, and converts with `longValueExact()`.

---

## ADR-001 — Business Day Cutoff

### Status
Accepted.

### Context
Nightlife venues operate beyond calendar midnight. An order at 02:00 may operationally belong to the same venue night that started at 20:00 on the previous calendar date.

### Decision
Each venue defines `timezone` and `business_day_cutoff_hour` (0–23, default demo `6`).

A canonical `BusinessDateService` calculates the operational date:

```text
local         = instant.atZone(venue.timezone)
business_date = local.toLocalDateTime().minusHours(cutoffHour).toLocalDate()
```

`LocalDateTime` subtracts wall-clock hours across DST transitions; `ZonedDateTime.minusHours` subtracts elapsed hours and can assign the wrong business date.

| Waktu lokal (Asia/Jakarta), cutoff = 6 | business_date |
|---|---|
| 2026-10-24 02:30 | 2026-10-23 |
| 2026-10-24 05:59:59 | 2026-10-23 |
| 2026-10-24 06:00:00 | 2026-10-24 |
| 2026-10-23 20:00 | 2026-10-23 |

Kasus DST (pembuktian mengapa `LocalDateTime` wajib):

| Instant (UTC) | Zona venue | cutoff | Waktu lokal | business_date |
|---|---|:-:|---|---|
| 2026-03-08T10:00:00Z | America/New_York | 6 | 2026-03-08 06:00 EDT (DST mulai 07:00Z) | 2026-03-08 |

`ZonedDateTime.minusHours(6)` pada kasus ini menghasilkan 2026-03-07 (salah).

Cutoff `0` berarti sama dengan tanggal kalender. Tidak ada modul lain yang boleh menghitung business date sendiri; waktu "sekarang" selalu dari `Clock` yang diinjeksi (agar boundary dapat diuji).

Records yang butuh pengelompokan hari operasional menyimpan `business_date` **saat dibuat** dan tidak pernah dihitung ulang. Tabel mana yang menyimpan, beserta alasannya per tabel, ada di `DATABASE.md` bagian "Audit business_date".

### Consequences
- Laporan, shift reconciliation, forecasting, dan analisis pembayaran memakai Business Date.
- Tengah malam kalender tidak otomatis membuat hari operasional baru.
- Order yang dibuka sebelum cutoff dan dibayar sesudahnya: `orders.business_date` = saat dibuka, `payments.business_date` = saat dibayar. Keduanya benar menurut definisinya dan dapat berbeda satu hari. Laporan wajib menyebut basis yang dipakai.
- Mengubah `business_day_cutoff_hour` venue tidak mengubah record lama.

Decision is locked.

---

## ADR-002 — Cashier Shift dan Batas Business Day

### Status
Accepted.

### Context
A cashier may open a shift before midnight and continue working after midnight.

### Decision
One cashier shift belongs to exactly **one Business Date**, inherited from its opening time. Calendar midnight does not split a shift. A shift must not silently remain open beyond the next Business Day cutoff: when the cutoff is reached while the shift is still `OPEN`, status becomes `MANAGER_REVIEW` and reconciliation must be explicitly resolved.

Catatan implementasi (menutup celah yang tidak tertulis di dokumen PM):

1. **Atribusi kas memakai `payments.shift_id`, bukan `business_date`.** Karena order dan pembayaran bisa melewati cutoff, mengagregasi kas per tanggal akan salah attribusi. `expected_cash = opening_float + SUM(amount) payment CASH berstatus PAID dengan shift_id = shift ini`.
2. **Pengecekan cutoff ada di jalur request**, bukan hanya di job. Pembuatan payment menolak (409 `SHIFT_NOT_OPEN`) jika `shift.status != OPEN` atau `shift.business_date != businessDate(now)`. Job terjadwal hanya membalik status OPEN → MANAGER_REVIEW; ia tidak menjadi satu-satunya penjaga.
3. Satu cashier hanya boleh punya satu shift `OPEN` (partial unique index).
4. Status: `OPEN → SUBMITTED → RECONCILED` (jalur normal) dan `OPEN → MANAGER_REVIEW → RECONCILED` (jalur cutoff). Pada jalur cutoff, kas aktual dimasukkan oleh Manager saat review.
5. Variance = `actual_cash − expected_cash`. Catatan review wajib bila variance ≠ 0 atau shift berasal dari jalur cutoff.
6. Konsekuensi operasional yang diterima: kasir yang masih bekerja saat cutoff harus submit dan membuka shift baru.

### Consequences
- Midnight does not split reports.
- Shift yang tidak selesai menjadi exception operasional yang terlihat.
- Review manager dapat diaudit.

Cakupan Month-1: shift hanya merekonsiliasi **kas** (CASH). Tip, cover charge, dan bottle service sebagai alur uang tersendiri **tidak dimodelkan**. Alasan nightlife untuk shift reconciliation tetap berlaku sebagai narasi, tetapi README tidak boleh mengklaim fitur yang tidak ada.

### Addendum v2.1 (ADR-002)
Aturan CASH berlaku untuk **semua role**: setiap payment CASH harus teratribusi ke shift `OPEN` milik pemrosesnya. Manager/Admin yang menerima tunai membuka shift sendiri ("role ≠ mode kerja"). Review shift tidak boleh dilakukan oleh pemilik shift (BR-30).

---

## ADR-003 — Stock-at-Order dan atomic conditional UPDATE

### Status
Accepted.

### Context
Minuman dibuat sebelum tagihan dibayar. Memotong stok saat pembayaran sukses membuka celah antara validasi dan pemotongan (oversell). Bahan seperti es dan vodka adalah baris panas; optimistic locking menghasilkan retry berantai.

### Decision
- Stok dipotong **saat item diterima ke order** (status `ORDERED`), dalam transaksi DB yang sama dengan insert item. "Order" pada istilah Stock-at-Order berarti penerimaan item ke tab, karena tab nightlife dibuka lalu ditambah bertahap.
- Pemotongan memakai `UPDATE inventory_items SET quantity = quantity - :need WHERE id = :id AND quantity >= :need`. `rows = 0` → 409 `INSUFFICIENT_STOCK` dan seluruh transaksi rollback.
- Bahan diproses terurut `ingredient_id ASC` untuk mencegah deadlock.
- Setiap perubahan menulis `inventory_transactions` (`balance_after`, `business_date`).
- Lokasi yang dipotong: satu lokasi `BAR` per venue (BR-26).
- Tidak ada Redis distributed lock tanpa bukti pengukuran.

### Consequences
- Webhook pembayaran tidak punya efek samping inventory.
- Wajib ada jalur kompensasi (ADR-004).
- Optimistic `@Version` tetap dipakai untuk entitas jarang bentrok.

### Addendum v2.1 (ADR-003): urutan operasi
`order_items` di-INSERT lebih dulu dalam transaksi yang sama sehingga `order_item_id` ada untuk `reference_id` ledger; kegagalan stok me-rollback seluruh transaksi termasuk order item. Baris order dikunci (`FOR UPDATE`) di awal. Pasangan (item, bahan) diurutkan menurut `inventory_items.id` ASC lalu `order_item_id` ASC agar satu ledger row tetap menunjuk satu item. Urutan lock global: `orders` → `payments` → `inventory_items` (ID menaik).

---

## ADR-004 — VOID sebagai kompensasi

### Status
Accepted. Memperhalus teks PM sync pada satu titik (lihat "Koreksi").

### Context
Stock-at-Order mengharuskan jalur pengembalian. Namun alasan Stock-at-Order adalah minuman *sudah dibuat* sebelum dibayar. Mengembalikan semua bahan ke stok pada setiap VOID akan mencatat stok untuk minuman yang sudah dituang.

### Decision
`POST /api/v1/orders/{orderId}/void` dengan `reason` wajib dan `disposition`:

| Status item saat void | disposition | Efek stok |
|---|---|---|
| `ORDERED` (belum disajikan) | `RESTORED` (default) | Dikembalikan lewat `VOID_RETURN` |
| `ORDERED` | `WASTED` (hanya Manager/Admin) | Tidak dikembalikan; dicatat sebagai waste di `void_logs` |
| `SERVED` | selalu `WASTED` | Tidak dikembalikan |

Aturan:
1. **Pengembalian dihitung dari ledger**, bukan dari recipe saat ini: baris `SALE_DEDUCT` milik item dibalik (`VOID_RETURN` dengan delta lawan). Perubahan recipe di antara order dan void tidak memengaruhi hasil.
2. **Exactly-once**: `void_logs.order_item_id` unik, dan transisi status order memakai conditional UPDATE (`WHERE status = 'OPEN'`) sebagai gerbang sebelum stok disentuh. Request VOID kedua → 409, stok tidak berubah.
3. Payment guard for PENDING/PAID transactions: deferred to Phase 6. Not implemented in Phase 5C. Order yang sudah dibayar belum punya jalur VOID; koreksi = refund (deferred).
4. Void di atas `venues.void_manager_approval_threshold` oleh CASHIER → 403 `MANAGER_APPROVAL_REQUIRED`; Manager/Admin yang mengeksekusi. Nilai ambang adalah konfigurasi (angka seed hanya contoh demo).
5. Month-1 mengimplementasikan VOID **seluruh order**. Skema (`order_items.status = VOIDED`, `void_logs` per item) sudah siap untuk item-level VOID; keterbatasan ini didokumentasikan di README.
6. Seluruh langkah dalam satu transaksi, ditutup dengan baris di `audit_logs`.

### Consequences
- Tidak ada double-restock, dibuktikan test.
- Keputusan RESTORED vs WASTED ada di tangan staf, bukan ditebak sistem; ini asumsi yang harus dijelaskan owner.

### Koreksi terhadap teks PM sync
Teks PM: "restore recipe ingredients" tanpa syarat. Itu bertentangan dengan alasannya sendiri (minuman dibuat sebelum dibayar). Diperbaiki dengan `disposition` dan pengembalian berbasis ledger.

---

## ADR-005 — Model pembayaran

### Status
Accepted. Provider-specific clauses are **partially superseded** by proposed ADR-022 only; the payment-domain decisions below remain in force.

### Decision
- Historical v2.1 provider-specific decision, retained for audit: canonical methods were `CASH`, `CARD_EDC`, and `QRIS_MIDTRANS`; only QRIS used a webhook; Midtrans was behind `PaymentGateway` alongside a fake test implementation. This provider choice is partially superseded by proposed ADR-022 and does not change legacy V8 rows.
- Legacy method `QRIS_MIDTRANS` remains valid. The proposed Xendit method/provider mapping is subject to PM schema approval; the domain rules in this ADR remain unchanged.
- Status payment: `PENDING, PAID, FAILED, EXPIRED, CANCELLED, REFUNDED`. `REFUNDED` dicadangkan untuk fitur refund yang deferred.
- **Order → Payment adalah 1:N** secara permanen. Order `PAID` saat `SUM(PAID) >= total_due` dan syarat minimum spend terpenuhi. Pembayaran dengan nominal (split by amount) didukung backend sejak Month-1; UI split-bill dan `payment_allocations` (split per item) deferred.
- `amount` payment ≤ sisa tagihan saat dibuat (422 bila melebihi). Kembalian tunai adalah urusan UI.
- Settlement yang datang untuk order `VOIDED` tetap dicatat (`payments.status = PAID`), order tidak berubah, dan `audit_logs` menerima aksi `PAYMENT_ON_CLOSED_ORDER` untuk ditangani manual (BR-21).
- The historical Midtrans provider choice is partially superseded by approved ADR-022; preserve legacy Midtrans rows and behavior.

**Preserved domain decisions:** Order 1:N Payment, split-payment-by-amount rules, amount reservation, order-row locking, concurrency invariants, settlement-on-VOIDED audit behavior, and the rule that external calls run outside locked database transactions.

### Consequences
Skema tidak perlu diubah ketika split-bill UI dibangun.

### Addendum v2.1 (ADR-005): konkurensi payment
- `amount ≤ total_due − SUM(PAID) − SUM(PENDING)`; PENDING menahan sisa.
- Pembuatan payment diserialkan lewat `SELECT ... FOR UPDATE` pada baris order. `Idempotency-Key` tidak menggantikan ini (hanya melindungi request identik).
- Historical v2.1 provider-specific flow, retained for audit: Midtrans was called between TX1 and TX2; a definite call failure was recorded as `FAILED`, and a PENDING payment without a provider reference was scheduled for expiry.
- The TX1/provider-call/TX2 shape remains a domain boundary, but provider-specific failure handling is superseded by proposed ADR-022. An ambiguous Xendit Create must never trigger an automatic second Create: keep business status `PENDING`, keep the reservation active and BR-20 VOID protection active, and represent recovery separately. `PENDING_MANUAL_REVIEW` is not a payment business status. The operator/reconciliation procedure is OPEN.

---

## ADR-006 — Webhook: signature vs idempotency

### Status
Accepted. The distinction between sender verification and event deduplication remains in force. Midtrans-specific signature, status API, and event-key clauses are **partially superseded** by proposed ADR-022 only.

### Decision
Dua mekanisme menyelesaikan dua masalah berbeda dan keduanya wajib; exact provider authentication and event-key contracts remain provider-specific:

| Mekanisme | Masalah yang diselesaikan | Tidak menyelesaikan |
|---|---|---|
| Verifikasi `signature_key` (SHA-512 dari `order_id + status_code + gross_amount + serverKey`) | Pengirim palsu / payload dimodifikasi | Pengiriman ulang notifikasi yang sah |
| Unique `(provider, event_key)` di `payment_events` | Notifikasi sah yang terkirim berkali-kali | Pengirim palsu |

The historical Midtrans Status API confirmation and signature formula are not Xendit contracts. Provider status confirmation, callback verification, and event-key shape for Xendit remain OPEN until verified. The state-machine principle that rejects backward transitions (for example, settlement followed by pending → `IGNORED_STALE`) remains applicable.

The historical `event_key = transaction_id + ':' + transaction_status + ':' + status_code` is Midtrans-specific and is not adopted for Xendit without provider-contract verification.

### Consequences
Satu panggilan eksternal tambahan per webhook. Panggilan HTTP dilakukan di luar transaksi DB yang memegang lock.

### Addendum v2.1 (ADR-006): semantik `payment_events`
`payment_events` = satu record kanonik per `event_key` (hasil dedup), bukan log setiap pengiriman. Duplikat memakai `INSERT ... ON CONFLICT DO NOTHING` (bukan menangkap exception, karena transaksi PostgreSQL batal pada pelanggaran unique). Enum `result`: `PROCESSED`, `IGNORED_STALE`, `REJECTED`. `IGNORED_DUPLICATE` dihapus. Duplikat teramati lewat log terstruktur; tabel receipt ditunda.

---

## ADR-007 — Uang dan kuantitas

### Status
Accepted.

### Decision
Uang: `BIGINT` rupiah. Kuantitas bahan: `NUMERIC(14,3)` di DB, `BigDecimal` di Java. `double`/`float` dilarang. Pajak/service dalam basis point; pembulatan satu fungsi terpusat dengan unit test.

---

## ADR-008 — Dua repo, modular monolith, ML di repo backend

### Status
Accepted.

### Decision
`nightvenue-frontend` dan `nightvenue-backend` (berisi `api/`, `ml-service/`, `infra/`). Tidak ada repo ketiga. Spring Boot adalah modular monolith package-by-feature; tidak ada microservice Java.

### Consequences
Kontrak FE–BE lewat OpenAPI: tipe TypeScript digenerate dari spesifikasi backend dan CI frontend gagal bila berubah tanpa pembaruan.

---

## ADR-009 — Same-origin dan token

### Status
Accepted.

### Decision
Browser hanya memanggil origin frontend; Next.js `rewrites` meneruskan `/api/*` ke Spring Boot. Access token JWT pendek disimpan di memori. Refresh token acak, hash-nya di Redis dengan rotasi, dikirim sebagai cookie `httpOnly; Secure; SameSite=Lax`. Reuse refresh token → seluruh sesi user dicabut.

### Consequences
Menghindari cookie lintas-situs. Seluruh panggilan API frontend harus lewat proxy.

### Addendum Phase 3 — Refresh Token Rotation and Reuse Policy

**Kebijakan reuse: STRICT.** Reuse refresh token (token yang sudah dipakai dan dirotasi dipakai lagi) mencabut **seluruh** sesi user (semua family), bukan hanya family tempat reuse terjadi. Alasan: token baru tidak dapat dikembalikan ke client karena hanya hash yang disimpan server.

**Penyimpanan tombstone:** Token lama yang sudah dirotasi **tidak langsung dihapus**. Statusnya diubah menjadi `USED` dengan TTL sisa dari token asli. Ini memungkinkan deteksi reuse sampai TTL habis. Tanpa tombstone, reuse tidak dapat dibedakan dari token random yang tidak dikenal.

**Atomicity rotasi:** Operasi rotasi (validasi token lama → tandai USED → buat token baru → perpanjang family TTL) **WAJIB atomik** menggunakan Redis Lua script. Pola GET→DEL→SET biasa rentan race condition pada concurrent refresh (dua tab, retry otomatis). `MULTI/EXEC` tidak cukup karena tidak mendukung conditional write berdasarkan hasil read.

**Resolusi IP untuk rate limiting:** Karena arsitektur Next.js rewrite, Spring melihat IP proxy. Gunakan `server.forward-headers-strategy=native` dengan `server.tomcat.remoteip.internal-proxies` dari env `APP_TRUSTED_PROXIES`. Dilarang membaca `X-Forwarded-For` secara manual atau menggunakan `ForwardedHeaderFilter` (tidak punya daftar proxy tepercaya, bisa di-spoof client).

**Konsekuensi operasional:** Dua request refresh paralel dengan token sama (dua tab, retry otomatis) menyebabkan yang kalah memicu reuse → seluruh sesi user dicabut. Frontend **WAJIB** single-flight refresh (queue request, jangan paralel). Keterbatasan ini didokumentasikan di `APPLICATION_ARCHITECTURE.md`.

---

## ADR-010 — Customer terpisah dari user

### Status
Accepted.

### Decision
Tabel `customers` dengan `user_id` nullable, agar tamu walk-in dapat memiliki botol tanpa akun login. Verifikasi usia disimpan sebagai flag + pemeriksa + waktu; **nomor identitas tidak disimpan**.

---

## ADR-011 — Hybrid chat routing

### Status
Accepted.

### Context
Chat harus tetap menjadi fitur utama (bukan FAQ statis), dapat dicoba recruiter tanpa API key, dan tidak membakar token untuk pertanyaan berulang.

### Decision
```text
Spring /api/v1/chat → rate limit → validasi input → FastAPI /ml/chat/classify
  intent lokal & confidence >= θ   → jawab dari PostgreSQL (0 token LLM)
  OUT_OF_SCOPE & confidence >= θ   → penolakan template (0 token LLM)
  ADVANCED_CONVERSATION atau confidence < θ
        → ada BYOK? LLM dengan konteks tepercaya + validasi output
        → tidak ada? ADVANCED_AI_REQUIRED
  FastAPI tidak tersedia → ADR-012
```
Sebelas intent: `OPENING_HOURS, VENUE_AREA, MENU_INFORMATION, BOTTLE_KEEPING, MINIMUM_SPEND, PROMOTION, TABLE_AVAILABILITY, RESERVATION, GENERAL_VENUE_INFORMATION, ADVANCED_CONVERSATION, OUT_OF_SCOPE`.

Aturan:
- θ dipilih dari **validation set** dan dicatat di sini setelah Phase 9. Nilai awal development hanya placeholder konfigurasi, bukan klaim.
- Classifier adalah mekanisme routing, **bukan batas keamanan**. Batas keamanan: konteks LLM hanya data publik venue, LLM tidak punya tool tulis, output divalidasi, rate limit.
- Karena reservasi deferred: `RESERVATION` dijawab dengan teks kebijakan, dan `TABLE_AVAILABILITY` menjawab **status meja saat ini**, bukan ketersediaan tanggal mendatang.
- Pemetaan intent → sumber data ada di `APPLICATION_ARCHITECTURE.md`.

### Consequences
Dua jalur kode, keduanya wajib diuji. Chat tidak memengaruhi transaksi: kegagalan chat tidak boleh menyentuh order/payment.

---

## ADR-012 — Spring deterministic fallback

### Status
Accepted.

### Decision
Bila FastAPI timeout/tidak tersedia, Spring memakai aturan regex/keyword **maksimal lima kategori**: jam buka, alamat venue, minimum spend, area venue, kebijakan bottle keeping. Dilarang: classifier Java kedua berisi 10–15 intent atau tiruan NLP. Tidak cocok → ada BYOK ? LLM : `ADVANCED_AI_REQUIRED`.

---

## ADR-013 — BYOK

### Status
Accepted.

### Decision
- Penyimpanan di browser: `sessionStorage` default; opsi "Ingat di perangkat ini" → `localStorage` dengan peringatan. Tidak ada klaim bahwa penyimpanan browser aman atau terenkripsi.
- Key dikirim di header `X-AI-Key` (+ `X-AI-Provider: GEMINI|GROQ`) ke Spring Boot, diteruskan ke provider untuk request itu saja. **Key transit melalui server NightVenue.**
- Server tidak menyimpan, tidak mencatat, dan tidak memasukkan key ke error telemetry. Test wajib membuktikan key tidak muncul di log, DB, atau respons.
- Teks UI yang akurat: *"Your API key is sent to NightVenue's backend for the current AI request and forwarded to the selected provider. NightVenue does not persist the key server-side."* Kalimat "key never leaves the browser" **dilarang** karena salah.
- `LlmProvider` adalah interface dengan dua implementasi (Gemini, Groq).

---

## ADR-014 — Batas FastAPI dan evaluasi ML

### Status
Accepted.

### Decision
FastAPI hanya: `POST /ml/chat/classify`, `POST /ml/forecast/inventory`, dan `GET /health`. Dilarang: auth, checkout, payment, mutasi inventory/bottle, aturan bisnis transaksional. Hanya dapat dijangkau dari jaringan Docker dengan `X-Internal-Token`. Kegagalan FastAPI tidak boleh menghentikan transaksi inti.

Evaluasi classifier (TF-IDF + Logistic Regression atau sekelas): training set, validation set, held-out test set, distribusi kelas, baseline keyword classifier, accuracy, precision/recall/F1 per kelas, confusion matrix, keterbatasan. **Aturan kebocoran**: set uji tidak boleh klon template dari set latih (hanya beda nama venue); wajib berisi parafrase tak terlihat dan campuran bahasa. Set uji sebaiknya ditulis terpisah dari set latih (idealnya oleh orang/agent berbeda). Dokumen menyebut: *"Evaluated using synthetic / hand-labeled portfolio data."*

Model dilatih offline, artefak (`joblib`) dibangun ke image; hanya memuat artefak buatan sendiri.

---

## ADR-015 — Forecasting dan baseline

### Status
Accepted.

### Decision
Model (regresi dengan fitur hari-dalam-minggu + tren) dibandingkan dengan baseline sederhana (rata-rata hari-yang-sama, moving average, atau minggu lalu). Respons memuat `modelMae` dan `baselineMae` dan model terpilih. Minimal 28 hari data, selain itu 422. Data konsumsi diagregasi per `business_date` dari ledger (`SALE_DEDUCT` dan `VOID_RETURN`). Data historis bersifat sintetis dan harus dilabeli demikian.

### Addendum v2.1 (ADR-015): rumus konsumsi
`net_consumption = -(SUM(delta WHERE type IN ('SALE_DEDUCT','VOID_RETURN')))`. Contoh: −10 dan +4 → 6.

---

## ADR-016 — Kebijakan metrik jujur

### Status
Accepted.

### Decision
Tidak ada angka kinerja, akurasi, atau penghematan di README atau dokumen sebelum diukur oleh test/benchmark NightVenue sendiri. Angka contoh dari DeepSeek (12,847 request; p95 342 ms; akurasi 87,3%; hemat 99,5%) adalah contoh *format bukti*, bukan hasil, dan tidak boleh disalin.

Bukti wajib: A (konkurensi inventory), B (idempotensi webhook), C (evaluasi ML), D (analisis biaya hybrid chat). Detail di `PRD.md` bagian 10.

---

## ADR-017 — Scope Month-1, deferred, urutan potong

### Status
Accepted.

### Decision
- **Tier 1 — Nightlife Transaction Core** dan **Tier 2 — AI/ML Differentiator** adalah target Month-1.
- **Deferred** (tidak boleh menghambat Tier 1/2, tidak boleh masuk diam-diam): reservasi, recipe versioning, last call, void anomaly detection, reaktivasi botol via email, simulasi what-if jam sibuk.
- **Deferred tambahan (tidak tercantum di dokumen PM; default Claude, perlu konfirmasi Owner di PRD OD-7)**: loyalty, transfer stok gudang→bar, password recovery, refund, laporan penjualan, split-bill per item (`payment_allocations`), VOID per item.
- **Urutan potong** bila terlambat: Tier 1 tidak boleh dipotong; Tier 2 dipotong dari belakang (Phase 10 sebelum Phase 9). Bukti A dan B (Phase 11) tidak boleh dilewati walau Tier 2 belum lengkap.
- 30 hari adalah target siklus engineering pertama, bukan alasan memalsukan logika transaksi.

### Addendum v2.1 (ADR-017): dua tingkat kesiapan
`CORE_PORTFOLIO_READY` = Tier 1 + Bukti A/B + deployment + README + understanding gate. `FULL_NIGHTVENUE_READY` = core + Tier 2 + Bukti C/D. Memotong Tier 2 tidak membatalkan status core.

---

## ADR-018 — Kebijakan versi framework

### Status
Accepted.

### Decision
Versi lama dalam draft (Next.js 14, Spring Boot 3.3, Python 3.11) tidak dipakai tanpa verifikasi. Phase 1 memilih rilis stabil yang saat itu masih didukung (utamakan LTS, hindari release candidate dan major yang deprecated), mencatat dan mem-pin versinya di `CONTEXT.md`. Setelah Phase 1, upgrade major memerlukan review eksplisit.

---

## ADR-019 — Target hosting backend

### Status
**Proposed.** Tidak dikunci dari asumsi free-tier lama.

### Decision
Phase 1 menjalankan spike ke 1–2 kandidat dan mencatat RAM, cold start, biaya, dukungan Spring + FastAPI + PostgreSQL + Redis eksternal, secret, health check, dan kebutuhan outbound provider. Referensi outbound Midtrans adalah historis; Xendit remains documentation-only until separate provider-traffic authorization. Hasil menggantikan status ADR ini. Frontend: Vercel kecuali ada blocker nyata. Demo publik wajib untuk portfolio; fallback bila hosting gagal: video demo + panduan menjalankan lokal.

---

## ADR-020 — Verifikasi kepemilikan botol

### Status
Accepted.

### Decision
Saat deposit, server membuat token acak 128-bit, menyimpan **hash SHA-256**-nya (`bottle_vaults.qr_token_hash`), dan mengembalikan token mentah **sekali** untuk dicetak sebagai QR pada label. "Redemption" berarti menuang dari vault. Redeem mensyaratkan: (1) token QR valid, (2) `orders.customer_id` = pemilik vault, (3) `ownerConfirmed = true` dari kasir. Setiap redemption mencatat pelaku dan konfirmasi di `bottle_vault_transactions`.

### Consequences
Ini bukti penguasaan label + pencocokan data, **bukan autentikasi kuat**: siapa pun yang memegang label dan dikonfirmasi kasir dapat redeem. Keterbatasan ditulis di README. Penerbitan ulang token untuk label hilang deferred.

### Addendum v2.1 (ADR-020): deposit exactly-once
`bottle_vaults.source_order_item_id` UNIQUE; deposit memakai `Idempotency-Key`; produk botol wajib `quantity = 1` per order item di Month-1 (satu botol fisik = satu order item). Unit-level deposit ditunda.

---

## ADR-021 — Phase 5C VOID dan SERVE

### Status
Accepted.

### Decision
- Phase 5C voids the entire OPEN order; it serves one ORDERED item. SERVE allows parent status OPEN, PARTIALLY_PAID, or PAID; VOIDED and REFUNDED are rejected.
- VOID defaults omitted disposition to RESTORED in application logic. The database has no default. SERVED items are always logged as WASTED. `approved_by` is set to the executing Manager/Admin only when subtotal is strictly above the venue threshold.
- VOID precedence is request/header validation; order read and venue authorization; CASHIER explicit WASTED rejection; idempotency reserve/replay; order lock; OPEN status check; cashier threshold/SERVED approval check; execute.
- Restore uses original SALE_DEDUCT ledger rows, one VOID_RETURN per row, with matching void_log reference. Missing deductions fail and roll back the transaction. Global ordering is `inventory_items.id ASC`, then `order_item_id ASC`, then ledger ID; inventory locks are acquired once per inventory ID in ascending order.
- Partial loss of SALE_DEDUCT rows is not detectable from the current schema; only zero SALE_DEDUCT rows for an ORDERED+RESTORED item is enforced.
- Table release skips null table IDs. A non-null table must transition OCCUPIED to AVAILABLE exactly once; zero rows is an invariant failure and rolls back VOID.
- `ManagerApprovalRequired` maps to HTTP 403 with `MANAGER_APPROVAL_REQUIRED`. Idempotency reservation, response, and all business effects share one transaction.
- Payment guard for PENDING/PAID transactions: deferred to Phase 6. Not implemented in Phase 5C.

### Consequences
The payment-vs-VOID race and payment state guard are Phase 6 responsibilities. PARTIALLY_PAID orders have no cancellation path until refund support exists.

---

## ADR-022 — Xendit Payment Requests v3 (M3-A/V9 approval and M4 contract)

### Status
**Approved with binding notes (PM FINAL SYNC).** M3-A, V9, and their schema tests are approved in the specified commit order. This does not authorize starting M3-B implementation or M4 provider traffic.

### Context
The Phase 6 documentation currently contains Midtrans-specific behavior that must not be presented as the planned Xendit contract. V1–V8 are immutable, and the V8 schema contains legacy `QRIS_MIDTRANS` data and constraints.

### Proposed decision
- Use Xendit Payment Requests v3 for the approved M3-A/V9 scope. M4 implementation remains future work under the contract below.
- Preserve the domain decisions from ADR-005/006: Payment 1:N, split-payment-by-amount, amount reservation, order locking, concurrency invariants, deduplication principles, and audit behavior. ADR-005 and ADR-006 are only partially superseded for provider-specific clauses.
- Keep `provider_order_id` for legacy Midtrans values; V9 adds `provider_reference_id` for NightVenue's Xendit `reference_id` and nullable `provider_request_id` for Xendit's `payment_request_id`. `provider_transaction_id` remains nullable; its exact Xendit `payment_id` mapping is OPEN_QUESTION for M4.
- Do not assume Get Payment Request can query by `reference_id`; its documented path requires `payment_request_id`.
- An ambiguous Create outcome never triggers automatic Create retry. Initially keep payment status `PENDING`, the amount reservation active, and BR-20 VOID protection in force. M4 may move `AMBIGUOUS` to `PAID` only after authenticated and correlated provider evidence; do not force `charge_state=ACCEPTED` to permit that status.
- The M4 contract must permit nullable or populated `provider_request_id` for `AMBIGUOUS`, so an authenticated Xendit webhook can recover `payment_request_id`. Correlation uses `reference_id`, `business_id`, `currency`, `amount`, `payment_id`, and `payment_request_id`.
- M4 must validate the webhook callback token and protect against duplicate events. Preserve the unique `(provider, event_key)` guard; exact Xendit event-key construction remains OPEN.
- V9 adds `AMBIGUOUS` while keeping V1–V8 unchanged. Its status matrix permits `AMBIGUOUS` with `PAID` after the application has verified settlement evidence.
- M3-B implementation is not part of this closure. Keep unverified QRIS request/action shape, expiry, Create idempotency, and other provider-specific semantics open.
- Frontend receives safe QR data from NightVenue only, polls the NightVenue payment GET endpoint, and never calls Xendit directly. QR rendering details remain open until the QRIS Indonesia response is verified.

### Consequences
The approved commit order is M3-A only, then V9 plus `PaymentSchemaMigrationTests`, then documentation and `.env.example`. Full regression must pass before commits; preserve unrelated worktree changes, do not stage unrelated files, and do not push to main.

### PM Decision Addendum — M4-A (approved)

- The V8 `payment_events.payment_id NOT NULL` contract remains unchanged. After callback authentication and successful Status API verification, a genuinely unmatched callback is acknowledged with HTTP 200, creates no event/payment/order row, and emits a sanitized warning. No V10 migration is authorized for unmatched events.
- A locally terminal `FAILED`, `EXPIRED`, or `CANCELLED` payment never transitions to `PAID` in M4-A, even when Xendit reports `SUCCEEDED`. Record a linked `REJECTED` event, an anomaly, and a sanitized manual-review warning. Do not change `PaymentStateMachine`.
- Authenticated, correlated, Status-API-verified `SUCCEEDED` may recover `SENDING`/`NOT_SENT` to `status=PAID`, `charge_state=AMBIGUOUS`, and the verified request ID in one conditional update. The reference, request ID, amount, currency, country, type, channel, and provider status must match; never force `ACCEPTED`.
- Status API 404, timeout, connection errors, 5xx, 401/403, or malformed responses cause HTTP 503 and no event/payment mutation. 404 never means payment failure or expiry.
- M4-A handles documented `payment.capture` and `payment.failure` payloads under top-level `event` and nested `data`. Do not infer `payment_request.expiry` fields; defer it to M4-B reconciliation.

### PM Closure — M4-A/M4-B (approved)

- Reconciliation defaults (D1, approved): `enabled=false`, `fixedDelay=PT5M`, `minAge=PT1H`, `batchSize=50`, `maxPerCycle=10`. Initial operational defaults, not guaranteed SLAs. Scheduling stays disabled by default; do not auto-enable it in production configuration.
- Provider `CANCELED` (D2, approved): no payment status mutation, no event insertion, one sanitized warning per cycle reporting affected payment IDs for manual review. `PaymentStateMachine` is not modified for `PENDING -> CANCELLED`; reservations remain held and BR-20 may block VOID until the row is manually resolved.
- SENDING / missing `provider_request_id` (D3, approved): never polled, never marked FAILED/EXPIRED/PAID from elapsed time; manual review only. Recovery through the webhook remains allowed only with verified provider evidence and reference correlation.
- Terminal resurrection (D4, REJECTED): FAILED/EXPIRED/CANCELLED never become PAID. The M4-A policy stands: unchanged payment plus a linked `REJECTED` event and anomaly.
- Payment request ID mismatch (D5): a Status API response whose `id` differs from the requested ID is MALFORMED and can never settle or mutate a payment (unit test `statusApi_responseIdDifferentFromRequestedId_isMalformed`, integration test `paymentRequestIdMismatchResponseNeverMutatesOrSettles`).
- SENDING + verified `payment.failure` (D6, approved): the settlement CAS writes `charge_state=AMBIGUOUS` together with `status=FAILED` in one conditional update. `AMBIGUOUS` records the unknown local Create acknowledgement while the payment outcome has been independently verified as FAILED. Verified against the committed V9 constraints on Testcontainers PostgreSQL (no SQLSTATE 23514); see `paymentFailureOnSendingRowWritesV9ValidFailedOutcome`.
- Reconciliation events use `event_key = reconcile:<providerStatus>:<payment_request_id>:<payment_id>` so one outcome cannot shadow a later one; the scheduler is conditional on `enabled=true` and multi-instance safety relies on the settlement CAS, not a distributed lock.
- No automatic local QRIS expiry assumption: `payment_request.expiry` stays acknowledged with 200 and no change; expiry is only resolved through the verified Status API.

---

## ADR-023 — Runbook operasional: diagnostik read-only untuk payment QRIS Xendit yang belum tuntas (M5-C, approved)

### Konteks

M4-A/M4-B (settlement + reconciliation) dan M5-A/M5-B meninggalkan beberapa kategori baris
payment yang belum tuntas (SENDING, AMBIGUOUS tanpa request id, key idempotency in-flight).
Keputusan ini mendokumentasikan diagnostik **read-only** untuk operator. Semua query di bawah
telah diverifikasi terhadap V4/V6/V8/V9 dan dieksekusi pada PostgreSQL Testcontainers oleh
`PaymentRunbookQueriesIntegrationTests` (SQL di file ini identik dengan SQL yang dijalankan test).
Tidak ada pernyataan UPDATE/DELETE/INSERT operasional di runbook ini.

### Fakta schema/source yang menjadi dasar (terverifikasi)

- Predikat "active payment" = `status IN ('PENDING','PAID')` — persis
  `PaymentRepository.sumActiveByOrder()` (PaymentRepository.java:60-67) dan
  `existsActiveByOrder()` (PaymentRepository.java:42-49).
- BR-28: outstanding = `orders.total_due - sumActiveByOrder(orderId)` (QrisCreateTransactions.java:110-113).
- BR-20: `voidOrder` menolak VOID bila `existsActiveByOrder(orderId)` true, sambil memegang lock
  order, sebelum gerbang status OPEN (OrderLifecycleService.java:87-92).
- `payments.amount BIGINT`, `orders.total_due BIGINT`, `orders.paid_total BIGINT` (V8:27, V6:16-17).
- `payments.charge_state`, `payments.charge_started_at`, `payments.provider_request_id` (V8:33-34; V9 menambah
  `provider_reference_id`, `provider_request_id VARCHAR(39)`, `qr_payload`).
- `ck_payments_charge_started` (V8:61-63) hanya mewajibkan `charge_started_at` NOT NULL untuk
  `SENDING`; baris `NOT_SENT` boleh memiliki `charge_started_at` NULL — karena itu Q1/Q2
  memakai `(charge_started_at IS NULL OR charge_started_at < now() - interval '1 hour')`.
- `idempotency_keys`: `response_status INT` nullable, `created_at TIMESTAMPTZ NOT NULL` (V4:29-38)
  → diagnostik usia in-flight key didukung schema.
- **Tidak ada** job cleanup idempotency di production source; hanya komentar "eligible for cleanup
  ... deferred" (IdempotencyRepository.java:15-17). Key in-flight bisa bertahan tanpa batas waktu.
- Kandidat reconciliation = Q3, mirror persis `findXenditReconciliationCandidates()`
  (PaymentRepository.java:102-112). Scheduler default `enabled=false` (PaymentReconciliationProperties.java:18-34);
  mengaktifkannya adalah keputusan operasional terpisah. Provider `CANCELED` = no-op dengan
  warning tersanitasi (PaymentReconciliationService.java:114-118).

### Q1 — Stuck SENDING / NOT_SENT

```sql
-- Q1: Xendit QRIS rows stuck in SENDING / NOT_SENT (threshold = now - min_age)
SELECT id, charge_state, charge_started_at, provider_reference_id
FROM payments
WHERE provider = 'XENDIT'
  AND method = 'QRIS'
  AND status = 'PENDING'
  AND charge_state IN ('SENDING', 'NOT_SENT')
  AND (charge_started_at IS NULL OR charge_started_at < now() - interval '1 hour')
ORDER BY charge_started_at ASC NULLS LAST, id ASC;
```

Catatan: operator dapat menyesuaikan interval waktu (default minAge reconciliation adalah 1 jam / PT1H).
Baris ini tidak selectable oleh reconciliation (Status API tidak bisa dipanggil tanpa
`provider_request_id`; `findXenditReconciliationCandidates` mengecualikan SENDING/NOT_SENT).
Recovery via webhook hanya mungkin bila provider benar-benar membuat Payment Request dan
callback membawa `reference_id` yang berkorelasi (`nv_pay_<id>`). Dari state DB saja, baris
tidak dapat diklasifikasikan aman sebagai "belum dibayar": provider mungkin sudah membuat
request (read timeout) atau belum (connect failure) — M5-B membuktikan keduanya menghasilkan
state DB yang sama.

### Q2 — AMBIGUOUS tanpa provider_request_id

```sql
-- Q2: Xendit QRIS AMBIGUOUS rows without provider_request_id (threshold = now - min_age)
SELECT id, charge_state, charge_started_at, provider_reference_id
FROM payments
WHERE provider = 'XENDIT'
  AND method = 'QRIS'
  AND status = 'PENDING'
  AND charge_state = 'AMBIGUOUS'
  AND provider_request_id IS NULL
  AND (charge_started_at IS NULL OR charge_started_at < now() - interval '1 hour')
ORDER BY charge_started_at ASC NULLS LAST, id ASC;
```

Reconciliation tidak dapat memquery baris ini — mengaktifkan reconciliation TIDAK membantu.
Connect failure dan read timeout tidak dapat dibedakan dari state tersimpan (keduanya
menghasilkan AMBIGUOUS tanpa request id). Recovery via webhook bersifat kondisional: butuh
callback asli dari provider yang membawa `reference_id` yang dapat dipakai dan lolos verifikasi
Status API. Bila tidak ada webhook, TIDAK ada prosedur release otomatis yang disetujui.

### Q3 — Kandidat reconciliation (semantik persis source)

```sql
-- Q3: reconciliation candidates (exact mirror of PaymentRepository.findXenditReconciliationCandidates)
SELECT id
FROM payments
WHERE provider = 'XENDIT'
  AND method = 'QRIS'
  AND status = 'PENDING'
  AND charge_state IN ('ACCEPTED', 'AMBIGUOUS')
  AND provider_request_id IS NOT NULL
  AND charge_started_at IS NOT NULL
  AND charge_started_at < now() - interval '1 hour'
ORDER BY charge_started_at ASC, id ASC
LIMIT 50;
```

Jangan disederhanakan atau diperluas. Scheduler tidak jalan kecuali `enabled=true`; itu
keputusan aktivasi operasional terpisah, bukan default.

### Q4 — Order dengan reservasi payment aktif (BR-28 / BR-20)

```sql
-- Q4: order totals, active payment reservations (BR-28) and unresolved Xendit rows (BR-20)
SELECT o.id AS order_id,
       o.venue_id,
       o.status AS order_status,
       o.total_due,
       o.paid_total,
       COALESCE(a.active_total, 0) AS active_reservation_total,
       o.total_due - COALESCE(a.active_total, 0) AS outstanding_amount,
       COALESCE(x.unresolved_xendit, '') AS unresolved_xendit_payments
FROM orders o
LEFT JOIN (
    SELECT order_id, SUM(amount) AS active_total
    FROM payments
    WHERE status IN ('PENDING', 'PAID')
    GROUP BY order_id
) a ON a.order_id = o.id
LEFT JOIN (
    SELECT order_id, string_agg(id::text || ':' || charge_state, ', ' ORDER BY id) AS unresolved_xendit
    FROM payments
    WHERE provider = 'XENDIT'
      AND method = 'QRIS'
      AND status = 'PENDING'
    GROUP BY order_id
) x ON x.order_id = o.id
ORDER BY o.id ASC;
```

`active_reservation_total` memakai predikat persis `sumActiveByOrder()` (`PaymentRepository.java:60-67`) — jangan mengartikan
"reservasi aktif" secara berbeda. Baris PENDING Xendit yang belum tuntas (Q1/Q2) ikut
memasukkan `active_reservation_total`: itu yang membuat sisa balance berkurang (BR-28) dan
`existsActiveByOrder` true sehingga BR-20 memblokir VOID. `unresolved_xendit_payments`
berformat `<payment_id>:<charge_state>` (diurutkan berdasarkan payment ID).

### Q5 — Idempotency key in-flight

```sql
-- Q5: in-flight idempotency keys (response_status IS NULL, threshold = now - min_age)
SELECT key, scope, user_id, request_hash, created_at
FROM idempotency_keys
WHERE response_status IS NULL
  AND created_at < now() - interval '1 hour'
ORDER BY created_at ASC;
```

Kolom `created_at` ada (V4), jadi diagnostik usia valid. **Tidak ada** job cleanup yang
berjalan; komentar 24-hour cleanup eligibility (IdempotencyRepository.java:15-17) hanya
dokumentasi deferral. Key in-flight bisa tetap unresolved tanpa batas waktu sampai mekanisme
recovery yang disetujui ada (M5-B: CAS-fence dan kegagalan TX2 internal meninggalkan key
in-flight permanen).

### Q6 — Kandidat queryable untuk verifikasi provider (CANCELED)

```sql
-- Q6: queryable PENDING Xendit candidates for read-only provider Status API verification
-- Note: Provider CANCELED is only visible through the provider Status API, not through this query.
SELECT id, provider_request_id, charge_started_at, charge_state
FROM payments
WHERE provider = 'XENDIT'
  AND method = 'QRIS'
  AND status = 'PENDING'
  AND provider_request_id IS NOT NULL
ORDER BY charge_started_at ASC NULLS LAST, id ASC;
```

PENTING: `CANCELED` adalah hasil dari Status API provider, bukan state yang bisa disimpulkan
dari baris PENDING lokal. Query ini hanya menyediakan daftar kandidat yang bisa diverifikasi
read-only ke provider. Kebijakan reconciliation saat ini memperlakukan provider CANCELED
sebagai no-op dengan warning tersanitasi (satu per siklus, PaymentReconciliationService.java:114-118):
payment lokal tetap PENDING, reservasi tetap terpegang, BR-20 dapat terus memblokir VOID,
dan tidak ada prosedur release lokal yang disetujui. Jangan merekomendasikan mutasi manual
ke CANCELLED.

### Batas recovery (wajib dibaca operator)

- Baris dengan `provider_request_id`: dapat dipulihkan oleh webhook terautentikasi dengan
  verifikasi Status API independen, atau oleh reconciliation bila diaktifkan dan memenuhi
  kriteria kandidat. Reconciliation tidak aktif secara default — itu keputusan aktivasi terpisah.
- Baris tanpa `provider_request_id`: tidak bisa diambil lewat query GET-by-request-id yang
  diimplementasikan. Recovery webhook bersifat kondisional (provider harus benar-benar membuat
  request dan mengirim callback dengan `reference_id` yang dapat dipakai) dan TIDAK dijamin.
- Kegagalan TX2 setelah provider konfirmasi: TX2 dapat rollback sehingga baris kembali
  SENDING/PENDING tanpa `provider_request_id` dan tanpa `qr_payload`; klien bisa jadi tidak
  pernah menerima QR. Meski recovery via callback secara teoretis mungkin, TIDAK ada jalur
  pembayaran pelanggan yang terjamin ketika QR tidak pernah diterima. Jangan disebut reliably
  recoverable.
- Fencing 409: klien bisa menerima 409 PAYMENT_STATE_CONFLICT padahal webhook sudah men-settle;
  key yang sama bisa terus mengembalikan 409 PAYMENT_IN_PROGRESS sampai ada mekanisme
  cleanup/recovery yang disetujui. Operator wajib memeriksa baris payment aktual sebelum
  menyarankan retry, dan TIDAK boleh menyuruh klien membuat Idempotency-Key baru sebelum
  status payment diperiksa.
- Respons provider 401/403 saat ini diklasifikasikan AMBIGUOUS. Apakah penolakan autentikasi
  membuktikan tidak ada request dibuat masih UNVERIFIED, menunggu investigasi sandbox yang
  disetujui. Jangan reklasifikasi di tugas ini.

### Operasi yang dilarang (semua kategori di atas)

- UPDATE langsung ke `payments.status` atau `payments.charge_state`.
- Mengisi `provider_request_id` atau `qr_payload` secara manual.
- Menandai payment PAID, FAILED, EXPIRED, atau CANCELLED secara manual.
- Me-release order/meja dengan melewati BR-20.
- Settlement manual di luar `XenditWebhookService` / `PaymentReconciliationService`.

UPDATE manual ke PAID dapat membuat `orders.paid_total`, status order, rekaman audit
(`PAYMENT_SETTLED`), dan release meja tidak konsisten. Untuk baris yang belum tuntas, aksi
yang disetujui saat ini adalah eskalasi ke owner/PM untuk investigasi; tidak ada prosedur
release otomatis yang disetujui.

---

## Changelog v2.1 (semantic audit PM)

| # | Perubahan | Lokasi |
|---|---|---|
| 1 | Deposit botol exactly-once (UQ, Idempotency-Key, quantity = 1) | DATABASE, APPLICATION_ARCHITECTURE, UML, PRD BR-29, FR-BTL-01/07 |
| 2 | Role tidak hierarkis; hapus pewarisan | UML, PRD |
| 3 | Invarian konkurensi payment dan pola TX1/Midtrans/TX2 | ADR-005, APPLICATION_ARCHITECTURE 7.3, PRD BR-28, FR-PAY-09 |
| 4 | Semantik `payment_events` (ON CONFLICT DO NOTHING, hapus `IGNORED_DUPLICATE`) | ADR-006, DATABASE, PRD FR-PAY-04 |
| 5 | `CORE_PORTFOLIO_READY` vs `FULL_NIGHTVENUE_READY` | PRD bagian 14, ADR-017 |
| 6 | Urutan Stock-at-Order: order item sebelum ledger | ADR-003, APPLICATION_ARCHITECTURE 7.2, UML 4.1 |
| 7 | Semua CASH butuh shift pemrosesnya; reviewer bukan pemilik | PRD BR-22/BR-30, ADR-002 |
| 8 | Rumus konsumsi bersih forecasting | ADR-015, APPLICATION_ARCHITECTURE 7.7 |
| 9 | `DESIGN_SYSTEM.md` = gerbang implementasi frontend, bukan backend | PRD, PHASES, AI_PERAN |
| 10 | Daftar konsep understanding gate eksplisit | AI_PERAN bagian 2 |
| 11 | Semua path publik memakai `/api/v1/*` | APPLICATION_ARCHITECTURE, UML |
| 12 | Referensi PRD ke AI_PERAN diperbaiki (bagian 2) | PRD bagian 14 |
| OD-11 | Keterbatasan minimum spend dan batas Month-1 | PRD |

Catatan: butir 7 (reviewer bukan pemilik shift, BR-30) dan aturan urutan lock global adalah tambahan Claude dari konsekuensi langsung delta; PM perlu mengonfirmasi.

## Changelog v2.2 (PM final sync)

| # | Perubahan | Lokasi |
|---|---|---|
| 1 | PM approves M3-A, V9 and schema tests; M3-B implementation remains out of scope | ADR-022, PRD, APPLICATION_ARCHITECTURE |
| 2 | ADR-005/006 tetap berlaku untuk keputusan domain; hanya klausul provider-specific yang partially superseded | DECISIONS |
| 3 | Ambiguous Create does not retry; M4 may settle `AMBIGUOUS` to `PAID` only after authenticated, correlated evidence | ADR-005, ADR-022, DATABASE, PRD |
| 4 | V9 adds `AMBIGUOUS`, nullable request ID and Xendit reference ID; V1–V8 unchanged | ADR-022, DATABASE, PHASES |
| 5 | M4 correlation fields, callback token validation and duplicate protection are binding; Xendit `payment_id` mapping remains open | PHASES, APPLICATION_ARCHITECTURE, UML, PRD |
| 6 | Snap.js dihapus dari rencana Xendit; QR aman dari backend, polling GET NightVenue, tanpa interval atau bentuk payload asumsi | FRONTEND_SCOPE.md, DESIGN_SYSTEM.md |

Approved commit order: M3-A only; V9 plus `PaymentSchemaMigrationTests`; then documentation and `.env.example`. Full regression must pass before commits. Do not stage unrelated files or push to main.
