# NUMM demonstration

## Act 0 — Preflight

Run `docker compose up -d --build`, wait for all four services to become healthy, and open `http://localhost:3000`. For the hosted demo, run `scripts/preflight_demo.ps1` first so sleeping services are awake.

## Act 1 — Operator ingestion

Use the ONGC Operator account. On `/ingest`, upload the sample CSV, verify column mapping, submit it, and watch the asynchronous job. Point out the separate counts for new records, already-harmonized records, records sent to harmonization, and records needing review.

Upload the same file again. Existing CPSE material codes should be reported as already harmonized or already awaiting review and should not be sent through matching again.

## Act 2 — Attribute-aware matching

On `/compare`, compare `CS SEAMLESS PIPE 50MM SCH40 ASTM A106 GRB` with `CARBON STEEL PIPE DN50 SCHEDULE 40 GR.B IS1239`. Show the live relationship, score, checks, warnings, and conflicts. Then compare it with a 200 mm pipe and show the nominal-size conflict. Do not quote a fixed score because it is model output.

## Act 3 — Technical review

Switch to Senior Reviewer. Open `/review`, inspect the evidence, edit standardized fields if necessary, and approve or reject the suggestion. Explain that all model output remains a proposal until this decision.

## Act 4 — Catalog and audit

Open `/catalog` and search by description, enterprise material code, or catalog reference. Show the harmonized group and its member records. Then open `/audit` and verify the hash chain. Finish on `/dashboard` with ingestion totals, harmonized groups, consolidation rate, review counts, enterprise coverage, and category distribution.
