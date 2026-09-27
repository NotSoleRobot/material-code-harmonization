# NUMM System Status and Architecture

Verified on 22 September 2026. This document reports observed implementation and command output; it does not claim production certification.

## Runtime architecture

| Service | Stack | Host port |
|---|---|---:|
| Frontend | React 19, Vite, TanStack Query, nginx | 3000 (Docker), 5173 (Vite) |
| Backend | Spring Boot 3.3, Java 17, Flyway | 8080 |
| Matching | Python 3.11, Flask, scikit-learn 1.8.0 | 5000 |
| Database | PostgreSQL 16 | 5433 |

Docker activates `python-matching,demo`. The `demo` profile only registers real one-click login accounts; it does not replace JWT authentication, PostgreSQL, or the matching service. Outside that profile, `/api/auth/demo-accounts` is absent and the login page does not render the quick-access strip.

## Material and governance flow

1. The backend persists submitted materials and calls Flask `/extract-attributes` once per ingest batch.
2. Extracted JSON and readable flat attributes are stored on each material.
3. A deterministic signature is created only when all category identity-critical keys are present. Incomplete identities have a null signature and cannot force a merge.
4. Otherwise, deterministic category-ordered candidates are scored by the model. Jobs use `/find-matches-batch` in bounded batches.
5. Exact and near duplicates above the plan threshold are proposed for the matched group. Functional equivalents and variants remain separate groups connected by a group relation.
6. A reviewer from a different CPSE confirms the mapping. A senior reviewer who did not confirm it publishes the group and mints `NUMM-SS-FF-CC-NNNNNN-K`.
7. The final character uses ISO/IEC 7064 MOD 37,36. Format validation runs before checksum validation.

## Security and visibility

The backend implements the 21-row authorization matrix in `SecurityConfig`; the frontend derives navigation and landing routes from `src/auth/roles.js`.

- Operators see their own CPSE materials and their own legacy member codes on published groups.
- Reviewers see complete membership for adjudication.
- Senior reviewers publish and access analytics/audit views.
- Administrators manage users and assumptions; administrators cannot mint or perform ordinary mapping approval.
- Disabled users fail Spring Security's `isEnabled` check.
- JWT configuration has no default in the normal profile. The demo secret exists only in `application-demo.properties`.

Tokens are currently stored in `sessionStorage`. Query state is cleared on logout and unauthorized responses.

## Audit integrity

Audit rows hash this ordered payload:

`prev_hash | user_id | action | entity_type | entity_id | timestamp | old_value | new_value`

A locked single-row head serializes appends. PostgreSQL rejects audit updates/deletes through an append-only trigger. Verification reads pages of 1,000. The demo-only tamper endpoint changes `old_value` so the verifier can demonstrate detection.

The chain is tamper-evident, not tamper-proof. A database administrator able to disable triggers and recompute the full chain can rewrite history.

## Frontend and exports

The interface uses the frozen institutional palette, locally served IBM Plex Sans/Mono and Noto Sans Devanagari, compact shared tables, keyboard focus, no gradients, and a mobile layout. It is built against GIGW 3.0 and WCAG 2.1 AA guidance but has not undergone a formal compliance audit.

Exports include CPSE-scoped cross-reference CSV/XLSX, national catalog CSV/XLSX for senior/admin roles, and SAP CSV with exactly `MATNR, MAKTX, MEINS, MATKL, NUMM_CODE` plus a UTF-8 BOM. Every export writes an audit entry. XLSX uses a 100-row SXSSF window; a 50,000-row heap measurement has not yet been performed.

## Verified command results

Backend command: `mvn -q clean test`.

```text
Reports=7 Tests=68 Failures=0 Errors=0 Skipped=0
SecurityMatrixTest: 39
AnalyticsServiceTest: 2
AuditServiceTest: 3
GovernanceServiceTest: 3
HarmonizationServiceTest: 3
NationalCodeGeneratorTest: 4
Iso7064Mod3736Test: 14
```

The ISO suite performs the plan's 2,000-code single-substitution and adjacent-distinct-transposition property checks.

Matching command: `.venv\Scripts\python.exe -m pytest -q`.

Result: `10 passed in 25.24s`.

Frontend commands: `npm run build`, `npm run lint`, and `node scripts/check-i18n-parity.mjs`.

Results: production build completed, lint returned zero findings, and parity reported `42 used keys, 99 keys per locale`. `base.css` and `components.css` total 242 lines (below the 700-line gate); hardcoded-hex and banned-icon gates returned no matches.

Docker command: clean `docker compose build` followed by `docker compose up -d` against a newly created PostgreSQL volume.

Result: frontend, backend, matching service, and PostgreSQL all reported healthy. Flyway applied all nine migrations through version 8. The frontend returned HTTP 200, the backend actuator returned `UP`, and the matching service returned `ok`. Live login/profile requests passed for the `OPERATOR`, `REVIEWER`, `SENIOR_REVIEWER`, and `ADMIN` demo accounts.

## Scope and limitations

- The classifier is trained on synthetic data from `src/data_generation/`. Recorded test macro-F1 is 0.84; `VARIANT` recall is 0.62 and `NEEDS_REVIEW` recall is 0.64. It has not seen a real CPSE catalog.
- Two labeled synthetic true-match examples fall below the current 0.30 candidate cutoff. The fixed 28-pair regression set passes, but real-catalog calibration remains required.
- ERP interoperability is CSV/XLSX import/export, not a live SAP connector.
- Production authentication should replace `sessionStorage` JWTs with HttpOnly secure cookies and CSRF protection.
