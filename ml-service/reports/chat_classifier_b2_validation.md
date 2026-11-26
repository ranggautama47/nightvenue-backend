# Chat Classifier B2 Validation Evaluation

## 1. Summary
- Status: **FAILURE**
- Model version: `chat-charwb-lr-0a01d14c6704ab37`
- Selected theta: `NONE`
- Raw 11-class accuracy: `0.871528`
- Raw 11-class macro-F1: `0.877697`
- Train fingerprint: `b049cffce6283ef118ef9f5f6ad6568e550995ee41cce37eb4fdf8b3e4481a37`
- Validation fingerprint: `3af03a264b6c85b9755a76e4445c343c3b4583f78e63eed2ac0c75ff2727eb8a`
- Counts: train=576, val=288, local=216, advanced=24, oos=48

## 2. Validation-selection caveat
Theta was selected on the validation set. All validation metrics at the selected theta are in-sample and are NOT a generalization estimate. The only unbiased estimate will come from the heldout set, authored after model and theta are frozen and approved.
The CV macro-F1 0.777773 is train-only cross-validation, not a validation result.

## 3. Conventions
- Zero-denominator rule: any ratio with denominator 0 is reported as 0 (0/1).
- Confidence threshold is inclusive: a prediction passes when `confidence >= theta`; `confidence < theta` falls back to `ADVANCED` (unless intent is `ADVANCED_CONVERSATION`, which routes `ADVANCED` first).
- Selection order among feasible candidates: maximize `local_macro_f1`, minimize `false_local_count`, maximize `correct_local_count`, maximize `theta` (exact comparison, no tolerance).
- Feasibility rule: `oos_feasible = (oos_tp * 10 >= 9 * N_oos)` (integer arithmetic).

## 4. Raw 11-class metrics
- Accuracy: `0.871528`
- Macro-F1: `0.877697`

| Intent | Precision | Recall | F1 | Support |
| --- | ---: | ---: | ---: | ---: |
| OPENING_HOURS | 0.950000 | 0.791667 | 0.863636 | 24 |
| VENUE_AREA | 0.718750 | 0.958333 | 0.821429 | 24 |
| MENU_INFORMATION | 0.857143 | 0.750000 | 0.800000 | 24 |
| BOTTLE_KEEPING | 1.000000 | 1.000000 | 1.000000 | 24 |
| MINIMUM_SPEND | 0.960000 | 1.000000 | 0.979592 | 24 |
| PROMOTION | 0.958333 | 0.958333 | 0.958333 | 24 |
| TABLE_AVAILABILITY | 1.000000 | 0.916667 | 0.956522 | 24 |
| RESERVATION | 0.888889 | 1.000000 | 0.941176 | 24 |
| GENERAL_VENUE_INFORMATION | 0.800000 | 0.833333 | 0.816327 | 24 |
| ADVANCED_CONVERSATION | 0.633333 | 0.791667 | 0.703704 | 24 |
| OUT_OF_SCOPE | 0.921053 | 0.729167 | 0.813953 | 48 |

Confusion matrix (rows = actual, columns = predicted, taxonomy order):

| actual \ predicted | OPENING_HOURS | VENUE_AREA | MENU_INFORMATION | BOTTLE_KEEPING | MINIMUM_SPEND | PROMOTION | TABLE_AVAILABILITY | RESERVATION | GENERAL_VENUE_INFORMATION | ADVANCED_CONVERSATION | OUT_OF_SCOPE |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| OPENING_HOURS | 19 | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 1 | 2 | 1 |
| VENUE_AREA | 0 | 23 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| MENU_INFORMATION | 0 | 0 | 18 | 0 | 0 | 1 | 0 | 0 | 3 | 2 | 0 |
| BOTTLE_KEEPING | 0 | 0 | 0 | 24 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| MINIMUM_SPEND | 0 | 0 | 0 | 0 | 24 | 0 | 0 | 0 | 0 | 0 | 0 |
| PROMOTION | 0 | 0 | 0 | 0 | 0 | 23 | 0 | 0 | 1 | 0 | 0 |
| TABLE_AVAILABILITY | 0 | 1 | 0 | 0 | 0 | 0 | 22 | 0 | 0 | 0 | 1 |
| RESERVATION | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 24 | 0 | 0 | 0 |
| GENERAL_VENUE_INFORMATION | 0 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 20 | 0 | 1 |
| ADVANCED_CONVERSATION | 1 | 3 | 0 | 0 | 1 | 0 | 0 | 0 | 0 | 19 | 0 |
| OUT_OF_SCOPE | 0 | 2 | 1 | 0 | 0 | 0 | 0 | 3 | 0 | 7 | 35 |

## 5. Routed local metrics at theta
No feasible theta selected; routed local metrics at theta are not available.

## 6. OOS and false-local
- No feasible theta selected.
- Best achievable OOS true positives: `35`

## 7. Local coverage
No feasible theta selected; coverage metrics at theta are not available.

## 8. Threshold scan summary
- Candidate count: `578`
- Feasible count: `0`
- Best achievable OOS true positives: `35`

First 10 feasible candidates in selection ranking:

| Rank | Theta | Local macro-F1 | False-local count | Correct local count | OOS TP |
| ---: | ---: | ---: | ---: | ---: | ---: |
| - | NONE | - | - | - | - |

## 9. Exploratory baselines
Exploratory baselines are reported as raw 11-class accuracy and macro-F1 only. Baselines are exploratory and never select theta.
The keyword baseline keyword list was authored after the validation set was inspected, so its numbers are optimistic and not a fair comparator. Its rule (zero hits -> `OUT_OF_SCOPE`, more than one intent matched -> `ADVANCED_CONVERSATION`) is a coarse proxy for 'needs more than one local source' and is not the label definition.
The length baseline is fitted on train only and is diagnostic.

| Baseline | Raw accuracy | Raw macro-F1 |
| --- | ---: | ---: |
| keyword | 0.625000 | 0.659495 |
| length | 0.177083 | 0.139171 |

## 10. Failure
- Best achievable OOS recall over all candidates: `0.729167` (`35 / 48`).
- The 0.90 OOS target was not relaxed and must be escalated to the PM.

