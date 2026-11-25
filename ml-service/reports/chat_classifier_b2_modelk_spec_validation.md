# Chat Classifier B2 Model K Validation Evaluation

## 1. Summary
- Status: **SUCCESS**
- Model version: `chat-modelk-lr-d98a37b805f0f770`
- Selected theta: `0.2663504950761665`
- Local macro-F1 (routed): `0.893748`
- OOS recall (routed): `0.972222`
- OOS precision (routed): `0.954545`
- False-local rate: `0.039370`
- Correct local coverage: `0.887574`
- Highest feasible theta in declared grid: `0.5253627713368553`
- Share of non-OOS validation routed to ADVANCED (LLM) at selected theta: `0.101064` (`19 / 188`)
- Raw 11-class accuracy: `0.912162`
- Raw 11-class macro-F1: `0.892131`
- Train fingerprint: `651e5e5fa095c712ed77d8794b810d52fd0cab63cbd5e3ebba0abea466f7b185`
- Validation fingerprint: `337870a23c44ed6d58f97246d794fc6b21aa87eaf787f27f85e66d97b0e6eee8`
- Counts: train=887, val=296, local=169, advanced=19, oos=108
- Determinism evidence: Repeated training on train_v2 with fixed config produced identical model_version=True, train_fingerprint=True, probe_probabilities_equal=True.

## 2. Validation-selection caveat
Theta was selected on the validation set (`val_accept_b2.jsonl`). All validation metrics at the selected theta are in-sample and are NOT a generalization estimate. `val_accept_b2` is a validation set (used for model comparison and theta selection), NOT an independent blind test.

## 3. Conventions
- Zero-denominator rule: any ratio with denominator 0 is reported as 0 (0/1).
- Confidence threshold is inclusive: a prediction passes when `confidence >= theta`; `confidence < theta` falls back to `ADVANCED` (unless intent is `ADVANCED_CONVERSATION`, which routes `ADVANCED` first).
- Selection order among feasible candidates: maximize `local_macro_f1`, minimize `false_local_count`, maximize `correct_local_count`, maximize `theta` (exact comparison, no tolerance).
- Feasibility rule: `oos_feasible = (oos_tp * 10 >= 9 * N_oos)` (integer arithmetic).
- Over-refusal rate (non-OOS routed to REFUSED) is a reporting guard only, not a gate.

## 4. Raw 11-class metrics
- Accuracy: `0.912162`
- Macro-F1: `0.892131`

| Intent | Precision | Recall | F1 | Support |
| --- | ---: | ---: | ---: | ---: |
| OPENING_HOURS | 0.894737 | 0.944444 | 0.918919 | 18 |
| VENUE_AREA | 0.640000 | 0.888889 | 0.744186 | 18 |
| MENU_INFORMATION | 0.900000 | 0.947368 | 0.923077 | 19 |
| BOTTLE_KEEPING | 0.944444 | 0.894737 | 0.918919 | 19 |
| MINIMUM_SPEND | 1.000000 | 0.894737 | 0.944444 | 19 |
| PROMOTION | 1.000000 | 1.000000 | 1.000000 | 19 |
| TABLE_AVAILABILITY | 0.882353 | 0.789474 | 0.833333 | 19 |
| RESERVATION | 1.000000 | 0.947368 | 0.972973 | 19 |
| GENERAL_VENUE_INFORMATION | 0.928571 | 0.684211 | 0.787879 | 19 |
| ADVANCED_CONVERSATION | 0.833333 | 0.789474 | 0.810811 | 19 |
| OUT_OF_SCOPE | 0.945946 | 0.972222 | 0.958904 | 108 |

Confusion matrix (rows = actual, columns = predicted, taxonomy order):

| actual \ predicted | OPENING_HOURS | VENUE_AREA | MENU_INFORMATION | BOTTLE_KEEPING | MINIMUM_SPEND | PROMOTION | TABLE_AVAILABILITY | RESERVATION | GENERAL_VENUE_INFORMATION | ADVANCED_CONVERSATION | OUT_OF_SCOPE |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| OPENING_HOURS | 17 | 0 | 0 | 0 | 0 | 0 | 1 | 0 | 0 | 0 | 0 |
| VENUE_AREA | 0 | 16 | 0 | 0 | 0 | 0 | 1 | 0 | 0 | 1 | 0 |
| MENU_INFORMATION | 0 | 0 | 18 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| BOTTLE_KEEPING | 0 | 0 | 0 | 17 | 0 | 0 | 0 | 0 | 0 | 0 | 2 |
| MINIMUM_SPEND | 1 | 0 | 0 | 0 | 17 | 0 | 0 | 0 | 0 | 1 | 0 |
| PROMOTION | 0 | 0 | 0 | 0 | 0 | 19 | 0 | 0 | 0 | 0 | 0 |
| TABLE_AVAILABILITY | 0 | 2 | 1 | 0 | 0 | 0 | 15 | 0 | 0 | 1 | 0 |
| RESERVATION | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 18 | 0 | 0 | 1 |
| GENERAL_VENUE_INFORMATION | 0 | 5 | 0 | 0 | 0 | 0 | 0 | 0 | 13 | 0 | 1 |
| ADVANCED_CONVERSATION | 0 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 15 | 2 |
| OUT_OF_SCOPE | 1 | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 1 | 0 | 105 |

## 5. Routed local metrics at theta
- Selected theta: `0.2663504950761665`
- Local macro-F1: `0.893748`

| Local intent | Precision | Recall | F1 | Support |
| --- | ---: | ---: | ---: | ---: |
| OPENING_HOURS | 0.894737 | 0.944444 | 0.918919 | 18 |
| VENUE_AREA | 0.640000 | 0.888889 | 0.744186 | 18 |
| MENU_INFORMATION | 0.900000 | 0.947368 | 0.923077 | 19 |
| BOTTLE_KEEPING | 0.944444 | 0.894737 | 0.918919 | 19 |
| MINIMUM_SPEND | 1.000000 | 0.894737 | 0.944444 | 19 |
| PROMOTION | 1.000000 | 1.000000 | 1.000000 | 19 |
| TABLE_AVAILABILITY | 0.882353 | 0.789474 | 0.833333 | 19 |
| RESERVATION | 1.000000 | 0.947368 | 0.972973 | 19 |
| GENERAL_VENUE_INFORMATION | 0.928571 | 0.684211 | 0.787879 | 19 |

## 6. OOS and false-local
- `oos_tp / N_oos`: `105 / 108`
- OOS recall: `0.972222`
- OOS precision: `0.954545`
- Over-refusal (non-OOS routed REFUSED): `5` / `188` (`0.026596`, 5% guard only)
- OOS -> ADVANCED: `0`
- OOS -> local-intent (LOCAL): `3`
- `false_local_count / N_nonlocal`: `5 / 127`
- False-local rate: `0.039370`

## 7. Local coverage
- `local_route_coverage`: `0.958580`
- `correct_local_coverage`: `0.887574`
- `local_route_precision`: `0.898204`
- Route counts: LOCAL=167, ADVANCED=19, REFUSED=110
- Low-confidence fallback count: `1`

## 8. Threshold scan summary
- Candidate count: `594`
- Feasible count: `90`
- Highest feasible theta in declared grid: `0.5253627713368553`
- Best achievable OOS true positives: `105`

First 10 feasible candidates in selection ranking:

| Rank | Theta | Local macro-F1 | False-local count | Correct local count | OOS TP |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 0.2663504950761665 | 0.893748 | 5 | 150 | 105 |
| 2 | 0.23864242101327687 | 0.893748 | 5 | 150 | 105 |
| 3 | 0.23864242101327685 | 0.893748 | 5 | 150 | 105 |
| 4 | 0.0 | 0.893748 | 5 | 150 | 105 |
| 5 | 0.36932725225910096 | 0.893625 | 4 | 147 | 103 |
| 6 | 0.35661959851170155 | 0.893625 | 4 | 147 | 103 |
| 7 | 0.3764939050160627 | 0.892105 | 3 | 146 | 103 |
| 8 | 0.3698172552356513 | 0.892105 | 3 | 146 | 103 |
| 9 | 0.3566195985117015 | 0.891560 | 5 | 147 | 103 |
| 10 | 0.35579644330622656 | 0.891560 | 5 | 147 | 103 |

## 9. Exploratory baselines
Exploratory baselines are reported as raw 11-class accuracy and macro-F1 only. Baselines are exploratory and never select theta.
The keyword baseline keyword list was authored after the validation set was inspected, so its numbers are optimistic and not a fair comparator. Its rule (zero hits -> `OUT_OF_SCOPE`, more than one intent matched -> `ADVANCED_CONVERSATION`) is a coarse proxy for 'needs more than one local source' and is not the label definition.
The length baseline is fitted on train only and is diagnostic.

| Baseline | Raw accuracy | Raw macro-F1 |
| --- | ---: | ---: |
| keyword | 0.591216 | 0.560740 |
| length | 0.081081 | 0.037606 |
