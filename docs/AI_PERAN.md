# AI_PERAN — NightVenue

Pendamping: `PHASES.md`, `CONTEXT.md`, `DECISIONS.md`. Owner (Rangga) adalah pengambil keputusan akhir.

---

## 1. Peran

| Agent | Peran | Tanggung jawab |
|---|---|---|
| **GPT** | PM | Menjaga PRD, urutan fase, keputusan scope, persetujuan ADR, penilaian laporan PR |
| **Claude** | Backend (penulis + reviewer) | Spring Boot, desain domain, review kritis; QA bersama Qwen |
| **Qwen** | Backend (penulis + QA) | Spring Boot, ml-service, test; QA bersama Claude |
| **DeepSeek** | Frontend staf/admin | POS, inventory, shift, bottle; **menulis `DESIGN_SYSTEM.md`** (OD-8) |
| **Gemini** | Frontend customer | Halaman customer, UI chat, review `DESIGN_SYSTEM.md` |

**Claude tidak menulis `DESIGN_SYSTEM.md`** (instruksi Owner). Berkas itu ditulis DeepSeek, direview Gemini, disetujui PM. Ia adalah **gerbang implementasi frontend**, bukan gerbang backend.

**Tool eksekusi (Antigravity, Claude Code, dan sejenisnya) bukan peran.** Model yang menjalankannya memegang peran penulis atau reviewer yang ditugaskan PM untuk tugas itu; penulis ≠ reviewer tetap berlaku.

---

## 2. Aturan Kerja

1. Penulis ≠ reviewer pada PR yang sama (cross-QA).
2. Setiap agent membaca `CONTEXT.md`, bagian fase di `PHASES.md`, dan FR terkait sebelum bekerja.
3. Audit kode yang ada sebelum menulis; sebut berkas yang akan disentuh.
4. Tidak mengganti stack, menambah dependency, atau membuat fitur di luar fase tanpa persetujuan PM tertulis.
5. Konflik dokumen atau requirement ambigu → berhenti dan lapor ke PM.
6. Tidak mengklaim selesai tanpa output test.
7. Tidak menulis secret ke repo atau log.

### Human understanding gate
Untuk topik **inventory, VOID, payment, business day, ML, LLM**: Owner harus membaca PR dan mampu menjelaskan alurnya dengan kata sendiri sebelum merge.

Owner wajib mampu menjelaskan secara spesifik: **Stock-at-Order, conditional UPDATE, race condition, VOID compensation, Midtrans signature, webhook idempotency, Business Date, TF-IDF, Logistic Regression, confidence threshold, baseline evaluation, BYOK, dan LLM grounding**. Contoh pertanyaan uji: mengapa conditional UPDATE mencegah oversell? Apa beda verifikasi signature dengan idempotensi webhook? Mengapa `Idempotency-Key` tidak mencegah dua payment berbeda melampaui tagihan? Agent menyediakan ringkasan "cara kerja dan alasan" di laporan PR. Tujuannya agar portofolio dapat dipertanggungjawabkan di wawancara.

---

## 3. Penugasan per Fase

| Fase | Penulis | Reviewer | Catatan |
|---|---|---|---|
| 1 | Qwen (backend), DeepSeek (frontend) | Claude | Spike hosting oleh Qwen, ditinjau PM |
| 2 | Qwen | Claude | `BusinessDateService` direview ketat |
| 3 | Claude | Qwen | |
| 4 | Claude | Qwen | |
| 5 | Claude | Qwen | Bukti A |
| 6 | Claude | Qwen | Bukti B |
| 7 | Qwen | Claude | |
| 8 | Qwen | Claude | |
| 9 | Qwen | Claude | Bukti C; evaluasi dibaca ulang Claude |
| 10 | Claude | Qwen | Bukti D; privasi key |
| 11 | Owner + GPT (narasi) | Claude + Qwen | Agent hanya menyuplai bukti |
| FE staf | DeepSeek | Gemini | |
| FE customer + UI chat | Gemini | DeepSeek | UI tidak boleh mengklaim "key tidak meninggalkan browser" |

---

## 4. Template Laporan PR

```text
Fase / FR:
Berkas disentuh:
Ringkasan perubahan:
Migrasi (nama berkas):
Test dijalankan + hasil (tempel output):
Cara kerja dan alasan (untuk human understanding gate):
Risiko / keterbatasan:
Penyimpangan dari dokumen (jika ada, dan alasan):
```

Reviewer menjawab: temuan (kritis / sedang / kecil), uji yang ditambahkan, dan keputusan (setuju / perlu revisi).

---

## 5. Eskalasi

| Situasi | Tindakan |
|---|---|
| Dokumen saling bertentangan | Hentikan, lapor PM |
| Perlu ADR baru | Ajukan tertulis ke PM sebelum implementasi |
| Test gagal dan penyebab tidak jelas | Lampirkan log, jangan hapus test |
| Fitur deferred diminta | Tolak kecuali PM menyetujui |
| Agent tidak sependapat dengan reviewer | Keduanya menulis argumen singkat; PM memutuskan, Owner dapat membatalkan |
