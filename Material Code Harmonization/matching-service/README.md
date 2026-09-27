# NUMM matching service

Python 3.11 Flask service for attribute extraction, schema lookup, comparison, deterministic candidate retrieval, and batched scoring.

## Local run and tests

```powershell
py -3.11 -m venv .venv
.venv\Scripts\python.exe -m pip install -r requirements.txt
$env:PYTHONPATH = "src"
.venv\Scripts\python.exe app.py
.venv\Scripts\python.exe -m pytest -q
```

## Rebuild model artifacts

```powershell
$env:PYTHONPATH = "src"
.venv\Scripts\python.exe src/matching/train.py
```

`models/` and `data/generated/` are ignored for new generated files. The current joblib artifacts and training corpus remain deliberately tracked so the demo works without retraining. They were serialized with scikit-learn 1.8.0, pinned in `requirements.txt`.
