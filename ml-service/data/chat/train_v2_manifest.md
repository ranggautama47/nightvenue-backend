# train_v2 manifest

Canonical training input (from now on): `ml-service/data/chat/train_v2.jsonl` only. Do not use `train.jsonl` + R files again.

## Composition

`train_v2.jsonl` is the concatenation, in this order, of:
- `train.jsonl` (576 records)
- `train_additions_r1.jsonl` (143 records)
- `train_additions_r2.jsonl` (36 records)
- `train_additions_r3.jsonl` (72 records)
- `train_additions_r4.jsonl` (60 records)

Total: **887** records. `train.jsonl` and all R files were not modified.

## Counts by intent

| Intent | Count |
|---|---:|
| ADVANCED_CONVERSATION | 94 |
| BOTTLE_KEEPING | 47 |
| GENERAL_VENUE_INFORMATION | 76 |
| MENU_INFORMATION | 66 |
| MINIMUM_SPEND | 47 |
| OPENING_HOURS | 68 |
| OUT_OF_SCOPE | 221 |
| PROMOTION | 52 |
| RESERVATION | 98 |
| TABLE_AVAILABILITY | 58 |
| VENUE_AREA | 60 |
| **TOTAL** | **887** |

## Counts by language

| Language | Count |
|---|---:|
| en | 255 |
| id | 479 |
| mixed | 153 |
| **TOTAL** | **887** |

## SHA-256

| File | Records | SHA-256 |
|---|---:|---|
| `train.jsonl` | 576 | `1b45cf82272e78447c3e1b5eb0292e2ebf34c71f2044ab2654f29d698f66c3d0` |
| `train_additions_r1.jsonl` | 143 | `34c99a37e3d13bccce409b8dcfddd7eaa988c014d5d73e832966f1ec4d295299` |
| `train_additions_r2.jsonl` | 36 | `e2c85d0bea470ea0ed5260af5cf6e8b1e8b0bd4bd4b61fc8a2c4dbb667d6b1c9` |
| `train_additions_r3.jsonl` | 72 | `df6f81db4d9919e209a613c9731b93e5738f8573021719a3b19930603a06d2db` |
| `train_additions_r4.jsonl` | 60 | `b0043bc09b8a5dce1cfbe116ff065ded608b27f6b43beeb6c04a4a6345083373` |
| `train_v2.jsonl` | 887 | `41177db35fac9331e9e0ded94cb298c21f7521d47d6db571db6fdc9b4928f3b6` |

## Deviations and provenance notes

- R1, R2, and R3 were created by Copilot before PM approval; R1 and R2 were directed by errors on an earlier validation set.
- R3, R4, and `val_accept` agents used PowerShell and loaded a skill while their reports said otherwise.
- The R4 and `val_accept` prompts were written by an assistant that had seen earlier dev-set error texts (small leakage risk); `val_accept` was written by a separate Copilot chat that read only `LABEL_GUIDE.md`.

## Reservation clarification (do not edit `LABEL_GUIDE.md`)

Questions asking for reservation policies, rules, availability, deposit requirements, cancellation policy, walk-in policy, or lead time are `RESERVATION` when asking for information only. Requests to actually create, book, hold, modify, cancel, or pay for a specific reservation are `OUT_OF_SCOPE`. Do not classify solely by presence of a name, time, or party size.
