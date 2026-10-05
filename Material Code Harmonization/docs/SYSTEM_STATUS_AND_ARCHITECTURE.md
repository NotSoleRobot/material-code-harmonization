# NUMM system status and architecture

Verified on 5 October 2026. This describes the current prototype; it does not claim production certification or real CPSE deployment.

## Runtime architecture

| Service | Stack | Host port |
|---|---|---:|
| Frontend | React 19, Vite, TanStack Query, nginx | 3000 (Docker), 5173 (Vite) |
| Backend | Spring Boot 3.3, Java 17, Flyway | 8080 |
| Matching | Python 3.11, Flask, scikit-learn | 5000 |
| Database | PostgreSQL 16 | 5433 |

Docker activates `python-matching,demo`. The demo profile exposes real seeded accounts; it does not bypass JWT authentication, PostgreSQL, or the matching service.

## Current workflow

1. An operator uploads a material CSV and confirms column mapping.
2. The backend rejects structurally incomplete rows and detects repeat CPSE material codes.
3. Records with a pending or confirmed mapping are reported as already harmonized and are not processed again.
4. Only new or previously unmapped records enter attribute extraction and hybrid matching.
5. Complete category identities can use deterministic signatures; incomplete identities cannot force a merge.
6. The model proposes a candidate group, a new group, or manual comparison.
7. A senior reviewer or administrator confirms, rejects, edits, or supersedes the suggestion.
8. Confirmed members become visible in the unified material catalog under a stable `CAT-...` reference.

## Roles

- `OPERATOR`: uploads and views the enterprise's materials, runs pairwise comparisons, and searches the catalog.
- `SENIOR_REVIEWER`: reviews suggestions within assigned categories, searches/exports the catalog, and views dashboard/audit data.
- `ADMIN`: manages users and has full technical-governance access.

## Catalog performance

Catalog search uses a single group query with `EXISTS` for member-code matching, avoiding row multiplication from joins. Trigram and lower-case indexes support description/reference lookup, while mappings are loaded in bounded pages.

## Verification

- Backend: `mvn -q test` → 62 tests, 0 failures, 0 errors.
- Matching service: `.venv\\Scripts\\python.exe -m pytest -q` → 14 passed.
- Frontend: `npm run build` → successful production bundle.

## Limitations

- The matching classifier is trained primarily on synthetic data and still needs real-catalog calibration.
- ERP interoperability is CSV/XLSX import/export, not a live SAP connector.
- The hosted free tier can have cold starts; run the preflight script before judging or presenting.
- Production authentication should move browser tokens to secure HttpOnly cookies with CSRF protection.
