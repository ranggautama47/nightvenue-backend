# DECISIONS — Architecture Decision Records NightVenue

Satu-satunya tempat ADR. `APPLICATION_ARCHITECTURE.md` hanya menunjuk ke sini.

**Aturan membuka ulang keputusan** (dari PM sync): hanya untuk (1) celah keamanan, (2) risiko korupsi data, (3) batasan deployment yang mustahil, (4) kegagalan performa yang *terukur*, (5) requirement yang saling bertentangan. Alasan seperti "framework lain lebih keren" tidak valid. ADR baru diajukan tertulis ke PM sebelum diimplementasikan.

| ADR | Judul | Status |
|---|---|---|
| 001 | Business Day Cutoff | Accepted |
| 002 | Cashier Shift dan Batas Business Day | Accepted |
| 003 | Stock-at-Order + atomic conditional UPDATE | Accepted |
| 004 | VOID sebagai kompensasi (disposition + exactly-once) | Accepted |
| 005 | Model pembayaran: Order 1:N Payment | Accepted |
| 006 | Webhook: signature vs idempotency | Accepted |
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
`order_items` di-INSERT lebih dulu dalam transaksi yang sama sehingga `order_item_id` ada untuk `reference_id` ledger; kegagalan stok me-rollback seluruh transaksi termasuk order item. Baris order dikunci (`FOR UPDATE`) di awal. Pasangan (item, bahan) diurutkan menurut `ingredient_id` menaik agar satu ledger row tetap menunjuk satu item. Urutan lock global: `orders` → `payments` → `inventory_items` (ID menaik).

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
3. VOID ditolak (409) jika order punya payment `PENDING` atau `PAID` (BR-20). Order yang sudah dibayar tidak dapat di-VOID; koreksi = refund (deferred).
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
Accepted.

### Decision
- Metode kanonik: `CASH`, `CARD_EDC`, `QRIS_MIDTRANS`. Hanya QRIS yang memakai webhook.
- Status payment: `PENDING, PAID, FAILED, EXPIRED, CANCELLED, REFUNDED`. `REFUNDED` dicadangkan untuk fitur refund yang deferred.
- **Order → Payment adalah 1:N** secara permanen. Order `PAID` saat `SUM(PAID) >= total_due` dan syarat minimum spend terpenuhi. Pembayaran dengan nominal (split by amount) didukung backend sejak Month-1; UI split-bill dan `payment_allocations` (split per item) deferred.
- `amount` payment ≤ sisa tagihan saat dibuat (422 bila melebihi). Kembalian tunai adalah urusan UI.
- Settlement yang datang untuk order `VOIDED` tetap dicatat (`payments.status = PAID`), order tidak berubah, dan `audit_logs` menerima aksi `PAYMENT_ON_CLOSED_ORDER` untuk ditangani manual (BR-21).
- Midtrans di balik interface `PaymentGateway` (satu interface, dua implementasi: Midtrans dan fake untuk test).

### Consequences
Skema tidak perlu diubah ketika split-bill UI dibangun.

### Addendum v2.1 (ADR-005): konkurensi payment
- `amount ≤ total_due − SUM(PAID) − SUM(PENDING)`; PENDING menahan sisa.
- Pembuatan payment diserialkan lewat `SELECT ... FOR UPDATE` pada baris order. `Idempotency-Key` tidak menggantikan ini (hanya melindungi request identik).
- QRIS dibagi tiga langkah: TX1 (lock, validasi, INSERT PENDING, commit), panggilan Midtrans di luar lock, TX2 (simpan hasil atau `FAILED`). Payment PENDING tanpa referensi provider disapu menjadi `EXPIRED` oleh job.

---

## ADR-006 — Webhook: signature vs idempotency

### Status
Accepted.

### Decision
Dua mekanisme menyelesaikan dua masalah berbeda dan keduanya wajib:

| Mekanisme | Masalah yang diselesaikan | Tidak menyelesaikan |
|---|---|---|
| Verifikasi `signature_key` (SHA-512 dari `order_id + status_code + gross_amount + serverKey`) | Pengirim palsu / payload dimodifikasi | Pengiriman ulang notifikasi yang sah |
| Unique `(provider, event_key)` di `payment_events` | Notifikasi sah yang terkirim berkali-kali | Pengirim palsu |

Tambahan: konfirmasi lewat Midtrans Status API sebelum transisi, dan state machine yang menolak transisi mundur (`settlement` lalu `pending` → `IGNORED_STALE`).

`event_key = transaction_id + ':' + transaction_status + ':' + status_code`.

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

### Addendum v2.1 (ADR-009): Refresh token rotation dan reuse policy

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
Phase 1 menjalankan spike ke 1–2 kandidat dan mencatat RAM, cold start, biaya, dukungan Spring + FastAPI + PostgreSQL + Redis eksternal, secret, health check, outbound ke Midtrans dan provider LLM. Hasil menggantikan status ADR ini. Frontend: Vercel kecuali ada blocker nyata. Demo publik wajib untuk portfolio; fallback bila hosting gagal: video demo + panduan menjalankan lokal.

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
