# CONTEXT — NightVenue

> Dokumen pendek untuk AI coding agent. Salin ke root **kedua repo** dan jaga tetap identik. Detail ada di dokumen lain; jangan menempelkan PRD ke sini.
> Wajib dibaca sebelum mengubah kode. Dokumen sumber ada di folder `docs/` repo backend: `PRD.md`, `DECISIONS.md`, `DATABASE.md`, `APPLICATION_ARCHITECTURE.md`, `UML.md`, `PHASES.md`, `AI_PERAN.md`. Folder itu satu-satunya sumber kebenaran. `DESIGN_SYSTEM.md` hanya gerbang implementasi frontend.

## Tujuan
Portfolio engineering project yang benar-benar berfungsi: bukti kemampuan Java Spring Boot (logika transaksional, konkurensi, payment idempotent), PostgreSQL/Redis, integrasi FastAPI (ML terapan), LLM hemat biaya, CI/CD, test, dan deployment publik. Produk **fiktif**; dilarang memakai nama/logo merek nyata di repo, seed, atau demo. Target siklus pertama: 1 bulan.

## Repositori
`nightvenue-frontend` (Next.js) dan `nightvenue-backend` (`api/` Spring Boot, `ml-service/` FastAPI, `infra/`). Tidak ada repo ketiga.

## Stack (keluarga; versi dipin di Phase 1, lihat ADR-018)
Frontend: Next.js App Router, TypeScript strict, Tailwind, shadcn/ui, TanStack Query, Zustand, React Hook Form + Zod.
Backend: Java LTS, Spring Boot, Spring Security + JWT, JPA/Hibernate, Flyway, MapStruct, springdoc-openapi, Actuator.
ML: Python, FastAPI, scikit-learn, pandas. Data: PostgreSQL, Redis. CI/CD: GitHub Actions, GHCR, Vercel.
Versi terpilih: JDK 21 (Temurin LTS 21.0.12.1), Spring Boot 4.1.2, Maven 3.9.9, Python 3.12 (image 3.12-slim-bookworm), FastAPI 0.162.0, PostgreSQL 17.4-alpine, Redis 7.4.2-alpine.

## Batas arsitektur
- **Spring Boot = system of record** dan pemilik semua logika bisnis.
- **FastAPI hanya**: `POST /ml/chat/classify`, `POST /ml/forecast/inventory`. Dilarang: auth, checkout, payment, mutasi inventory/bottle, aturan transaksional. Kegagalan FastAPI tidak boleh menghentikan transaksi inti.
- Redis hanya data ephemeral (cache menu, rate limit, refresh token). PostgreSQL = sumber kebenaran.
- Frontend bukan batas keamanan; otorisasi di backend.

## Aturan bisnis kritis
1. **Business Day**: `business_date = local(timestamp, venue.timezone) − cutoff_hour`, dihitung hanya oleh `BusinessDateService`, disimpan saat record dibuat, tidak dihitung ulang (ADR-001).
2. **Stock-at-Order**: stok dipotong saat item diterima ke order, bukan saat bayar; `UPDATE ... WHERE quantity >= :need`; bahan diurut ID asc; tiap perubahan masuk ledger (ADR-003).
3. **VOID**: kompensasi satu kali; pengembalian dari ledger; `disposition` RESTORED/WASTED; ditolak bila ada payment PENDING/PAID (ADR-004).
4. **Payment**: `CASH`, `CARD_EDC`, `QRIS_MIDTRANS`; Order 1:N Payment; pembuatan payment diserialkan lewat lock baris order dan `amount ≤ total_due − PAID − PENDING`; webhook = signature + Status API + `payment_events` unik; settlement terlambat pada order VOIDED = anomali tercatat (ADR-005/006).
5. **Shift**: satu shift = satu business date; semua payment CASH (role apa pun) wajib teratribusi ke shift OPEN pemrosesnya; kas dihitung per `payments.shift_id`; reviewer bukan pemilik shift; lewat cutoff → `MANAGER_REVIEW` (ADR-002).
6. **Bottle keeping**: invariant `0 ≤ remaining ≤ original`; satu order item (`quantity = 1`) = paling banyak satu vault (UQ); deposit hanya dari order `PAID` dan customer `age_verified`; redeem butuh QR token + pencocokan customer + konfirmasi kasir (ADR-020).
7. **Chat**: classifier → DB lokal → BYOK LLM; fallback Spring maksimal 5 kategori; `OUT_OF_SCOPE` tidak memanggil LLM; key tidak pernah disimpan/dicatat (ADR-011/012/013).
8. Uang `BIGINT` rupiah, kuantitas `NUMERIC(14,3)`/`BigDecimal`; dilarang `double`/`float`.
9. Tidak ada metrik tanpa pengukuran (ADR-016).

## Role
`ADMIN`, `MANAGER`, `CASHIER`, `CUSTOMER`. Role **tidak hierarkis**; otorisasi mengikuti matriks di `PRD.md`. Staf dibatasi venue lewat `user_venues`.

## Konvensi
API publik `/api/v1/*` (RFC 7807 untuk error, `Idempotency-Key` pada tambah item, buat payment, void, deposit botol, redeem). Migrasi hanya Flyway; `ddl-auto` tidak boleh `update`. Branch `feature/*`, `fix/*`, `chore/*`.

## Non-goals
Kubernetes, Kafka, RabbitMQ, Elasticsearch, event sourcing, CQRS, GraphQL, service mesh, microservice Java ganda, blockchain, voice ordering, rekomendasi AI palsu, WebSocket tanpa kebutuhan nyata.

## Deferred (jangan dikerjakan tanpa persetujuan PM)
Reservasi, recipe versioning, last call, void anomaly detection, reaktivasi botol email, simulasi what-if, loyalty, transfer stok, password recovery, refund, laporan penjualan, split per item, VOID per item.

## Aturan agent
1. Baca file ini, bagian fase di `PHASES.md`, dan FR terkait di `PRD.md`.
2. Audit kode yang ada; sebut file yang akan disentuh.
3. Jangan ganti stack; jangan tambah dependency tanpa alasan tertulis; jangan buat fitur di luar fase.
4. Skema hanya lewat migration Flyway; kontrak API berubah → perbarui OpenAPI.
5. Tambah test untuk logika bisnis; jalankan test; lampirkan output sebelum mengklaim selesai.
6. Penulis ≠ reviewer pada PR yang sama. PR kritis wajib dibaca dan dapat dijelaskan oleh Owner sebelum merge.
7. Jangan menulis secret ke repo atau log. Jangan menghapus test agar build hijau.
8. Konflik antar dokumen atau requirement ambigu → berhenti dan laporkan ke PM.
