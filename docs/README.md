# NightVenue Docs (v2.1)

Folder ini adalah **satu-satunya sumber kebenaran** untuk desain backend NightVenue. Jangan menyimpan salinan di tempat lain.

## Urutan baca

| # | Berkas | Isi |
|---|---|---|
| 1 | `../CONTEXT.md` | Ringkasan untuk agent (aturan kritis, batas arsitektur) |
| 2 | `PRD.md` | Tujuan, scope, role, aturan bisnis (BR), kebutuhan fungsional (FR) |
| 3 | `DECISIONS.md` | ADR-001..020 + Changelog v2.1 (alasan di balik keputusan) |
| 4 | `APPLICATION_ARCHITECTURE.md` | Arsitektur, alur kritis, API, keamanan |
| 5 | `DATABASE.md` | Skema, constraint, relasi, seed |
| 6 | `UML.md` | Use case, kelas, sequence, state machine |
| 7 | `PHASES.md` | Fase 0–11 dan DoD terukur |
| 8 | `AI_PERAN.md` | Peran agent, cross-QA, understanding gate |
| - | `prompts/` | Prompt eksekusi per fase untuk agent coding |

## Aturan

1. Perubahan dokumen = commit terpisah dengan pesan `docs: ...`; ADR baru diajukan ke PM sebelum dipakai.
2. `CONTEXT.md` harus identik di repo backend dan frontend. Hanya berkas itu yang diduplikasi.
3. `DESIGN_SYSTEM.md` (frontend) tidak ditulis di sini dan bukan syarat backend.
4. Konflik antar dokumen: berhenti, laporkan ke PM.
