# val_accept_b2 manifest

`ml-service/data/chat/val_accept_b2.jsonl` is derived from `ml-service/data/chat/val_accept.jsonl`. `val_accept.jsonl` (300 records) was kept untouched (checked: final newline present, no CR). The b2 file is written with LF line endings and a final newline.

## Source

| File | SHA-256 |
|---|---|
| `val_accept.jsonl` | `fb2c25dc9d1274acd2efef076fa011ba9f5fcf77d38d43ee7deda32704cc56d6` |

## Normalization and exclusion rule

Normalization: lowercase, replace non-word characters with space, collapse whitespace, strip.

Excluded records: exactly the records of `val_accept.jsonl` whose normalized text exactly equals any `train_v2.jsonl` normalized text. Reason: exact normalized overlap with the canonical training corpus (`train_v2.jsonl`).

Excluded ids (4): `va-0670`, `va-0698`, `va-0890`, `va-0934`

## Result

| File | Records | SHA-256 |
|---|---:|---|
| `val_accept_b2.jsonl` | 296 | `b8bcd2f611f7ca6e84ad8745c288edc8b019137ec12b4c32b387ac3abd1f71ef` |

Counts:
- OUT_OF_SCOPE: 108
- Non-OOS: 188
- Total: 296

## Counts by intent

| Intent | Count |
|---|---:|
| ADVANCED_CONVERSATION | 19 |
| BOTTLE_KEEPING | 19 |
| GENERAL_VENUE_INFORMATION | 19 |
| MENU_INFORMATION | 19 |
| MINIMUM_SPEND | 19 |
| OPENING_HOURS | 18 |
| OUT_OF_SCOPE | 108 |
| PROMOTION | 19 |
| RESERVATION | 19 |
| TABLE_AVAILABILITY | 19 |
| VENUE_AREA | 18 |
| **TOTAL** | **296** |

## Counts by language

| Language | Count |
|---|---:|
| en | 81 |
| id | 153 |
| mixed | 62 |
| **TOTAL** | **296** |

This file is a validation set (used for model comparison and theta selection), not an independent blind test.
