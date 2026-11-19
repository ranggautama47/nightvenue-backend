# Evidence C — Inventory Forecast Evaluation

NOTICE: Evaluated using synthetic portfolio data generated with deterministic seeds. No real-world production accuracy is claimed or implied.

## Dataset provenance

- Generator: `ml-service/scripts/generate_synthetic_forecast_data.py`
- Seed: `20260101`; total days: `126`; start: `2026-01-01`.
- Profiles: `A (ML), B (GRAM), C (PIECE)`.
- Dataset fingerprint (SHA-256): `172536fdcff1aa56da2d25fb1964c725a73fa7b93dad99a5b0cd39644c5c16db`.

## Split and windows

- Training: days 1–84; validation: days 85–105; held-out: days 106–126.
- Horizon: 7 days; validation origins: 84, 91, 98; held-out origins: 105, 112, 119.
- Windows roll forward by the horizon with expanding input and no overlap.

## Model definition

- Regression: OLS weekday one-hot plus linear trend via `numpy.linalg.lstsq`.
- Baseline: historical mean for the same weekday.
- Selection: lower internal backtest MAE; ties select REGRESSION. No hyperparameters exist (OLS is closed form and the baseline is fixed).

## Validation results

| Ingredient | Unit | Regression mean daily MAE | Baseline mean daily MAE | Selected mean daily MAE | Regression windows | Baseline windows | Total quantity absolute error |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| A | ML | 30.773 | 91.715 | 30.773 | 3 | 0 | 109.703 |
| B | GRAM | 31.318 | 27.991 | 30.287 | 1 | 2 | 248.274 |
| C | PIECE | 4.104 | 5.832 | 4.104 | 3 | 0 | 52.826 |

## Held-out results (evaluated once)

| Ingredient | Unit | Regression mean daily MAE | Baseline mean daily MAE | Selected mean daily MAE | Regression windows | Baseline windows | Total quantity absolute error |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| A | ML | 34.551 | 121.205 | 34.551 | 3 | 0 | 460.696 |
| B | GRAM | 37.706 | 37.335 | 38.413 | 1 | 2 | 425.604 |
| C | PIECE | 2.647 | 6.640 | 2.647 | 3 | 0 | 49.637 |

### Held-out windows

| Ingredient | Origin | Selected | Predicted total | Actual total | Internal model MAE | Internal baseline MAE | Regression daily MAE | Baseline daily MAE | Selected daily MAE |
| --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| A | 105 | REGRESSION | 8472.029 | 8325.426 | 25.276 | 97.605 | 40.879 | 87.605 | 40.879 |
| A | 112 | REGRESSION | 8530.358 | 8600.849 | 40.879 | 87.605 | 18.312 | 121.476 | 18.312 |
| A | 119 | REGRESSION | 8638.690 | 8882.291 | 18.312 | 121.476 | 44.462 | 154.536 | 44.462 |
| B | 105 | BASELINE | 3522.193 | 3441.414 | 37.343 | 35.085 | 31.849 | 34.141 | 34.141 |
| B | 112 | REGRESSION | 3464.311 | 3733.773 | 31.849 | 34.141 | 47.564 | 44.329 | 47.564 |
| B | 119 | BASELINE | 3529.887 | 3605.249 | 47.564 | 44.329 | 33.705 | 33.533 | 33.533 |
| C | 105 | REGRESSION | 265.619 | 274.000 | 3.181 | 4.480 | 1.549 | 4.895 | 1.549 |
| C | 112 | REGRESSION | 270.950 | 294.000 | 1.549 | 4.895 | 3.293 | 7.446 | 3.293 |
| C | 119 | REGRESSION | 279.794 | 298.000 | 3.293 | 7.446 | 3.099 | 7.580 | 3.099 |

## Planted vs recovered

| Ingredient | Unit | Planted slope | Recovered OLS slope |
|---|---|---:|---:|
| A | ML | 2.000 | 2.003 |
| B | GRAM | 0.000 | -0.240 |
| C | PIECE | 0.100 | 0.078 |

## Known limitations

- Data is synthetic and generated from assumptions close to the OLS model, so regression winning here is expected and is NOT evidence of real-world accuracy.
- Only three ingredients and three held-out windows are evaluated; no statistical significance is claimed.
- There is no operating-day or closure modelling.
- If the baseline wins on some series, that result is reported as is.
