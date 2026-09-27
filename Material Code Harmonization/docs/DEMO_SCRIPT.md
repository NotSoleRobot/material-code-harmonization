# NUMM five-act demonstration

## Act 0 — Reset

Run `docker compose down -v`, then `docker compose up -d --build`. Wait for all four services to become healthy and confirm the catalog is empty.

## Act 1 — Operator ingestion

Use ONGC Operator quick access. On `/ingest`, upload the ONGC SAP CSV, verify column mapping, submit it, watch the asynchronous job, and open `/my-materials`. The list must contain only ONGC records.

Explain that extracted attributes are persisted and incomplete identities never become deterministic merge keys.

## Act 2 — Attribute-aware matching

On `/compare`, compare `CS SEAMLESS PIPE 50MM SCH40 ASTM A106 GRB` with `CARBON STEEL PIPE DN50 SCHEDULE 40 GR.B IS1239`. Show the live relationship, match probability, label probability, checks, warnings, and conflicts. Do not quote a fixed score.

Then compare against `CS SEAMLESS PIPE 200MM SCH40 ASTM A106 GRB`. Show the nominal-size conflict and lower match probability.

## Act 3 — Four-eyes review

Switch to Mechanical Reviewer (IOCL). Approve the ONGC mapping on `/review/:id`. Then attempt an IOCL-submitted record and show the HTTP 409 conflict-of-interest response. The mapping becomes `CONFIRMED`; the group remains `PROPOSED` without a national code.

## Act 4 — Publication

Switch to Senior Reviewer. On `/publish`, publish the confirmed group. Open `/codes/:code`, show the canonical record and members, validate the code, then alter one character and show validation failing. Explain the ISO/IEC 7064 MOD 37,36 check character.

## Act 5 — Audit and analytics

On `/audit`, verify the chain. Under the demo profile, use the administrator-only tamper action, verify again, and show the broken audit ID. Demonstrate that PostgreSQL refuses a direct audit update.

Finally show `/dashboard`: deduplication, rate-contract candidates, price variance, and explicit procurement assumptions. Do not use invented savings values. Close with the Scope and limitations section in `SYSTEM_STATUS_AND_ARCHITECTURE.md`.
