# NightVenue — PRD v2.1 (Backend Architecture Frozen Candidate)

| Item | Isi |
|---|---|
| Status | v2.1: v2.0 + 12 delta hasil semantic audit PM dan OD-11. Menunggu persetujuan PM (`BACKEND_READY_FOR_PHASE_1`) |
| Tanggal | 2026-10-24 (v2.0: 2026-10-23) |
| Owner | Rangga (keputusan akhir dan integrator) |
| PM | GPT |
| Posisi | **NightVenue — Intelligent Nightlife Venue Operations Platform** (produk fiktif) |
| Dokumen turunan | `DECISIONS.md`, `CONTEXT.md`, `AI_PERAN.md`, `PHASES.md`, `APPLICATION_ARCHITECTURE.md`, `DATABASE.md`, `UML.md` |
| `DESIGN_SYSTEM.md` | **Tidak ditulis Claude** (instruksi Owner). Gerbang **implementasi frontend**, bukan gerbang backend: ditulis DeepSeek, direview Gemini, disetujui PM (OD-8). Backend Phase 1 tidak menunggunya |
| Revisi v2.1 | Daftar delta ada di `DECISIONS.md` bagian Changelog v2.1 |

Urutan otoritas bila dokumen bertentangan: PRD untuk perilaku produk, `DATABASE.md` untuk skema, `APPLICATION_ARCHITECTURE.md` untuk teknis, `DECISIONS.md` untuk alasan. Konflik dilaporkan ke PM, bukan diselesaikan sendiri oleh agent.

Perubahan dari v1.0: chatbot hybrid (bukan FAQ default), Business Day kanonik, shift reconciliation, VOID, QR redemption, reservasi dan fitur lain dipindah ke deferred, fase disusun ulang menjadi 0–11, bukti kinerja wajib.

---

## 1. Hasil Audit

### 1.1 Dari audit PRD GPT dan DeepSeek (v1.0)

| ID | Temuan | Status v2.0 |
|---|---|---|
| A1 | Stok dipotong setelah webhook paid → oversell | Berlaku: Stock-at-Order (ADR-003) |
| A2 | Hanya Midtrans | Berlaku: `CASH`, `CARD_EDC`, `QRIS_MIDTRANS` |
| A3 | Optimistic lock untuk baris stok panas | Berlaku: conditional UPDATE + urutan ID |
| A4 | BYOK untuk pelanggan nyata tak realistis; usulan FAQ default | **Diganti**: audiens adalah recruiter, chat hybrid (ADR-011). Asumsi v1.0 salah untuk konteks portfolio |
| A5 | ML tanpa data dan tanpa baseline | Berlaku: data sintetis berlabel + baseline (ADR-015) |
| A6 | Tidak ada seed/demo data | Berlaku: seeder idempotent (Phase 2) |
| A7 | Customer vs user | Berlaku (ADR-010) |
| A8 | Verifikasi usia botol; ID tidak disimpan | Berlaku |
| A9 | Hari operasional | **Dipertegas**: `business_day_cutoff_hour` + `BusinessDateService` (ADR-001) |
| A10 | Pajak/service/basis minimum spend | Berlaku: basis = subtotal setelah diskon, sebelum pajak dan service |
| A11 | Double-tap | Berlaku: `Idempotency-Key` |
| A12 | Webhook: signature + Status API + event unik | Berlaku (ADR-006) |
| A13 | Cookie lintas domain | Berlaku (ADR-009) |
| A14 | Drift kontrak API | Berlaku: tipe digenerate dari OpenAPI |
| A15 | Terlalu banyak fase untuk satu developer | **Diubah**: Tier 1 dan Tier 2 jadi target Month-1; urutan potong ditetapkan (ADR-017) |
| A16 | Hosting demo belum diputuskan | Berlaku: spike Phase 1 (ADR-019) |
| A17 | Versi framework usang | Berlaku (ADR-018) |
| A18 | Tidak boleh self-QA | Berlaku + human review gate |
| A19 | Audit log dan soft delete | Berlaku |
| A20 | Merek nyata | Berlaku: produk fiktif |
| A21 | Design system untuk dua agent FE | Berlaku, tetapi **tidak ditulis Claude** |
| A22 | Jumlah container | Compose backend = 4 (`api`, `ml-service`, `postgres`, `redis`) |

### 1.2 Temuan sync (Claude terhadap dokumen PM sync)

Semua ditangani di dokumen ini; yang butuh keputusan Owner ada di bagian 13.

| ID | Temuan | Tindakan |
|---|---|---|
| S1 | VOID "restore recipe ingredients" bertentangan dengan alasan Stock-at-Order (minuman sudah dibuat) | `disposition` RESTORED/WASTED, pengembalian dari ledger (ADR-004) |
| S2 | ADR-001/002 bentrok dengan penomoran ADR lama | Semua ADR dipindah ke `DECISIONS.md`, dinomori ulang |
| S3 | Shift melewati cutoff: kas per tanggal akan salah attribusi | Kas per `payments.shift_id`; cek cutoff di jalur request (ADR-002) |
| S4 | Loyalty, transfer stok, password recovery, refund, laporan tidak ada di daftar scope maupun deferred PM | Default: **deferred**. Butuh konfirmasi (OD-7) |
| S5 | Bukti B meminta "loyalty mutation once" dan "inventory side effect"; loyalty deferred dan webhook tidak menyentuh inventory | Bukti B diubah: transisi payment/order sekali, inventory tidak berubah |
| S6 | VOID order saat QRIS `PENDING` → uang masuk untuk order batal | BR-20, BR-21 |
| S7 | Rencana 4 minggu memuat semuanya + human gate di PR kritis | Urutan potong ditetapkan; jadwal = target, bukan komitmen |
| S8 | PM mewajibkan `DESIGN_SYSTEM.md` oleh Claude; Owner sebelumnya melarang | Tidak dibuat; konflik dilaporkan |
| S9 | Intent `RESERVATION`/`TABLE_AVAILABILITY` padahal reservasi deferred | Jawaban kebijakan dan status meja saat ini (ADR-011) |
| S10 | `business_day_cutoff` sebelumnya `TIME` | Kanonik: `business_day_cutoff_hour SMALLINT` |
| S11 | "QR / ownership redemption" tidak didefinisikan | ADR-020 + keterbatasan jujur |
| S12 | Bukti D butuh data rute chat yang belum ada di skema | Tabel `chat_route_logs` |
| S13 | Phase 2 meminta histori 90 hari, tapi tabel order baru ada di Phase 5 | Phase 2 menyemai **ledger konsumsi** saja; histori order opsional |
| S14 | Alasan nightlife shift menyebut tip, cover charge, bottle service yang tidak dimodelkan | Month-1 hanya rekonsiliasi kas; README tidak boleh mengklaim lebih |

---

## 2. Definisi Produk

NightVenue adalah studi kasus portfolio berfungsi untuk operasi venue nightlife: POS, inventory resep, botol titipan, rekonsiliasi shift kasir, ditambah asisten percakapan hybrid. Terinspirasi pola operasional venue sejenis, tetapi bukan clone atau aplikasi resmi perusahaan mana pun.

Pembeda (alasannya operasional, bukan teknologi):

| Pembeda | Masalah nyata |
|---|---|
| Business Day Cutoff | Malam kerja melewati tengah malam; laporan pukul 02:00 milik malam sebelumnya |
| Stock-at-Order | Minuman dibuat sebelum tagihan dibayar |
| Bottle Keeping | Tamu VIP menitip botol; ada expiry dan verifikasi pemilik |
| Shift Reconciliation | Banyak kas, serah terima kasir, shift lewat tengah malam |
| Hybrid chat (ML + DB + LLM) | Pertanyaan berulang cepat tanpa biaya LLM; pertanyaan kompleks eskalasi |
| Forecasting stok | Belanja bahan untuk bulan depan |

Prinsip: *Stack is not the differentiator. Engineering decisions are.* dan *Complexity must be earned.*

## 3. Tujuan, Positioning, Non-Goals

**Tujuan**: membuktikan pemilik proyek dapat memahami dan menjelaskan Java Spring Boot; membangun logika transaksional yang benar; menangani konkurensi; mendesain payment idempotent; memakai PostgreSQL dan Redis dengan tepat; mengintegrasikan Spring dengan FastAPI; menerapkan ML terapan dan LLM yang sadar biaya; CI/CD; test otomatis; load/concurrency test; deployment publik; dan memakai alur kerja agent AI tanpa kehilangan kendali arsitektur.

**Positioning**: Strong Junior → Mid-Level. Tidak boleh mengklaim "senior-level production system". Docker Compose bukan Kubernetes.

**Bukan**: CRUD bootcamp, prototype hackathon, kumpulan teknologi tren, POS restoran biasa dengan nama diganti.

**Non-goals**: Kubernetes, Kafka, RabbitMQ, Elasticsearch, event sourcing, CQRS, GraphQL, service mesh, banyak microservice Java, blockchain, voice ordering, rekomendasi AI palsu, WebSocket tanpa kebutuhan nyata, payment gateway selain Midtrans Sandbox, aplikasi mobile native, sistem pajak sesuai regulasi.

---

## 4. Aktor dan Hak Akses (Month-1)

Role: `ADMIN`, `MANAGER`, `CASHIER`, `CUSTOMER`. Otorisasi di backend; cek role di frontend hanya UX. `MANAGER` dan `CASHIER` dibatasi venue penugasan (`user_venues`); `ADMIN` lintas venue. `CUSTOMER` pada Month-1 hanya: melihat botol sendiri dan memakai chat. **Role tidak hierarkis**: otorisasi mengikuti matriks eksplisit di bawah, tanpa pewarisan hak.

| Sumber daya / aksi | ADMIN | MANAGER | CASHIER | CUSTOMER |
|---|:-:|:-:|:-:|:-:|
| Kelola user dan penugasan venue | ✅ | — | — | — |
| Konfigurasi venue (timezone, cutoff, pajak, ambang void) | ✅ | — | — | — |
| Konfigurasi provider chat dan batas | ✅ | — | — | — |
| CRUD produk, bahan, recipe | ✅ | ✅ | — | — |
| Lihat inventory | ✅ | ✅ | ✅ (baca) | — |
| Penyesuaian stok / waste manual | ✅ | ✅ | — | — |
| Kelola meja dan minimum spend | ✅ | ✅ | — | — |
| Buka order, tambah item, tandai disajikan | ✅ | ✅ | ✅ | — |
| VOID di bawah ambang, disposition RESTORED | ✅ | ✅ | ✅ (alasan wajib) | — |
| VOID di atas ambang, atau disposition WASTED | ✅ | ✅ | — | — |
| Terima pembayaran | ✅ (CASH: shift OPEN milik sendiri) | ✅ (CASH: shift OPEN milik sendiri) | ✅ (CASH: shift OPEN milik sendiri) | — |
| Buka dan submit shift sendiri (wajib bila menerima CASH) | ✅ | ✅ | ✅ | — |
| Review dan rekonsiliasi shift (bukan shift sendiri) | ✅ | ✅ | — | — |
| Titip botol, redeem botol | ✅ | ✅ | ✅ | — |
| Lihat botol milik sendiri | — | — | — | ✅ |
| Jalankan dan lihat forecasting | ✅ | ✅ | — | — |
| Chat | — | — | — | ✅ (dan anonim, rate-limited) |
| Lihat audit log | ✅ | ✅ (venue sendiri) | — | — |

---

## 5. Scope

### 5.1 Tier 1 — Nightlife Transaction Core (wajib)
Autentikasi, RBAC, konfigurasi venue, Business Day Cutoff, produk, bahan, recipe, inventory, Stock-at-Order, pemotongan stok atomic, siklus order, CASH, CARD_EDC, QRIS Midtrans Sandbox, state machine payment, verifikasi dan idempotensi webhook, minimum spend, Bottle Keeping (deposit, redemption, expiry), VOID dan pengembalian stok transaksional, Shift Reconciliation + review manager, CI/CD, integration test, deployment publik.

### 5.2 Tier 2 — AI/ML Differentiator (target Month-1)
FastAPI ML service, intent classifier, chat lokal berbasis database, confidence threshold, fallback Spring deterministik, BYOK Gemini/Groq, rate limiting Redis, guardrail AI, evaluasi classifier, forecasting inventory + baseline, analisis biaya dan latensi.

### 5.3 Deferred
Tidak boleh menghambat Tier 1/2 dan tidak boleh dimasukkan diam-diam:

| Dari PM | Tambahan default Claude (OD-7) |
|---|---|
| Reservasi, recipe versioning, last call, void anomaly detection, reaktivasi botol email, simulasi what-if | Loyalty, transfer stok gudang→bar, password recovery, refund, laporan penjualan, split-bill per item, VOID per item, penerbitan ulang QR token |

Skema untuk fitur deferred sudah dirancang di `DATABASE.md` (bagian "Deferred") tetapi **tabelnya tidak dibuat** di Month-1.

### 5.4 Rencana (target, bukan komitmen)

| Minggu | Fase | Gerbang |
|---|---|---|
| 1 | 0–5: fondasi, CI, spike hosting, DB, Business Day, auth, inventory, order + stok teratomisasi | Kasir: login → buat order → stok terpotong → transaksi tersimpan, dengan integration test |
| 2 | 6–8: payment, VOID, bottle keeping, shift | Bukti A dan B dapat dijalankan |
| 3 | 9–10: ML, chat hybrid, BYOK | Evaluasi classifier ada |
| 4 | 11: bukti, hardening, deploy, README, video | Semua metrik berasal dari pengukuran |

**Urutan potong** (ADR-017): Tier 1 tidak dipotong; Tier 2 dipotong dari belakang. Fase maju lebih cepat bila fase sebelumnya sudah lolos QA dan understanding gate. Kecepatan agent menulis bukan bottleneck; bottleneck adalah review dan pemahaman Owner pada Phase 4–8 dan 10.

---

## 6. Aturan Bisnis

| ID | Aturan |
|---|---|
| BR-01 | Uang `BIGINT` rupiah; kuantitas `NUMERIC(14,3)`/`BigDecimal`; dilarang `double`/`float` |
| BR-02 | **Stock-at-Order**: stok dipotong saat item diterima (status `ORDERED`) dalam transaksi yang sama dengan insert item |
| BR-03 | Stok tidak boleh negatif (`CHECK` + conditional UPDATE); kurang → 409 dan seluruh transaksi rollback |
| BR-04 | Setiap perubahan stok menulis `inventory_transactions` dengan `balance_after` dan `business_date` |
| BR-05 | Order `PAID` hanya bila `SUM(payments PAID) >= total_due` **dan** `subtotal setelah diskon >= minimum_spend_snapshot` (sebelum pajak dan service) |
| BR-06 | Satu meja hanya boleh punya satu order `OPEN`/`PARTIALLY_PAID` |
| BR-07 | `PAID` final pada Month-1; koreksi lewat refund (deferred) |
| BR-08 | `business_date` dihitung sekali oleh `BusinessDateService` saat record dibuat dan tidak dihitung ulang (ADR-001) |
| BR-09 | Vault: `0 ≤ remaining_volume_ml ≤ original_volume_ml`; redeem hanya bila `ACTIVE` dan belum kedaluwarsa |
| BR-10 | Deposit botol miras mensyaratkan `customers.age_verified = true`; nomor identitas tidak disimpan |
| BR-11 | Redeem mengurangi `bottle_vaults`, **bukan** inventory (bahan sudah terpotong saat botol dibeli); item redemption boleh berharga 0 |
| BR-12 | Deposit hanya dari item produk `is_bottle_keepable` pada order berstatus `PAID` |
| BR-13 | Webhook dengan event key sama diproses paling banyak sekali; efek samping tidak berulang |
| BR-14 | Transisi status hanya sesuai state machine di `UML.md`; ilegal → 409 dan dicatat |
| BR-15 | Semua query staf difilter `venue_id` dari penugasan, diverifikasi di service layer |
| BR-16 | VOID di atas `void_manager_approval_threshold` oleh CASHIER ditolak; harus dieksekusi Manager/Admin (nilai seed hanya contoh) |
| BR-17 | LLM bukan sumber kebenaran; data meja, jam, promo diambil dari DB oleh backend |
| BR-18 | API key BYOK tidak pernah disimpan atau dicatat; teks UI harus menyatakan key transit lewat server |
| BR-19 | **VOID kompensasi sekali**: pengembalian dari ledger; `disposition` RESTORED/WASTED; item `SERVED` selalu WASTED (ADR-004) |
| BR-20 | VOID ditolak bila ada payment `PENDING` atau `PAID` pada order |
| BR-21 | Settlement untuk order `VOIDED` dicatat sebagai payment `PAID` dan anomali di `audit_logs`; order tidak berubah |
| BR-22 | **Setiap** payment CASH, oleh role apa pun (termasuk Manager/Admin yang bertindak sebagai kasir), mensyaratkan shift `OPEN` milik pemrosesnya dengan `business_date` = business date sekarang; satu shift `OPEN` per user; melewati cutoff → `MANAGER_REVIEW` |
| BR-23 | `expected_cash = opening_float + SUM(CASH PAID dengan shift_id)`; `variance = actual − expected`; catatan review wajib bila variance ≠ 0 atau shift dari jalur cutoff |
| BR-24 | Routing chat: `OUT_OF_SCOPE` tidak memanggil LLM; confidence di bawah θ → eskalasi/`ADVANCED_AI_REQUIRED`; fallback Spring maksimal 5 kategori |
| BR-25 | Redeem mensyaratkan QR token valid + `order.customer_id` = pemilik vault + `ownerConfirmed` (ADR-020) |
| BR-26 | Penjualan memotong satu lokasi `BAR` per venue (multi-bar deferred) |
| BR-27 | Tidak ada metrik di README tanpa pengukuran NightVenue sendiri (ADR-016) |
| BR-28 | Pembuatan payment diserialkan terhadap baris order; `amount ≤ total_due − SUM(PAID) − SUM(PENDING)` (PENDING menahan sisa); panggilan Midtrans di luar lock (ADR-005) |
| BR-29 | Satu botol fisik = satu order item `quantity = 1`; `bottle_vaults.source_order_item_id` UNIQUE; deposit memakai `Idempotency-Key` |
| BR-30 | Review shift tidak boleh dilakukan oleh pemilik shift itu sendiri |

---

## 7. Requirement Fungsional

Acceptance dalam bentuk ringkas Given/When/Then. Semua FR berikut adalah Month-1.

### FR-AUTH (Tier 1)
| ID | Requirement | Acceptance |
|---|---|---|
| FR-AUTH-01 | Login email + password (BCrypt) | Salah password atau email → 401 identik |
| FR-AUTH-02 | JWT access pendek + refresh dengan rotasi | Refresh token yang sudah dipakai dipakai lagi → seluruh sesi dicabut |
| FR-AUTH-03 | RBAC + venue scoping | CASHIER venue A mengakses order venue B → 403/404 |
| FR-AUTH-04 | Logout mencabut refresh token | Token lama tidak dapat refresh |
| FR-AUTH-05 | Rate limit `/auth/*` | Melebihi batas → 429 |

### FR-BD Business Day (Tier 1)
| ID | Requirement | Acceptance |
|---|---|---|
| FR-BD-01 | `BusinessDateService` satu-satunya penghitung | Tabel contoh ADR-001 lulus sebagai test, termasuk 05:59:59 vs 06:00:00 dan timezone venue |
| FR-BD-02 | `business_date` tersimpan pada `orders`, `payments`, `cashier_shifts`, `inventory_transactions` | Order 02:30 tanggal 24 → `business_date` tanggal 23 |
| FR-BD-03 | Perubahan cutoff venue tidak mengubah record lama | Test regresi |

### FR-CAT Katalog, Recipe, Inventory (Tier 1)
| ID | Requirement | Acceptance |
|---|---|---|
| FR-CAT-01 | CRUD produk, kategori, bahan (soft delete) | Produk nonaktif tidak muncul di POS tetapi tetap pada order lama |
| FR-CAT-02 | Recipe: produk → bahan + kuantitas + unit | Recipe tanpa item ditolak |
| FR-CAT-03 | Inventory per lokasi | Unik `(location, ingredient)` |
| FR-CAT-04 | Pengurangan stok atomic + ledger | 50 thread memesan stok yang cukup untuk 10 → tepat 10 berhasil, 40 gagal 409, stok akhir ≥ 0 |
| FR-CAT-05 | Penyesuaian manual / waste / pembelian masuk dengan alasan | Tanpa alasan → 422; tercatat di `audit_logs` |

### FR-POS Order (Tier 1)
| ID | Requirement | Acceptance |
|---|---|---|
| FR-POS-01 | Buka order (meja opsional, customer opsional); snapshot minimum spend saat meja dipilih | Meja sudah punya order aktif → 409 |
| FR-POS-02 | Tambah item → potong stok sesuai recipe, `Idempotency-Key` | Gagal sebagian → rollback semua; request sama 2× → satu efek |
| FR-POS-03 | Tandai item disajikan | Item `ORDERED → SERVED`; tidak bisa mundur |
| FR-POS-04 | Hitung subtotal, diskon, service, pajak | Pembulatan konsisten, diuji unit |
| FR-POS-05 | Validasi minimum spend saat tutup | Subtotal < minimum → tidak bisa `PAID`, pesan menyebut selisih |
| FR-POS-06 | Order menerima `business_date` | Lihat FR-BD |

### FR-VOID (Tier 1)
| ID | Requirement | Acceptance |
|---|---|---|
| FR-VOID-01 | `POST /api/v1/orders/{id}/void` dengan alasan + disposition | Item `ORDERED`+RESTORED: stok kembali sesuai ledger; item `SERVED`: tidak kembali |
| FR-VOID-02 | Exactly-once | Request VOID kedua → 409; stok tidak berubah (test) |
| FR-VOID-03 | Ambang manager | CASHIER di atas ambang → 403 `MANAGER_APPROVAL_REQUIRED` |
| FR-VOID-04 | Ditolak bila ada payment PENDING/PAID | 409 dengan pesan yang jelas |
| FR-VOID-05 | `void_logs` per item + `audit_logs` | Catatan alasan, pelaku, disposition, nilai |
| FR-VOID-06 | Perubahan recipe setelah order tidak memengaruhi pengembalian | Test: ubah recipe, void, stok kembali sebesar yang dipotong |

### FR-PAY Pembayaran (Tier 1)
| ID | Requirement | Acceptance |
|---|---|---|
| FR-PAY-01 | CASH dan CARD_EDC | Cukup bayar → order `PAID`, `closed_at`, meja `AVAILABLE`; nominal ≤ sisa tagihan |
| FR-PAY-02 | QRIS via Midtrans Sandbox | `provider_order_id` unik; status `PENDING` |
| FR-PAY-03 | Webhook memverifikasi signature + Status API | Signature salah → 401, tidak ada perubahan state |
| FR-PAY-04 | Webhook idempotent | Payload sama 5× → hanya satu record `payment_events` diproses; empat duplikat menjadi no-op (`ON CONFLICT DO NOTHING`) yang teramati lewat log terstruktur |
| FR-PAY-05 | Webhook tidak berurutan | `settlement` lalu `pending` tidak menurunkan status |
| FR-PAY-06 | Payment kedaluwarsa | `EXPIRED` tidak dihitung ke `paid_total` |
| FR-PAY-07 | Beberapa payment per order (split nominal) | 2jt + 1jt + 1jt untuk tagihan 4jt → `PAID` hanya setelah semuanya sukses |
| FR-PAY-08 | Settlement pada order `VOIDED` | Tercatat sebagai anomali, order tetap `VOIDED` |
| FR-PAY-09 | Payment konkuren pada order yang sama | Dua payment 3jt paralel (key berbeda) untuk tagihan 4jt → tepat satu sukses, satu 422; `SUM(PAID)+SUM(PENDING) ≤ total_due` |

### FR-BTL Bottle Keeping (Tier 1)
| ID | Requirement | Acceptance |
|---|---|---|
| FR-BTL-01 | Deposit dari item botol (`quantity = 1`) pada order `PAID`, customer `age_verified`, dengan `Idempotency-Key` | Syarat tak terpenuhi → ditolak; token QR mentah dikembalikan sekali |
| FR-BTL-02 | Redeem (QR token + customer cocok + `ownerConfirmed`) | Token salah/customer berbeda → ditolak |
| FR-BTL-03 | Redeem konkuren | Dua redeem melebihi sisa → satu gagal 409; sisa ≥ 0 |
| FR-BTL-04 | Expiry | Botol kedaluwarsa tidak dapat di-redeem; job harian menandai `EXPIRED`; pengecekan juga di jalur request |
| FR-BTL-05 | Riwayat | Setiap perubahan volume punya baris `bottle_vault_transactions` |
| FR-BTL-06 | Customer melihat botol sendiri | Botol customer lain → 403/404 |
| FR-BTL-07 | Deposit tidak dapat ganda | Item yang sama di-deposit 2× (key berbeda, juga paralel) → satu vault, sisanya 409 `VAULT_ALREADY_EXISTS`; menambah produk botol `quantity > 1` → 422 |

### FR-SHF Shift Reconciliation (Tier 1)
| ID | Requirement | Acceptance |
|---|---|---|
| FR-SHF-01 | Buka shift dengan opening float | Kedua shift OPEN untuk satu cashier → ditolak |
| FR-SHF-02 | Agregasi kas per `shift_id` | `expected_cash` benar terhadap payment CASH shift itu |
| FR-SHF-03 | Submit kas aktual | `variance = actual − expected` |
| FR-SHF-04 | Review manager | Variance ≠ 0 tanpa catatan → 422; shift → `RECONCILED` |
| FR-SHF-05 | Cutoff | Shift OPEN melewati cutoff → `MANAGER_REVIEW` (diuji dengan `Clock` palsu) |
| FR-SHF-06 | Payment pada shift tidak OPEN / business date lama | 409 `SHIFT_NOT_OPEN` |
| FR-SHF-07 | Shift untuk semua penerima CASH; reviewer ≠ pemilik | Manager/Admin menerima CASH tanpa shift OPEN → 409; Manager mereview shift sendiri → 403 |

### FR-ML (Tier 2)
| ID | Requirement | Acceptance |
|---|---|---|
| FR-ML-01 | `POST /ml/chat/classify` → intent, confidence, `requiresLlm` | Kontrak teruji |
| FR-ML-02 | Evaluasi classifier lengkap | Train/val/test, baseline keyword, P/R/F1 per kelas, confusion matrix, keterbatasan (ADR-014) |
| FR-ML-03 | `POST /ml/forecast/inventory` | Data < 28 hari → 422 |
| FR-ML-04 | Model vs baseline | Respons memuat `modelMae`, `baselineMae`, model terpilih |
| FR-ML-05 | Timeout dan outage | Classify 2 dtk, forecast 10 dtk (konfigurasi); FastAPI mati tidak memengaruhi order/payment |
| FR-ML-06 | Hasil forecast tersimpan di `stock_forecasts` | Ada `model_name`, `input_days`, `as_of_business_date` |

### FR-CHAT Hybrid Assistant (Tier 2)
| ID | Requirement | Acceptance |
|---|---|---|
| FR-CHAT-01 | Jawaban lokal dari PostgreSQL tanpa LLM | Pertanyaan jam buka → 0 panggilan LLM, data sama dengan DB |
| FR-CHAT-02 | Confidence threshold | Di bawah θ tidak dijawab lokal |
| FR-CHAT-03 | `OUT_OF_SCOPE` tidak memanggil LLM | Test dengan LLM palsu: nol panggilan |
| FR-CHAT-04 | Eskalasi BYOK (Gemini; Groq via `LlmProvider`) | Konteks hanya data publik venue |
| FR-CHAT-05 | Tanpa key | Respons `ADVANCED_AI_REQUIRED`, bukan error |
| FR-CHAT-06 | Fallback Spring bila FastAPI tidak tersedia | Lima kategori terjawab; selainnya BYOK atau `ADVANCED_AI_REQUIRED` |
| FR-CHAT-07 | Rate limit 10 req/menit/IP | Melebihi → 429 |
| FR-CHAT-08 | Validasi input dan output | Input terlalu panjang → 422; output berisi blok kode atau terlalu panjang → fallback aman |
| FR-CHAT-09 | Key tidak bocor | Test: tidak ada di log, DB, respons |
| FR-CHAT-10 | Metrik rute | Setiap request menulis `chat_route_logs` tanpa isi pesan dan tanpa key |
| FR-CHAT-11 | Teks UI akurat | Menyatakan key dikirim ke backend dan diteruskan ke provider |
| FR-CHAT-12 | Demo recruiter tanpa key | Tombol "Try Demo Chat"; pertanyaan lokal berfungsi tanpa key |

### Deferred (ID dicadangkan)
`FR-LOY` (loyalty), `FR-OPS` (transfer stok, laporan), `FR-RSV` (reservasi), `FR-REC` (password recovery), `FR-PAY-R` (refund), item-level VOID, `payment_allocations`, recipe versioning, last call. Definisi lengkapnya ada di PRD v1.0 untuk referensi; tidak berlaku sampai PM memindahkannya ke scope.

---

## 8. Non-Fungsional

| Area | Requirement |
|---|---|
| Keamanan | BCrypt; JWT ketat; CORS whitelist; validasi DTO; secret dari env; gitleaks di CI; webhook diverifikasi; rate limit `/auth/*`, checkout, chat; `X-Forwarded-For` hanya dari proxy tepercaya; ML hanya jaringan internal + `X-Internal-Token` |
| Observability | Log JSON terstruktur, `X-Request-Id`, `/actuator/health` (liveness + readiness DB/Redis), FastAPI `/health` |
| Performa | Target diukur, bukan dijanjikan (BR-27). Angka = hasil k6/benchmark NightVenue |
| Privasi | Tidak menyimpan nomor identitas; isi chat tidak dipersistensi; `chat_route_logs` tanpa isi pesan |
| Dependensi | Dependabot; versi dipin |
| Dokumentasi | OpenAPI (springdoc), ERD, ADR, README case study |

---

## 9. Testing dan Quality Gate

Backend: JUnit 5, Mockito, Spring Boot Test, Testcontainers (PostgreSQL dan Redis asli, bukan H2). Frontend: Vitest + React Testing Library (keranjang kasir, form pembayaran, pemilihan meja, redeem botol, login, error API). ML: pytest (kontrak, validasi dataset, evaluasi).

**Skenario wajib** (masing-masing satu test otomatis):

1. stok tidak cukup
2. order bersamaan pada stok terbatas (50 thread)
3. webhook duplikat
4. webhook tidak berurutan
5. signature webhook salah
6. pelanggaran minimum spend
7. redeem botol bersamaan
8. botol kedaluwarsa
9. VOID dua kali (exactly-once) dan VOID setelah recipe berubah
10. role tidak berwenang + venue lain
11. transisi status ilegal
12. `Idempotency-Key` ganda
13. batas Business Day (05:59:59 / 06:00:00, timezone)
14. shift melewati cutoff → `MANAGER_REVIEW`, payment ditolak
15. variance shift dan catatan wajib
16. VOID ditolak saat payment PENDING
17. chat: FastAPI mati → fallback
18. chat: key tidak ada di log/DB/respons
19. chat: `OUT_OF_SCOPE` tanpa panggilan LLM
20. FastAPI mati tidak memengaruhi order dan payment
21. payment: dua payment paralel (key berbeda) tidak melampaui tagihan (FR-PAY-09)
22. bottle: deposit paralel untuk order item yang sama → satu vault (FR-BTL-07)
23. shift: CASH oleh Manager tanpa shift OPEN → 409; reviewer = pemilik shift → 403

**Quality gate PR**: build, lint, type check, test, migration valid (`flyway validate` + migrasi dari DB kosong), gitleaks, tidak melanggar batas arsitektur, DoD fase terpenuhi, laporan agent lengkap, **understanding gate untuk PR kritis** (`AI_PERAN.md`). Coverage JaCoCo ≥ 70% pada package service hanya gerbang minimum.

## 10. Bukti Kerja (Proof-of-Work)

Tidak ada angka tanpa pengukuran (ADR-016).

| Bukti | Yang diukur | Kebenaran yang wajib |
|---|---|---|
| **A. Konkurensi inventory** | Total request, order sukses/ditolak, stok negatif, mutasi ganda, error rate, distribusi latensi | `stok negatif = 0` |
| **B. Idempotensi webhook** | Webhook sama berulang | Transisi payment sekali; transisi order sekali; **inventory tidak berubah** (Stock-at-Order); duplikat = no-op. Log atau output test dilampirkan |
| **C. Evaluasi ML** | Ukuran dataset, ukuran test held-out, metrik, confusion matrix, perbandingan baseline, kelemahan; untuk forecast: MAE (dan metrik lain yang sesuai) vs baseline | Dilabeli data sintetis/hand-labeled |
| **D. Biaya hybrid chat** | Request lokal vs LLM, latensi inferensi lokal, latensi LLM, token masuk/keluar, estimasi biaya | Persen penghematan **dihitung dari data**, bukan target |

Bukti B diubah dari teks PM (yang menyebut loyalty dan inventory side effect) karena loyalty deferred dan webhook memang tidak menyentuh inventory.

## 11. CI/CD

| Repo | CI (PR dan push `main`) | CD |
|---|---|---|
| frontend | install → lint → typecheck → generate tipe OpenAPI dan cek diff → test → build | Vercel: preview per PR, production dari `main` |
| backend (Java) | compile → unit → integration (Testcontainers) → package → docker build | build image → push GHCR → deploy → health check |
| backend (Python) | lint → test → docker build | idem |

CI aktif sejak commit pertama. `main` diproteksi. Target hosting: ADR-019 (setelah spike).

## 12. Risiko

| Risiko | Dampak | Mitigasi |
|---|---|---|
| Scope Month-1 besar untuk satu developer | Proyek terhenti di tengah | Urutan potong; Tier 1 diprioritaskan |
| Owner tidak dapat menjelaskan kode | Dipersepsikan sebagai "AI orchestration project" | Understanding gate; fase ditahan |
| Kode kritis salah tetapi test hijau | Klaim correctness palsu | Test konkurensi nyata (Testcontainers), cross-QA, review manusia Phase 4–8, 10 |
| Dataset classifier bocor / terlalu mirip | Akurasi menyesatkan | Aturan kebocoran, set uji terpisah, baseline |
| Recruiter enggan memasukkan API key | Bagian LLM tidak terlihat | Demo lokal tanpa key; transkrip dan video untuk jalur LLM |
| Hosting demo gagal | Tidak ada demo publik | Spike Phase 1; fallback video + panduan lokal |
| Midtrans Sandbox berubah | Phase 6 tersendat | Interface `PaymentGateway` |
| Webhook butuh URL publik saat lokal | Tidak bisa diuji | Tunnel didokumentasikan |
| Bocor secret oleh agent | Insiden | gitleaks, `.env.example`, aturan agent |
| Dua agent FE tanpa design system | UI tidak konsisten | `DESIGN_SYSTEM.md` oleh agent lain sebelum UI pertama |
| Klaim berlebihan di README | Kredibilitas turun | BR-27, ADR-016 |

## 13. Keputusan Terbuka

Default dipakai bila tidak dijawab.

| ID | Pertanyaan | Default |
|---|---|---|
| OD-1 | Tenggat | 1 bulan (ditetapkan PM) — ditutup |
| OD-2 | Chat | Hybrid — ditutup |
| OD-3 | Target hosting backend | Ditentukan spike Phase 1 (ADR-019) |
| OD-4 | Basis minimum spend | Sebelum pajak dan service |
| OD-5 | Versi framework | Terbaru yang didukung saat Phase 1 |
| OD-6 | Area FE: DeepSeek staf, Gemini customer | Diterima |
| **OD-7** | Loyalty, transfer stok, password recovery, refund, laporan penjualan **tidak ada di daftar scope maupun deferred PM** | Deferred. Bila ada yang ingin ditarik ke Month-1, PM harus menambahkannya ke scope dan mengurangi yang lain |
| **OD-8** | Siapa menulis `DESIGN_SYSTEM.md` (Claude dilarang) | DeepSeek menulis, Gemini review, PM menyetujui. **Gerbang implementasi frontend**, bukan gerbang backend; dikerjakan paralel atau setelah Phase 1 backend |
| **OD-9** | Sediakan key LLM demo di server dengan batas harian? | Tidak (risiko biaya dan penyalahgunaan); jalur LLM didemokan lewat video dan transkrip |
| **OD-10** | Nilai ambang void dan θ confidence | Ambang void: contoh demo di seed. θ: dipilih dari validation set setelah Phase 9 |
| **OD-11** | Minimum spend tidak tercapai dan pelanggan menolak membayar selisih | Batas Month-1 yang diterima: lihat Keterbatasan Month-1. Tidak ada fitur baru; ADR kecil hanya bila Fase 5 menemukan alur settlement realistis yang tidak mungkin |

### Keterbatasan Month-1 (sengaja)

- **Minimum spend** adalah kesepakatan operasional sebelum order dibuka. Sistem menegakkannya keras (BR-05): order tidak dapat `PAID` bila subtotal setelah diskon < `minimum_spend_snapshot`. Tidak ada manager override, tidak ada biaya selisih otomatis, tidak ada reservasi atau deposit. Sengketa luar biasa diselesaikan secara operasional di luar sistem. Order yang macet tetap `OPEN`/`PARTIALLY_PAID`; bila belum ada payment, VOID berlaku (ADR-004); bila sudah ada payment, belum ada jalur sistem (batas yang diketahui).
- `MANAGER_REVIEW` adalah status shift. Jangan dipakai sebagai status order.
- VIP/VVIP hanya walk-in bila meja `AVAILABLE`; reservasi dan deposit ditunda.
- Penjualan memotong lokasi `BAR` (BR-26). Lokasi `WAREHOUSE` ada, tetapi transfer gudang→bar ditunda; stok masuk ke BAR lewat `PURCHASE_IN` atau penyesuaian.
- Laporan penjualan multi-venue ditunda.

## 14. Definition of Done

Per fase: `PHASES.md` (setiap fase punya DoD terukur). Dua tingkat kesiapan:

- **`CORE_PORTFOLIO_READY`**: Tier 1 seluruhnya terpenuhi, Bukti A dan B ada (angka hasil ukur), deployment publik berjalan (atau fallback terdokumentasi), README jujur, dan Owner lolos understanding gate (`AI_PERAN.md` bagian 2).
- **`FULL_NIGHTVENUE_READY`**: `CORE_PORTFOLIO_READY` + Tier 2 + Bukti C dan D.

Tier 2 boleh dipotong (ADR-017) tanpa membatalkan `CORE_PORTFOLIO_READY`. Fase yang secara teknis selesai tetapi belum dapat dijelaskan Owner dianggap **belum ready**.
