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
**FR**: AUTH-01..05, BD-01..03.
**DoD**
- [ ] Login, refresh dengan rotasi, reuse terdeteksi → semua sesi dicabut
- [ ] Refresh token httpOnly; access token hanya di memori FE
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
- [ ] Idempotency-Key pada tambah item dan VOID; add-item mengunci baris order dan `order_item` dibuat sebelum ledger
- [ ] VOID: RESTORED vs WASTED, ledger-based, exactly-once (UQ + conditional update), ditolak bila ada payment PENDING/PAID
- [ ] Ambang approval Manager dan batasan WASTED diuji
- [ ] Owner dapat menjelaskan alur potong-stok dan kompensasi

### Fase 6 — Pembayaran
**FR**: PAY-01..08. **Kritis.**
**DoD**
- [ ] CASH, CARD_EDC, QRIS Sandbox; Order 1:N Payment; split nominal
- [ ] Pembuatan payment diserialkan lewat lock baris order; uji konkurensi: dua payment paralel (key berbeda) tidak melampaui tagihan (FR-PAY-09)
- [ ] QRIS memakai pola TX1 → panggilan Midtrans di luar lock → TX2; job penyapu `PENDING` tanpa referensi provider
- [ ] Webhook duplikat memakai `ON CONFLICT DO NOTHING`; satu record `payment_events` per `event_key`
- [ ] Signature SHA-512 divalidasi; Status API dikonfirmasi; `payment_events` UQ
- [ ] **Bukti B**: webhook dikirim berulang/paralel → transisi payment/order sekali, inventory tidak berubah
- [ ] Out-of-order → `IGNORED_STALE`; settlement pada order VOIDED → tercatat + audit anomali
- [ ] Minimum spend ditegakkan sebelum PAID
- [ ] Tidak ada panggilan HTTP eksternal di dalam transaksi berlock

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
