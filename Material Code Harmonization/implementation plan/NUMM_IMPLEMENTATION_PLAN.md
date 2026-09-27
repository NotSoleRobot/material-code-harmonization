# NUMM / SAMAAN — Rebuild & Remediation Plan

**Target repository:** `Material Code Harmonization/`
**Audience:** Antigravity (autonomous coding agent)
**Authority:** This document supersedes `docs/SYSTEM_STATUS_AND_ARCHITECTURE.md`, `docs/HANDOVER_CONTEXT.md`, and every `docs/Material_Master_Project_CONTEXT_v*.md` file wherever they disagree. Those files contain claims that are not true of the code. See §11.

---

## 0. Rules of engagement for the agent

Read this section before touching a file.

**R1 — Stack is frozen.** Spring Boot 3.3 + Java 17 + PostgreSQL 16 + Flyway for the backend. Python 3.11 + Flask + scikit-learn for the matching service. React 19 + Vite + react-router + TanStack Query for the frontend. Do not introduce Kafka, Redis, Elasticsearch, Next.js, Tailwind, a component library, a state-management library, or an ORM change. Do not rewrite the Flask service in Java or vice versa.

**R2 — Work in the order given.** The work packages in §6 are ordered by dependency, not by preference. WP1 unblocks everything. Do not start WP5 before WP2 is green.

**R3 — One work package per commit.** Each commit message: `WP<n>: <what changed>`. Never bundle unrelated fixes.

**R4 — Every work package has a Gate (§6). Do not declare a WP done until its Gate command runs clean and you paste the output.** "It should work now" is not acceptable. If a Gate fails, fix the cause, do not weaken the Gate.

**R5 — No invented data, ever.** No mock fallbacks, no placeholder statistics, no `Math.random()` in a code path the UI can reach, no hardcoded "94.1%" anywhere. If the backend fails, the UI shows the failure.

**R6 — No new runtime dependencies without pinning.** Every dependency added to `requirements.txt` gets `==` with an exact version. Every npm dependency added gets an exact version (no `^`).

**R7 — Delete is a valid change.** This codebase has ~1,500 lines of dead code. Removing it is part of the job, not a distraction. §12 lists what to delete.

**R8 — Do not expand scope.** No new features beyond what §5 and §6 specify. No "while I was in here" refactors of files that are not in the current WP. If you find a defect outside the current WP, add it to a running `docs/FOUND_ISSUES.md` file and keep going.

**R9 — When a claim in a comment or doc contradicts the code, the code is the truth and the comment is the bug.** Fix both.

**R10 — Preserve the demo narrative.** The five-act demo in §10 must work end to end after every WP from WP4 onward. If a change breaks the demo path, that is a failed WP.

---

## 1. What this system is (ground truth)

A national material-master harmonizer for Indian CPSEs (ONGC, IOCL, GAIL, BHEL, SAIL). Different enterprises describe the same physical item differently. The system ingests those messy legacy records, uses a hybrid ML matcher to propose that two records are the same item, routes the proposal to a human reviewer, and — only on human approval — mints a single authoritative national code that all enterprises can reference.

**Three services:**

| Service | Directory | Port | Role |
|---|---|---|---|
| Spring Boot backend | `material-master-backend/` | 8080 | Ingestion, governance state machine, code minting, audit chain, export, analytics |
| Flask matching service | `matching-service/` | 5000 | Attribute extraction, feature engineering, RandomForest classification, explanation generation |
| React SPA | `frontend/` | 3000 (nginx) / 5173 (vite dev) | All user interaction |

**Domain objects that matter:**

- `material` — one messy legacy row as a CPSE submitted it. Immutable after ingest.
- `material_group` — one canonical national item. Gets a `common_material_code` when published.
- `material_mapping` — the link between a `material` and a `material_group`, carrying the ML confidence and the human decision.
- `audit_trail` — an append-only, hash-chained log of every governance action.

The entire product value is: **many `material` rows → one `material_group` row → one national code.** Everything else is supporting cast.

---

## 2. Root-cause diagnosis

The codebase does not have a hundred unrelated bugs. It has **six systemic defects**, and most of the visible symptoms are downstream of them. Fix these six and the "bugs everywhere" feeling largely evaporates.

### RC-1 — The identity model is inert. Every material in a category collapses into one group. *(Severity: critical)*

`MaterialGroup.attribute_signature` is `SHA-256` of the material's extracted attributes, and the column is `UNIQUE NOT NULL`. The signature is built in `HarmonizationService.extractAttributesMap()` from four entity fields:

```java
// HarmonizationService.java:273-279
if (m.getExtractedDimension() != null) map.put("dimension", ...);
if (m.getExtractedGrade() != null) map.put("grade", ...);
if (m.getExtractedStandardCode() != null) map.put("standard", ...);
if (m.getExtractedMaterialType() != null) map.put("material_type", ...);
```

**Those four columns are never written anywhere in the codebase.** Grep confirms: `extracted_dimension` appears in `V1__initial_schema.sql`, in `Material.java` as a field, and in the getter above. There is no `setExtractedDimension(...)` call anywhere. The Python service extracts these attributes but the result is never persisted back.

Consequence chain:
1. `extractAttributesMap()` always returns `{}`.
2. `computeAttributeSignature("PIPE", {})` always returns `SHA-256('{"__category":"PIPE"}')` — a single constant per category.
3. `attribute_signature` is `UNIQUE`, so the second PIPE ever ingested finds the existing group and joins it.
4. **Every pipe in the country ends up in one group. Every valve in another. Eight groups total, one per seeded category.**
5. Dedup rate, rate-contract candidates, price variance, the catalog, and the review queue are all computed from these groups, so all of them are meaningless.

This is the single most important defect in the repository.

Compounding it: `HarmonizationService` line 189 passes `Collections.emptyMap()` explicitly for the "novel specification" path, which guarantees the collapse even if attributes were populated.

### RC-2 — The governance loop never closes. No national code is ever minted. *(Severity: critical)*

Minting lives in `GovernanceService.mintGroup()`, reachable only via `POST /api/groups/{id}/mint`. Three independent blockers:

1. **No UI calls it.** `frontend/src/services/api.js` has no method that touches `/api/groups`. Grep returns zero hits. There is no button, anywhere, that mints a code.
2. **The role gate is `SENIOR_REVIEWER` only** (`SecurityConfig.java:73`, and again inside `mintGroup`).
3. **The two-person rule is unsatisfiable in the seed data.** `mintGroup` rejects the mint if any confirmed mapping was reviewed by the same user who is minting — but only `SENIOR_REVIEWER` may mint, and there is exactly one `SENIOR_REVIEWER` in `V2__seed_data.sql`. If that user also confirmed, nobody can mint.

Consequence chain: `material_group.status` never becomes `ACTIVE` → `CodeController.searchCodes()` filters on `"ACTIVE".equals(status)` → **the unified catalog is permanently empty** → `AnalyticsService.getDashboardStats()` iterates `findByStatus("ACTIVE")`, gets an empty list → **`duplicatesEliminated = 0`, `dedupRate = 0.0`, `savingsLakhs = 0.0` forever.**

The dashboard showing all zeroes is not a dashboard bug. It is this.

### RC-3 — The role/route/endpoint matrix is internally inconsistent. *(Severity: critical — this is the "auth is broken" symptom)*

Three places independently decide what a role can do, and they disagree:

| Surface | File |
|---|---|
| Server authorization | `SecurityConfig.java:64-79` |
| Client route guards | `App.jsx` `RequireRole` blocks |
| Navigation visibility | `Sidebar.jsx:20-23` |

Concrete mismatches, all of which produce a 403 or a blank screen in normal use:

| # | What the user does | What happens | Cause |
|---|---|---|---|
| 1 | Any non-admin logs in | Lands on `/dashboard`, immediately 403 | `App.jsx:69` redirects `/` → `/dashboard` for everyone; `SecurityConfig:67` gates `/api/dashboard/**` to `ADMIN` |
| 2 | Operator clicks "Compare" | 403 | Sidebar shows `/compare` to all; `SecurityConfig:75` gates `/api/harmonization/compare` to REVIEWER/SENIOR/ADMIN |
| 3 | Reviewer clicks "Ingest" | 403 | Sidebar shows `/ingest` to all; `SecurityConfig:76` gates `/api/materials/**` to OPERATOR/ADMIN |
| 4 | Operator opens Catalog and hits Export | 403 on master export | `SecurityConfig:77` gates `/api/export/catalog` to SENIOR/ADMIN; CatalogView offers it to everyone |
| 5 | Reviewer opens a published code | Sees zero member materials | `CodeController.java:158` — `if ("REVIEWER".equals(viewer.getRole())) continue;` silently skips every member |
| 6 | Admin disables a user | User can still log in | `UserPrincipal.isEnabled()` hardcodes `return true`; the `active` column is never consulted |

Plus two structural problems: CORS is configured **twice** (`SecurityConfig.corsConfigurationSource()` with `allowedOriginPatterns("*")` **and** `CorsConfig` as a `WebMvcConfigurer`), and `JwtTokenProvider` ships a hardcoded default secret that will be used silently if `JWT_SECRET` is unset.

### RC-4 — The Damm checksum is mathematically invalid. *(Severity: high — it is a headline claim and it is false)*

`DammAlgorithm.java:27-38` builds its "totally anti-symmetric quasigroup" as:

```java
QUASIGROUP[i][j] = (perm[i] * 7 + j * 13 + (i ^ j)) % BASE;
```

A Damm check character requires the table to be a **Latin square** with a **zero diagonal** and **total anti-symmetry**. This table is none of those. Verified numerically:

| Property required by Damm | Actual |
|---|---|
| Every row is a permutation of 0..35 | **False** |
| Every column is a permutation of 0..35 | **False** |
| Diagonal is all zeros | **False** (starts `7, 12, 3, 8, 35, 4, ...`) |
| Anti-symmetry violations | **2,812** |

Measured error-detection over 20,000 random `NUMM-40-14-07-NNNNNN` codes:

| Error class | Claimed in docs | Measured |
|---|---|---|
| Single-character substitution | 100% | **72.2%** |
| Adjacent transposition | 100% | **88.8%** |

`DammAlgorithmTest` passes 5/5 because it only tests two hand-picked corruptions that happen to be caught. The tests do not test the property being claimed.

Secondary defect: `validate()` splits on the *last* hyphen with no format check, so `NUMM-40-14-07-000042` (no check char) is parsed as body `NUMM-40-14-07-00004` + check `2` and evaluated instead of rejected.

### RC-5 — The audit chain is weaker than advertised, and a migration rewrites it. *(Severity: high)*

Three problems:

1. **`old_value` is not hashed.** `AuditService.computeRowHash()` hashes `prevHash | userId | action | entityType | entityId | timestamp | newValue`. An attacker can rewrite any `old_value` in the table and `verifyChainIntegrity()` reports `valid: true`.
2. **`V3__repair_audit_hash_precision.sql` recomputes every `row_hash` and `prev_hash` in a `DO $$` loop.** A tamper-evident ledger that ships with a migration whose job is to make tampered-looking rows verify again is not tamper-evident. Any judge who opens that file has your integrity claim.
3. **The append is racy.** `logEvent()` does `findTop1ByOrderByAuditIdDesc()` then `save()` with no lock. Two concurrent requests read the same head and write two rows with the same `prev_hash` — the chain forks and `verifyChainIntegrity()` fails permanently. Bulk CSV ingest runs on a 4-thread pool, so this is reachable in normal operation.

Also: `verifyChainIntegrity()` loads the entire table with `findAllByOrderByAuditIdAsc()` into a `List`.

### RC-6 — Confidence means the wrong thing, and grouping races itself. *(Severity: high)*

**Confidence semantics.** `inference.py` returns `confidence = float(proba[pred_idx])` — the probability of the *predicted label*, whatever that label is. `HarmonizationService.java:134` reads it as if it were match strength:

```java
double score = topMatch.getConfidence();
String tier = ... score >= 0.85 ? "HIGH" : ...
```

So a candidate the model is 95% sure is **`NOT_A_MATCH`** is stored with `confidence_score = 0.95, confidence_tier = "HIGH"` and surfaced in the review queue as a high-confidence match. `bulkApproveHighConfidence()` will then batch-approve it.

**Grouping race.** `createOrFindGroupForMaterial()` does `findByAttributeSignature()` then `save()` against a `UNIQUE` column, with no lock and no conflict handling. `HarmonizationJobService` runs harmonization on a `newFixedThreadPool(4)`, and `POST /harmonization/harmonize-all` runs it again on the request thread. Concurrent ingest reliably throws `DataIntegrityViolationException`.

**Non-deterministic candidates.** `findCandidatesByCategory` applies `Pageable.of(0, 50)` with **no `ORDER BY`**. Postgres is free to return a different 50 rows each run, so the same input produces different harmonization output across runs. This alone makes the demo unreliable.

---

## 3. Full defect register

Everything below is verified against the source. Fix-in column maps to §6 work packages.

### 3.1 Backend — correctness

| ID | File:line | Defect | Fix in |
|---|---|---|---|
| B-01 | `HarmonizationService.java:273` | `extracted_*` columns never populated → signature always constant → category collapse (RC-1) | WP1 |
| B-02 | `HarmonizationService.java:189,203` | Novel-item path passes `Collections.emptyMap()` as attributes | WP1 |
| B-03 | `V1__initial_schema.sql:83` | `attribute_signature VARCHAR(64) UNIQUE NOT NULL` makes an incomplete signature a hard merge key | WP1 |
| B-04 | `GovernanceService.java:189-200` | `mintGroup` unreachable from UI; unsatisfiable two-senior rule (RC-2) | WP4 |
| B-05 | `GovernanceService.java:52-62` | COI check requires `reviewer.getCpse() != null`, but `V2__seed_data.sql` sets every reviewer's `cpse_id` to `NULL` → the barrier can never fire | WP4 |
| B-06 | `HarmonizationService.java:134` | `confidence` is P(label), used as P(match) (RC-6) | WP3 |
| B-07 | `HarmonizationService.java:254` | `createOrUpdateMapping` reuses the active mapping and resets `status = "PENDING"`, silently reverting a `CONFIRMED` human decision with no audit entry | WP4 |
| B-08 | `HarmonizationService.java:228-247` | Find-then-insert race on `attribute_signature` unique index | WP3 |
| B-09 | `MaterialRepository.java:21-24` | `findCandidatesByCategory` / `findCandidatesAll` paginate without `ORDER BY` → non-deterministic results | WP3 |
| B-10 | `AnalyticsService.java:177` | `spread.divide(min, 4, HALF_UP)` throws `ArithmeticException` when `min` is `0` | WP6 |
| B-11 | `AnalyticsService.java:170` | `cpsePriceMap.put(cpseName, price)` silently overwrites when one CPSE has two materials in a group | WP6 |
| B-12 | `AuditService.java:135` | `old_value` excluded from `row_hash` (RC-5.1) | WP5 |
| B-13 | `AuditService.java:36` | Racy chain head read (RC-5.3) | WP5 |
| B-14 | `V3__repair_audit_hash_precision.sql` | Migration rewrites the whole hash chain (RC-5.2) | WP5 |
| B-15 | `DammAlgorithm.java:27-38` | Invalid quasigroup; 72%/89% not 100%/100% (RC-4) | WP2 |
| B-16 | `DammAlgorithm.java:78-92` | `validate()` accepts malformed codes by splitting on the last hyphen without a format check | WP2 |
| B-17 | `UserPrincipal.java:80` | `isEnabled()` hardcoded `true`; `user.active` never enforced | WP7 |
| B-18 | `CodeController.java:158` | Reviewers see zero group members (`if ("REVIEWER"...) continue;`) | WP7 |
| B-19 | `CodeController.java:156` | Unchecked cast of `authentication.getPrincipal()` to `UserPrincipal` inside a loop → `ClassCastException` / `NullPointerException` on any unauthenticated path | WP7 |
| B-20 | `MaterialController.java:70` | `findByCpse_CpseId(currentUser.getCpseId())` with a null `cpseId` returns everything or throws, depending on role data | WP7 |
| B-21 | `GovernanceService.java:220` | `supersedeMapping` sets `status = "PENDING"` then calls `decideMapping`, which re-runs the COI check against the admin — an admin superseding an ONGC item while holding an ONGC `cpse_id` is blocked by its own override path | WP4 |
| B-22 | `HarmonizationService.java:196` | When `matches` is empty the result keeps `confidenceScore = null` on the DTO but the mapping is written with `1.0 / HIGH` — a fabricated perfect score for "we found nothing" | WP3 |

### 3.2 Backend — performance & scale

| ID | File:line | Defect | Fix in |
|---|---|---|---|
| P-01 | `AnalyticsService.java:96` | `materialRepository.findAll()` loaded into memory for category distribution | WP6 |
| P-02 | `AnalyticsService.java:88-93` | N+1: one `findActiveByMaterialId` query per material per CPSE | WP6 |
| P-03 | `AnalyticsService.java:118,148` | `groupRepository.findAll()` then one mapping query per group | WP6 |
| P-04 | `CodeController.java:89` | `groupRepository.findAll()` then filter in Java for catalog search | WP6 |
| P-05 | `HarmonizationController.java:61` | `/harmonize-all` is synchronous over every unmatched material, each with an HTTP round-trip to Flask (10s read timeout), behind a 12s client `AbortController` → guaranteed timeout past ~10 materials | WP3 |
| P-06 | `HarmonizationJobService.java:20` | `Executors.newFixedThreadPool(4)` is never shut down → thread leak on context close; no `@PreDestroy` | WP3 |
| P-07 | `MaterialController.java:249` | `jobService.processAsync()` fires before the enclosing request completes; the async thread may query a job row that is not yet visible | WP3 |
| P-08 | `MaterialRepository.java:27-37` | `findUnmatched` / `countUnmatched` use `NOT IN (subquery)` instead of `NOT EXISTS` | WP6 |
| P-09 | `AuditService.java:70` | `verifyChainIntegrity` loads the entire `audit_trail` table into a `List` | WP5 |
| P-10 | `PythonMatchingClient.java` | One HTTP call per material during bulk ingest; no batch endpoint | WP3 |

### 3.3 Matching service

| ID | File | Defect | Fix in |
|---|---|---|---|
| M-01 | `requirements.txt` | Every dependency is `>=`, unpinned. The committed models were serialized by **scikit-learn 1.8.0** (verified in the joblib headers). A fresh `docker build` on a day when 1.9 ships will unpickle against the wrong version and fail or silently misbehave. | WP0 |
| M-02 | `app.py` | No `/extract-attributes` endpoint, so Java cannot persist extracted attributes (blocks RC-1 fix) | WP1 |
| M-03 | `inference.py:120` | `confidence` is P(predicted label), not P(match) (RC-6) | WP3 |
| M-04 | `app.py` | No batch endpoint for `find-matches` (P-10) | WP3 |
| M-05 | `models/classifier.joblib` | 79 MB binary committed to git. Bloats every clone and every Docker build context. | WP0 |
| M-06 | `inference.py:150` | `find_matches` returns candidates even when every one is `NOT_A_MATCH`; the sort key demotes them to `-1` but they are still in the top-k the caller consumes | WP3 |
| M-07 | `app.py:28` | `sys.path.insert` hacks in both `app.py` and `inference.py`; the package layout is not importable as a package | WP0 |
| M-08 | Training data | The model is trained entirely on `data/generated/pairs.csv` (synthetic). Test macro-F1 is 0.84 with `VARIANT` recall at 0.62 and `NEEDS_REVIEW` recall at 0.64 — both weak classes. This is acceptable but **must be stated honestly**, not rounded up to "94.1% accuracy". | WP11 |

### 3.4 Frontend

| ID | File | Defect | Fix in |
|---|---|---|---|
| F-01 | `App.jsx:69` | `/` redirects to `/dashboard` for all roles (RC-3 #1) | WP8 |
| F-02 | `context/PersonaContext.jsx` | 67 lines of dead code. `PersonaProvider` is never mounted. | WP8 |
| F-03 | `components/Navbar.jsx` | Dead. Imported nowhere. Calls `usePersona()`, which would throw if it were ever rendered. | WP8 |
| F-04 | `components/BulkUploadView.jsx` | Dead. Imported nowhere. Superseded by `IngestionWizard`. | WP8 |
| F-05 | `services/api.js` | No export methods and no mint method; `CatalogView` hand-rolls its own `fetch` with a duplicated token read | WP8 |
| F-06 | `CatalogView.jsx:114` | `alert("Export failed: ...")` — raw browser alert in a government UI | WP9 |
| F-07 | `CatalogView.jsx:102` | `URL.createObjectURL` without a matching `revokeObjectURL` → blob leak per download | WP9 |
| F-08 | `ReviewQueueView.jsx:43` | Manual `useState` + `useEffect` fetching while `DashboardView`/`CatalogView` use TanStack Query — two different data layers in one app | WP9 |
| F-09 | `App.jsx:35` | `AppLayout` declares `const [pendingCount, setPendingCount] = useState(0)` and never sets it; the sidebar review badge is permanently absent | WP9 |
| F-10 | `i18n.js` | ~60 distinct `t()` keys are referenced across components against a partial dictionary. A half-Hindi UI is worse than an English one for this audience. | WP9 |
| F-11 | `index.css` | 1,538 lines, 49 custom properties, no layering. Unmaintainable. | WP9 |
| F-12 | `App.css` | 184 lines, largely Vite scaffold leftovers | WP8 |
| F-13 | `assets/react.svg`, `assets/vite.svg`, `assets/hero.png` | Scaffold assets still shipped | WP8 |
| F-14 | `LoginView.jsx:186-236` | Demo credentials hardcoded in the component; they will drift from `V2__seed_data.sql` the moment the seed changes | WP7 |
| F-15 | `vite.config.js` vs `nginx.conf` | Dev proxies `/api` to `localhost:8080`; prod proxies to `backend:8080`. Fine, but there is no single place documenting it, and `api.js` assumes a same-origin `/api` in both. Document it. | WP0 |

### 3.5 Infrastructure & repository hygiene

| ID | Location | Defect | Fix in |
|---|---|---|---|
| I-01 | `docker-compose.yml:11` | Postgres published on host `5432`, which collides with a native Postgres install on the dev machine. Use `5433:5432`. | WP0 |
| I-02 | `docker-compose.yml` | `backend` has no healthcheck; `frontend` uses bare `depends_on` so nginx can start before the API is reachable | WP0 |
| I-03 | `pom.xml` | No `spring-boot-starter-actuator`, so there is no health endpoint to check even if one were configured | WP0 |
| I-04 | `SecurityConfig.java:78` | `.anyRequest().denyAll()` will also block any actuator endpoint added later. Permit `/actuator/health` explicitly. | WP0 |
| I-05 | `material-master-backend/data/numm-demo.*.db` | H2 database files committed to the repository | WP0 |
| I-06 | `src/main/resources/data.sql`, `schema.sql` | Dead files that coexist with Flyway and confuse the reader (they do not execute against Postgres, but nothing says so) | WP0 |
| I-07 | Repo-wide | Branding is split: containers and Python say **SAMAAN**, the frontend says **NUMM**, seed emails say `@samaan.gov.in`. Pick one. | WP0 |
| I-08 | `matching-service/data/generated/pairs.csv` | 15 MB generated artifact committed | WP0 |

---

## 4. What stays, unchanged

Not everything here is broken. **Do not rewrite these.** They are good and they carry the technical story.

- **`matching-service/src/preprocessing/attribute_extraction.py`** — the regex attribute extractor. Ordered patterns, material-type extraction. This is real domain work.
- **`matching-service/src/features/feature_engineering.py`** — the 17-feature vector (TF-IDF word + char cosine, rapidfuzz ratios, structured attribute agreement/conflict/missing counts). The feature importances in `training_results.json` confirm the structured features carry real signal (`frac_agree` 0.111, `n_attrs_conflict` 0.101, `any_type_conflict` 0.084 — collectively outweighing raw text similarity).
- **`matching-service/src/matching/inference.py`** two-stage retrieval — category blocking, then one vectorized TF-IDF pass, then expensive scoring only on the shortlist. Architecturally correct.
- **The `explanation` structure** (`checks` / `warnings` / `conflicts`) — attribute-level, derived from real comparisons, never fabricated. This is the explainability story and it is genuine.
- **The Flyway migration discipline** (V1 → V1_1 → V2 → V3) — keep migrating forward, never edit an applied migration.
- **The `material` / `material_group` / `material_mapping` three-table shape.** It is the right model. The bug is in how the group key is computed, not in the tables.
- **`uq_active_mapping_per_material`** — the partial unique index on `material_mapping(material_id) WHERE status != 'REJECTED'`. Correct and load-bearing.
- **`GovernanceService`'s separation of mapping decision from group publication.** The concept is right. The implementation (§RC-2) is what needs work.

---

## 5. Target design decisions

Each decision states the problem, the chosen design, and why the alternatives were rejected — so you can explain it and so the agent does not re-litigate it.

### D1 — Group identity: signature as a fast path, ML as the fallback, never a blind merge key

**Problem:** a SHA-256 over a possibly-empty attribute map is used as a `UNIQUE` merge key (RC-1).

**Design:**

1. Add `POST /extract-attributes` to Flask (batch, accepts a list, returns a list of `{material_id, category, attributes: {...}}`).
2. At ingest (single and bulk), the backend calls it and persists the result. Add a new column rather than fighting the four fixed ones:
   ```sql
   -- V4__attribute_identity.sql
   ALTER TABLE material ADD COLUMN extracted_attributes JSONB;
   ALTER TABLE material ADD COLUMN attributes_extracted_at TIMESTAMP;
   ```
   Keep `extracted_dimension`, `extracted_grade`, `extracted_standard_code`, `extracted_material_type` and populate them too (they are cheap, indexable, and readable in a demo).
3. Introduce **signature completeness**. Each category declares its `identity_critical` attribute set (this already exists in `matching-service/src/data_generation/schemas.py` — expose it via `GET /schema/{category}` and cache it in the backend, or mirror it in a `material_category.identity_critical_keys` JSONB column seeded by migration; prefer the endpoint, single source of truth).
   - If **all** identity-critical keys for the category are present and non-blank → compute the signature. Set `signature_complete = true`.
   - If **any** is missing → `attribute_signature = NULL`, `signature_complete = false`.
4. Change the constraint:
   ```sql
   ALTER TABLE material_group DROP CONSTRAINT material_group_attribute_signature_key;
   ALTER TABLE material_group ALTER COLUMN attribute_signature DROP NOT NULL;
   ALTER TABLE material_group ADD COLUMN signature_complete BOOLEAN NOT NULL DEFAULT false;
   CREATE UNIQUE INDEX uq_group_signature_complete
       ON material_group (attribute_signature)
       WHERE attribute_signature IS NOT NULL;
   ```
5. **Grouping decision order** in `HarmonizationService.harmonizeMaterial()`:

   ```
   Step 1  Compute the material's signature.
           If complete AND a group exists with that signature
             → join it. Set mapping.match_basis = 'DETERMINISTIC_SIGNATURE',
               match_probability = 1.0, tier = HIGH.
               (Two independently submitted records with identical
                identity-critical attributes are the same item by definition.)

   Step 2  Otherwise, run ML candidate retrieval (blocked by category,
           ordered deterministically — see D4).
           If top match's relationship ∈ {EXACT_DUPLICATE, NEAR_DUPLICATE}
             AND match_probability ≥ 0.60
             AND the matched material has an active mapping
             → propose joining that material's group.
               match_basis = 'ML_PROPOSED'.

   Step 3  If relationship ∈ {FUNCTIONALLY_EQUIVALENT, VARIANT}
             AND match_probability ≥ 0.60
             → create a NEW group, and record a group_relation row
               linking it to the matched group. These are related
               items, not the same item. Never merge them.

   Step 4  Otherwise → create a new group.
               match_basis = 'NOVEL'. match_probability = the model's
               actual P(match) against the best candidate — NOT 1.0.
               tier derived from that. (Fixes B-22.)
   ```

6. Add `match_basis VARCHAR(30)` to `material_mapping`. The review screen shows it. "This was matched deterministically on identical identity-critical attributes" and "This was proposed by the model at 78%" are different things and a reviewer must be able to tell them apart.

**Rejected:** keeping the unique constraint and just populating the attributes. Rejected because a material whose description omits a size still produces *some* signature, and any two such materials would still hard-merge. Completeness gating is the actual fix.

### D2 — National code format and checksum: ISO 7064 MOD 37,36

**Problem:** the Damm table is not a quasigroup (RC-4).

**Design:** replace `DammAlgorithm` with `Iso7064Mod3736`.

Format is unchanged and remains `NUMM-SS-FF-CC-NNNNNN-K`:
- `SS` — UNSPSC segment (2 digits), from `material_category.code_segment`
- `FF` — UNSPSC family (2 digits)
- `CC` — UNSPSC class (2 digits)
- `NNNNNN` — zero-padded serial from the `numm_serial_seq` Postgres sequence
- `K` — one base-36 check character

Algorithm (ISO/IEC 7064:2003, MOD 37,36 — the "hybrid system" variant):

```java
public static char computeCheckChar(String body) {
    // body: alphanumerics only, delimiters stripped
    int p = 36;                          // M = 36, M+1 = 37
    for (char c : body.toCharArray()) {
        int a = valueOf(c);              // 0-9 -> 0-9, A-Z -> 10-35
        int s = (p % 37) + a;
        int m = s % 36;
        p = (m == 0 ? 36 : m) * 2;
    }
    int checkValue = (37 - (p % 37)) % 36;
    return charOf(checkValue);
}
```

Why this and not "fix the Damm table": ISO 7064 MOD 37,36 is a **published international standard** you can cite in the presentation, it is defined over exactly the alphabet you need (0-9 + A-Z), and it provably detects 100% of single-character substitutions and 100% of adjacent transpositions. A hand-rolled order-36 quasigroup is a research problem; this is a lookup in a spec.

**Validation must be format-gated first:**

```java
private static final Pattern CANONICAL =
    Pattern.compile("^NUMM-\\d{2}-\\d{2}-\\d{2}-\\d{6}-[0-9A-Z]$");

public static boolean validate(String code) {
    if (code == null) return false;
    String c = code.trim().toUpperCase();
    if (!CANONICAL.matcher(c).matches()) return false;   // fixes B-16
    String body  = c.substring(0, c.length() - 2);        // drop "-K"
    char expected = c.charAt(c.length() - 1);
    return computeCheckChar(stripDelimiters(body)) == expected;
}
```

**The test must test the property, not two examples.** See Gate WP2.

Keep `PROV-YYYY-NNNNNN` for provisional refs, unchanged, with no check character (it is internal and temporary — say so in the UI).

### D3 — Governance: a real, demonstrable four-eyes loop

**Problem:** minting is unreachable and the COI barrier cannot fire (RC-2, B-05).

**Design — two distinct human steps, both in the UI:**

```
  Operator ingests                       Group: PROPOSED, no code
        │                                Mapping: PENDING
        ▼
  ── Step 1: TECHNICAL SIGN-OFF ──────────────────────────────
  REVIEWER (or SENIOR_REVIEWER) confirms the mapping.
  Blocked if reviewer.cpse_id == submitting material's cpse_id
  → 409 with an explicit "conflict of interest" body.
        │                                Mapping: CONFIRMED
        ▼
  ── Step 2: PUBLICATION ──────────────────────────────────────
  SENIOR_REVIEWER publishes the group from /publish.
  Blocked if the publisher is the same user who confirmed
  the mapping in step 1 → 409.
  On success: mint the national code inside the same transaction.
        │                                Group: ACTIVE, code minted
        ▼
  Code appears in the national catalog, is exportable,
  and counts toward dedup / savings analytics.
```

Required supporting changes:

- **Seed reviewers with CPSE affiliations** so the COI barrier is demonstrable in both directions:

  | Email | Role | `cpse_id` |
  |---|---|---|
  | `admin@numm.gov.in` | ADMIN | NULL |
  | `senior.reviewer@numm.gov.in` | SENIOR_REVIEWER | NULL (central committee) |
  | `reviewer.mech@numm.gov.in` | REVIEWER | **IOCL** |
  | `reviewer.elec@numm.gov.in` | REVIEWER | **GAIL** |
  | `operator@ongc.co.in` … `operator@sail.in` | OPERATOR | own CPSE |

  Now: the mechanical reviewer (IOCL) can approve an ONGC pipe — and is blocked from approving an IOCL pipe. Both halves of the rule are visible in a two-minute demo.

- **Drop the "a different senior reviewer must mint" rule from `mintGroup`.** Replace with "the publisher must not be the user who confirmed the underlying mapping." With one senior reviewer seeded plus reviewers doing step 1, this is satisfiable.
- **Add `GET /api/groups/publishable`** returning groups in `PROPOSED` with at least one `CONFIRMED` mapping. This backs the new `/publish` screen.
- **`POST /api/groups/{id}/mint` gets a UI.** `api.js` gains `getPublishableGroups()` and `publishGroup(groupId, notes)`.
- **Fix B-07:** `createOrUpdateMapping` must never overwrite a mapping whose status is `CONFIRMED` or `REJECTED`. If re-harmonization would change a decided mapping, it must instead create a new `PENDING` mapping and mark the old one `SUPERSEDED`, with an audit entry. If the partial unique index blocks that, the old row must be set to `SUPERSEDED` first in the same transaction — and note `uq_active_mapping_per_material` currently permits `SUPERSEDED`, so **widen the index predicate to `WHERE status IN ('PENDING','CONFIRMED')`** in migration V4.
- **Fix B-21:** `supersedeMapping` must not route through `decideMapping`'s COI check. Extract the status-transition logic into a private method that both call, with COI enforced only on the reviewer path.

### D4 — Confidence: separate match probability from label probability

**Problem:** RC-6.

**Design:**

Flask (`inference.py`) returns **both**:

```python
DUPLICATE_CLASSES = {"EXACT_DUPLICATE", "NEAR_DUPLICATE", "FUNCTIONALLY_EQUIVALENT"}

match_probability = sum(
    p for cls, p in zip(le.classes_, proba) if cls in DUPLICATE_CLASSES
)

return {
    "predicted_relationship": predicted_label,
    "label_probability":  round(float(proba[pred_idx]), 4),   # how sure of the label
    "match_probability":  round(float(match_probability), 4), # how sure it's the same item
    "confidence_tier":    tier_from(match_probability),
    "class_probabilities": {...},
    "explanation": {...},
}
```

Keep `confidence` in the response as an alias of `match_probability` for one release so nothing breaks, then remove it.

Tiering is on `match_probability` only:

| Tier | Band | Meaning in the UI |
|---|---|---|
| HIGH | ≥ 0.85 | Eligible for streamlined/bulk approval |
| MEDIUM | 0.60 – 0.85 | Normal review |
| LOW | < 0.60 | Review with the conflicts panel expanded by default |

**Bulk approval additionally requires zero `identity_critical` conflicts in the explanation**, not just a HIGH tier. A model can be confident and wrong; an attribute conflict on an identity-critical field is a hard stop. Enforce this server-side in `bulkApproveHighConfidence`, not in the UI.

`match_basis = 'DETERMINISTIC_SIGNATURE'` bypasses tiering entirely and is always eligible.

**Determinism (B-09):** add `ORDER BY m.materialId ASC` to `findCandidatesByCategory` and `findCandidatesAll`. Same input, same output, every run. This is non-negotiable for a live demo.

### D5 — Audit chain: hash everything, serialize appends, never rewrite

**Problem:** RC-5.

**Design:**

1. **Hash payload** becomes, in this exact order, `|`-joined:
   `prev_hash | user_id | action | entity_type | entity_id | timestamp(ISO, µs) | old_value | new_value`
   Add `old_value` (B-12). Define `null` as the empty string for every field. Document the payload format in a comment above `computeRowHash` — it is a wire format now.

2. **Serialize the append.** Add a single-row anchor table and take a row lock:
   ```sql
   -- V5__audit_chain_head.sql
   CREATE TABLE audit_chain_head (
       id       SMALLINT PRIMARY KEY DEFAULT 1,
       head_hash VARCHAR(64) NOT NULL,
       CONSTRAINT one_row CHECK (id = 1)
   );
   INSERT INTO audit_chain_head (id, head_hash)
   SELECT 1, COALESCE(
       (SELECT row_hash FROM audit_trail ORDER BY audit_id DESC LIMIT 1),
       repeat('0', 64));
   ```
   `logEvent` does `SELECT head_hash FROM audit_chain_head WHERE id = 1 FOR UPDATE`, appends, updates `head_hash`. Concurrent writers now queue instead of forking (B-13).

3. **Make the table append-only at the database level:**
   ```sql
   CREATE OR REPLACE FUNCTION audit_trail_is_append_only()
   RETURNS TRIGGER AS $$
   BEGIN
       RAISE EXCEPTION 'audit_trail is append-only (attempted %)', TG_OP;
   END;
   $$ LANGUAGE plpgsql;

   CREATE TRIGGER trg_audit_no_update
       BEFORE UPDATE OR DELETE ON audit_trail
       FOR EACH ROW EXECUTE FUNCTION audit_trail_is_append_only();
   ```
   This is a much stronger and much more demonstrable claim than a hash chain alone: *the database itself refuses the write.* It is also three lines a judge can read.

4. **Delete the chain-rewrite from V3.** Do not edit the applied V3 file (Flyway checksums). Instead add `V6__retire_chain_rewrite.sql` that does nothing but carry a comment explaining V3 is historical and must never be re-run, and add a `CHECK`-style guard in `docs/` noting it. For a fresh database, V3's loop over an empty table is a no-op, which is fine.

5. **Paginate verification.** `verifyChainIntegrity()` streams with `Pageable` in blocks of 1,000, carrying `expectedPrevHash` across pages (P-09). Return `{valid, chainLength, genesisHash, headHash, verifiedAt}` plus, on failure, `{brokenAtAuditId, reason}`.

6. **Add a tamper demo endpoint under a `demo` Spring profile only:** `POST /api/demo/tamper-audit/{auditId}` which disables the trigger, mutates `old_value`, re-enables it. This lets you show the verifier going red on stage without a database console. It must not exist in the default profile — guard with `@Profile("demo")`.

### D6 — Authorization: one matrix, three enforcement points derived from it

**Problem:** RC-3.

**Design.** Define the matrix once, in this document, and make all three surfaces match it exactly.

**Roles:** `OPERATOR`, `REVIEWER`, `SENIOR_REVIEWER`, `ADMIN`. No others.

**Endpoint matrix** — implement in `SecurityConfig` in exactly this order (Spring matches first-wins, so specific rules precede wildcards):

| Order | Pattern | Method | Roles |
|---|---|---|---|
| 1 | `/api/auth/login`, `/error`, `/actuator/health` | ALL | permitAll |
| 2 | `/api/auth/demo-accounts` | GET | permitAll *(only registered under the `demo` profile)* |
| 3 | `/api/auth/me`, `/api/auth/logout` | ALL | authenticated |
| 4 | `/api/admin/**` | ALL | ADMIN |
| 5 | `/api/analytics/assumptions/**` | PUT | ADMIN |
| 6 | `/api/analytics/**`, `/api/dashboard/**` | GET | SENIOR_REVIEWER, ADMIN |
| 7 | `/api/mappings/audit/**` | GET | SENIOR_REVIEWER, ADMIN |
| 8 | `/api/mappings/*/supersede` | POST | SENIOR_REVIEWER, ADMIN |
| 9 | `/api/mappings/bulk-approve` | POST | REVIEWER, SENIOR_REVIEWER |
| 10 | `/api/mappings/*/approve`, `/reject`, `/edit` | POST | REVIEWER, SENIOR_REVIEWER |
| 11 | `/api/mappings/**` | GET | REVIEWER, SENIOR_REVIEWER, ADMIN |
| 12 | `/api/groups/publishable` | GET | SENIOR_REVIEWER, ADMIN |
| 13 | `/api/groups/*/mint` | POST | SENIOR_REVIEWER |
| 14 | `/api/codes/**` | GET | authenticated *(response is scoped per role — see below)* |
| 15 | `/api/harmonization/compare` | POST | **authenticated** *(widened — every role uses the sandbox)* |
| 16 | `/api/harmonization/harmonize-all` | POST | ADMIN |
| 17 | `/api/harmonization/**` | POST | OPERATOR, ADMIN |
| 18 | `/api/materials/**`, `/api/jobs/**` | ALL | OPERATOR, ADMIN |
| 19 | `/api/export/catalog` | GET | SENIOR_REVIEWER, ADMIN |
| 20 | `/api/export/**` | GET | OPERATOR, SENIOR_REVIEWER, ADMIN |
| 21 | `anyRequest()` | ALL | denyAll |

**Row-level scoping** (enforced in the service layer, never in the UI):

- `OPERATOR` sees only `material` rows where `cpse_id = principal.cpseId`. Already correct in `MaterialController`; make it uniform across `ExportService` and `CodeController`.
- `OPERATOR` viewing a published national code sees the canonical record in full, plus the member list **filtered to their own CPSE's legacy codes**. They must not see what IOCL calls the same item — that is commercially sensitive and it is the right product decision. Say this in the demo; it reads as maturity.
- `REVIEWER` sees the **full** member list (they are adjudicating a cross-CPSE merge and cannot do it blind). **Delete `CodeController.java:158`.** (B-18)
- `SENIOR_REVIEWER` and `ADMIN` see everything.

**Landing page per role** (fixes F-01 / RC-3 #1):

| Role | Lands on |
|---|---|
| OPERATOR | `/my-materials` |
| REVIEWER | `/review` |
| SENIOR_REVIEWER | `/review` |
| ADMIN | `/dashboard` |

Implement as a single exported function `defaultRouteFor(role)` in `src/auth/roles.js`, used by `App.jsx`'s index redirect **and** by `LoginView`'s post-login `navigate()`. One source of truth.

**Navigation visibility** derives from the same file:

```js
// src/auth/roles.js — the single source of truth for the client
export const NAV = [
  { to: "/dashboard",    key: "nav.dashboard", roles: ["SENIOR_REVIEWER", "ADMIN"] },
  { to: "/my-materials", key: "nav.myMaterials", roles: ["OPERATOR"] },
  { to: "/ingest",       key: "nav.ingest",    roles: ["OPERATOR", "ADMIN"] },
  { to: "/review",       key: "nav.review",    roles: ["REVIEWER", "SENIOR_REVIEWER", "ADMIN"] },
  { to: "/publish",      key: "nav.publish",   roles: ["SENIOR_REVIEWER"] },
  { to: "/catalog",      key: "nav.catalog",   roles: ["OPERATOR", "REVIEWER", "SENIOR_REVIEWER", "ADMIN"] },
  { to: "/compare",      key: "nav.compare",   roles: ["OPERATOR", "REVIEWER", "SENIOR_REVIEWER", "ADMIN"] },
  { to: "/audit",        key: "nav.audit",     roles: ["SENIOR_REVIEWER", "ADMIN"] },
  { to: "/admin/users",  key: "nav.adminUsers",roles: ["ADMIN"] },
];
```

`Sidebar` maps over `NAV`. `App.jsx` generates its `RequireRole` wrappers from `NAV`. A route cannot exist in one and not the other.

**Other auth fixes:**
- `UserPrincipal.isEnabled()` returns `user.getActive()` (B-17).
- Delete `config/CorsConfig.java` entirely; keep only `SecurityConfig.corsConfigurationSource()`, and replace `allowedOriginPatterns("*")` with an `app.cors.allowed-origins` property defaulting to `http://localhost:5173,http://localhost:3000`.
- `JwtTokenProvider` must **fail startup** if `jwt.secret` is absent and the active profile is not `dev` or `demo`. Remove the hardcoded default from `@Value`.
- Token lives in `sessionStorage` (already the case) — keep it, and note in the docs that a production deployment would use an httpOnly cookie plus CSRF tokens. Stating the gap is better than pretending it is not there.

### D7 — Quick role access for the hackathon

**Requirement:** instant per-role entry so a judge sees each view in seconds, without it looking like the old fake persona switcher.

**Design:** a real login, one click.

- New endpoint `GET /api/auth/demo-accounts`, registered **only under the `demo` Spring profile** (`@Profile("demo")` on the controller). Returns:
  ```json
  [{"label":"ONGC Operator","email":"operator@ongc.co.in","password":"operator123",
    "role":"OPERATOR","org":"ONGC","blurb":"Ingests ONGC's legacy catalog"}, ...]
  ```
  Because it is generated from the same seed data the backend uses, the buttons can never drift from reality (fixes F-14).
- `LoginView` fetches it. If the call 404s (production profile), the demo strip simply does not render. No dead UI.
- Clicking a card performs a **real `POST /api/auth/login`** with those credentials and gets a real JWT. There is no client-side role assumption anywhere.
- Each card shows the email and password in plain text under the button, so a judge can also type them.
- Switching roles = log out, log in. `logout()` must clear the TanStack Query cache (`queryClient.clear()`), which is what "context loss on profile switch" was actually about — stale cached data from the previous role bleeding into the next.

This satisfies "quick pages for each account for instant viewing of the roles" while being an honest auth system, which is the opposite of what the old `PersonaContext` did.

### D8 — Frontend visual direction

**The brief:** decent, navigable, appropriate for a government/SIH context, not high-end, no unnecessary detail, and — explicitly — **not looking AI-generated**.

"Looks AI-generated" is a real and specific failure mode. It means: purple/indigo gradients, glassmorphism, sparkle and rocket icons, animated counters, oversized hero sections, three-across marketing feature cards, emoji in headings, generic taglines ("Empowering the future of..."), and every list rendered as a card grid. The current `LoginView` has a hero with three capability cards and a `Sparkles` icon on the dashboard. That is the look to eliminate.

**Replace it with an administrative-system register:**

| Aspect | Decision |
|---|---|
| Reference points | India's own government service portals, GSTN, MCA21, ICEGATE. Dense, tabular, unglamorous, legible. |
| Typography | **IBM Plex Sans** (UI) + **IBM Plex Mono** (all codes, hashes, IDs) + **Noto Sans Devanagari** (Hindi). Self-host via `@fontsource` (add as pinned npm deps) — do not hotlink Google Fonts; the demo may run offline. |
| Type scale | 12 / 13 / 14 / 16 / 20 / 26 px. Nothing larger. Body is 14. |
| Palette | Exactly six: `--ink #16202C`, `--ink-muted #5A6B7D`, `--surface #FFFFFF`, `--canvas #F4F6F8`, `--line #D8DEE5`, `--accent #0B4F6C` (deep institutional teal-navy). Plus four status colours: `--ok #1C7A4A`, `--warn #9A6400`, `--danger #A62B2B`, `--info #24567A`. **No gradients anywhere.** |
| Contrast | Every text/background pair ≥ 4.5:1. Verify, don't assume. |
| Radius | 3px on everything. One value. |
| Shadows | One: `0 1px 2px rgba(22,32,44,.08)`. Used on the header and on modals only. Cards get a 1px border instead. |
| Spacing | 4px base scale: 4, 8, 12, 16, 24, 32. |
| Layout | 240px fixed sidebar, 52px header, content `max-width: 1180px`, 24px page padding. |
| Icons | `lucide-react`, 16px, `--ink-muted`, navigation and actions only. **Never decorative.** Banned from this codebase: `Sparkles`, `Rocket`, `Zap`, `Wand2`, `Star`. |
| Lists | **Tables, not card grids.** Catalog, review queue, audit log, materials, users — all tables with sticky headers. Cards only for the four dashboard KPI tiles. |
| Emoji | None. Not in the UI, not in headings, not in toasts. |
| Motion | Only `transition: background-color 120ms` on interactive elements. No counters that count up, no skeleton shimmer, no page transitions. |

**"No unnecessary detail" — delete these specific things:**

- The login hero: `login-eyebrow`, `login-intro-title`, the three `login-capability` blocks, `login-trust-note`, `login-support-note`. Keep: masthead, the sign-in form, the demo-account strip, a one-line footer.
- `Sidebar`'s `brand-tag` "concept badge", `brand-sub`, the `ministry-tag` and `footer-version` block. Keep the wordmark and the nav.
- `Header`'s `org-sector` parenthetical and the duplicated ministry text (it already appears in the masthead).
- The procurement **Assumptions** panel from every view except `/dashboard` for ADMIN.
- Every `kpi-sub` line that restates a number already shown in the tile.
- All decorative `<div className="...-icon-wrapper">` wrappers around KPI icons.

**Accessibility (keep — it is a genuine differentiator for this audience, and GIGW/WCAG is a real evaluation criterion for government software):**
- Skip link, already present. Keep and style it visible on focus.
- A visible 2px `--accent` focus ring on every interactive element. No `outline: none` without a replacement.
- Every table gets `<caption class="sr-only">`, `<th scope="col">`.
- Every async region gets `aria-live="polite"`; every error gets `role="alert"`.
- Full keyboard path through the whole demo — no mouse-only action.
- Keep the Hindi toggle **only if** WP9's key-parity gate passes. A half-translated government UI is worse than an English one.

### D9 — Screen inventory (final)

Eleven screens. Nothing else.

| Route | Roles | Purpose | Notes |
|---|---|---|---|
| `/login` | — | Sign in + demo account strip | Stripped per D8 |
| `/dashboard` | SENIOR_REVIEWER, ADMIN | 4 KPIs, rate-contract candidates table, price-variance table | No batch-harmonize button here; move to `/admin` actions |
| `/my-materials` | OPERATOR | **New.** The operator's own ingested rows with mapping status, national code once published, and an export button | This is the operator's home. It is the screen they actually need and it does not exist today. |
| `/ingest` | OPERATOR, ADMIN | CSV wizard: upload → column map → preview → submit → job progress | Keep `IngestionWizard`, restyle, remove the three pre-canned "benchmark" sample buttons down to one |
| `/review` | REVIEWER, SENIOR_REVIEWER, ADMIN | Pending queue, tier filter, SLA aging, bulk approve | Keep SLA aging — it is good |
| `/review/:id` | same | Side-by-side attribute parity, explanation panel, approve/reject/edit | Show `match_basis` prominently |
| `/publish` | SENIOR_REVIEWER | **New.** Groups with confirmed mappings awaiting code minting | Closes RC-2 |
| `/catalog` | all | Published national codes, search, export | Member list scoped per D6 |
| `/codes/:code` | all | Canonical record, members, related groups, checksum validator | |
| `/compare` | all | Pairwise ML sandbox | Requires widening endpoint auth (D6 row 15) |
| `/audit` | SENIOR_REVIEWER, ADMIN | Log table + "Verify chain integrity" action | Plus the tamper demo button under the `demo` profile |
| `/admin/users` | ADMIN | Provision operators and reviewers, assign categories | |

---

## 6. Work packages

Execute in order. Each has a **Gate** — a command whose output you must paste before moving on.

---

### WP0 — Make the build honest and reproducible

**Why first:** nothing else can be verified while the build is non-deterministic and the repo is full of artifacts.

**Tasks:**
1. Pin `matching-service/requirements.txt` exactly. The committed models were serialized with **scikit-learn 1.8.0** — pin that. Pin `numpy`, `scipy`, `pandas`, `joblib`, `RapidFuzz`, `flask`, `gunicorn`, `pytest` to the versions currently resolving in your environment, using `==`.
2. Add `matching-service/models/` and `matching-service/data/generated/` to `.gitignore`. Add `matching-service/scripts/train.py` invocation instructions to `matching-service/README.md` so models are reproducible. **Do not delete the committed models** — the demo needs them and there is no time to retrain. Add a note that they are tracked deliberately and why. (M-05, I-08)
3. Delete `material-master-backend/data/*.db`, `src/main/resources/data.sql`, `src/main/resources/schema.sql`. Add `data/` to `.gitignore`. (I-05, I-06)
4. Add `spring-boot-starter-actuator`. Expose only `health`. Permit `/actuator/health` in `SecurityConfig`. (I-03, I-04)
5. `docker-compose.yml`: change Postgres to `"5433:5432"`; add a `backend` healthcheck hitting `/actuator/health`; make `frontend.depends_on.backend.condition: service_healthy`. (I-01, I-02)
6. Restructure the Python package so `sys.path.insert` hacks disappear: add `__init__.py` files and import as `from src.matching.inference import ...`, or set `PYTHONPATH=/app` in the Dockerfile. Pick one and remove the hacks. (M-07)
7. **Unify branding on `NUMM`.** Rename `samaan-matching-service` → `numm-matching-service` in `app.py`. Change seed emails `@samaan.gov.in` → `@numm.gov.in`. Update `README.md`. Grep for `SAMAAN` case-insensitively and eliminate it. (I-07)
8. Write `docs/RUNBOOK.md`: how to start dev (vite proxy → 8080), how to start docker (nginx proxy → backend:8080), which ports, which profiles (`dev`, `demo`, `python-matching`), what each env var does. (F-15)

**Gate WP0:**
```bash
docker compose down -v && docker compose build --no-cache && docker compose up -d
sleep 60
docker compose ps                                   # all 4 healthy/running
curl -fsS http://localhost:5000/health
curl -fsS http://localhost:8080/actuator/health
curl -fsS -o /dev/null -w "%{http_code}\n" http://localhost:3000
git status --porcelain                              # clean
grep -ric samaan . --include="*.java" --include="*.py" --include="*.jsx" --include="*.yml" | grep -v ":0$"   # no output
```

---

### WP1 — Fix the identity model *(RC-1 — the most important WP in this document)*

**Tasks:**
1. Flask: add `POST /extract-attributes`. Body `{"materials":[{"material_id":1,"description":"...","specification":"...","category":"PIPE"}]}`. Response `{"results":[{"material_id":1,"category":"PIPE","attributes":{...},"identity_critical_present":true,"missing_identity_keys":[]}]}`. Reuse `preprocessing.attribute_extraction.extract_attributes` and `data_generation.schemas.CATEGORIES`. (M-02)
2. Flask: add `GET /schema/{category}` returning `{"identity_critical":[...], "variant_critical":[...], "all_fields":[...]}`.
3. Migration `V4__attribute_identity.sql`:
   - `ALTER TABLE material ADD COLUMN extracted_attributes JSONB, ADD COLUMN attributes_extracted_at TIMESTAMP;`
   - `ALTER TABLE material_group ADD COLUMN signature_complete BOOLEAN NOT NULL DEFAULT false;`
   - `ALTER TABLE material_mapping ADD COLUMN match_basis VARCHAR(30);`
   - Drop the unique constraint on `attribute_signature`, drop `NOT NULL`, create the partial unique index (see D1 step 4).
   - Replace `uq_active_mapping_per_material` with `WHERE status IN ('PENDING','CONFIRMED')`.
4. `MatchingClient` interface + `PythonMatchingClient`: add `extractAttributes(List<MaterialInfoDto>)` and `getCategorySchema(String)`. Cache schemas in a `ConcurrentHashMap` with a 10-minute TTL.
5. `MaterialController`: after persisting materials (single and bulk), call `extractAttributes` **in one batch per request**, persist `extracted_attributes` JSONB plus the four flat columns, and set `attributes_extracted_at`.
6. `NationalCodeGenerator.computeAttributeSignature`: add a `signatureComplete` return. Signature is `null` unless every identity-critical key for the category is present and non-blank.
7. `HarmonizationService.harmonizeMaterial`: implement the four-step decision order from D1 verbatim. Delete both `Collections.emptyMap()` call sites. (B-01, B-02)
8. `StubMatchingClient` must implement the new methods so the non-`python-matching` profile still compiles.

**Gate WP1:**
```bash
docker compose down -v && docker compose up -d && sleep 60
# log in as ONGC operator, ingest the 42-row synthetic set, harmonize, then:
docker compose exec postgres psql -U postgres -d material_master -c "
SELECT c.name AS category, COUNT(DISTINCT g.group_id) AS groups, COUNT(m.material_id) AS materials
FROM material m
JOIN material_category c ON c.category_id = m.category_id
LEFT JOIN material_mapping mm ON mm.material_id = m.material_id AND mm.status <> 'REJECTED'
LEFT JOIN material_group g ON g.group_id = mm.group_id
GROUP BY c.name ORDER BY 1;"
```
**Pass condition:** for every category with more than one distinct physical item, `groups > 1`. A category showing `groups = 1` with `materials = 12` means RC-1 is not fixed. Also assert every ingested material has `extracted_attributes IS NOT NULL`.

---

### WP2 — Replace the checksum with ISO 7064 MOD 37,36 *(RC-4)*

**Tasks:**
1. New `util/Iso7064Mod3736.java` per D2. Delete `util/DammAlgorithm.java`.
2. Update `NationalCodeGenerator.mintNationalCode` and `CodeController.validateCode` to use it.
3. Replace `DammAlgorithmTest` with `Iso7064Mod3736Test` containing **property tests**, not examples:
   - `allSingleCharacterSubstitutionsAreDetected()` — for 2,000 random valid codes, mutate every alphanumeric position to every other base-36 character; assert detection rate is exactly 100%.
   - `allAdjacentTranspositionsAreDetected()` — for 2,000 random valid codes, swap every adjacent distinct-character pair; assert exactly 100%.
   - `malformedCodesAreRejected()` — missing check char, wrong segment width, lowercase, embedded spaces, empty, null.
   - `roundTripIsStable()` — `validate(mint(category))` is true for all eight seeded categories.
4. Update every doc and UI string that says "Damm" to say "ISO 7064 MOD 37,36". (§11)

**Gate WP2:**
```bash
cd material-master-backend && mvn -q test -Dtest=Iso7064Mod3736Test
```
Paste the full output. All four tests must pass. If the detection-rate assertions are anything other than a hard `assertEquals(1.0, rate)`, the gate has been weakened and fails.

---

### WP3 — Matching pipeline: semantics, determinism, concurrency *(RC-6)*

**Tasks:**
1. Flask `inference.py`: return `match_probability` and `label_probability` per D4. Keep `confidence` as an alias of `match_probability`. (M-03)
2. Flask: add `POST /find-matches-batch` taking `{"queries":[{"material":{...},"candidates":[...],"top_k":5}, ...]}`. (M-04)
3. Flask: in `find_matches`, drop candidates whose `match_probability < 0.30` before returning — do not return noise the caller must filter. (M-06)
4. Java `CompareResponse` / `MatchCandidateResultDto`: add `matchProbability`, `labelProbability`. `HarmonizationService` tiers on `matchProbability` only. (B-06)
5. Fix B-22: the no-match path writes the real `matchProbability` and derived tier, never a hardcoded `1.0 / HIGH`.
6. `MaterialRepository`: add `ORDER BY m.materialId ASC` to both candidate queries. (B-09)
7. `createOrFindGroupForMaterial`: wrap the insert in a `try { save } catch (DataIntegrityViolationException) { re-select by signature; if still absent, rethrow }`. Add `@Retryable`-style single retry or do it manually — no new dependency. (B-08)
8. `HarmonizationJobService`: replace the raw `ExecutorService` with a Spring `ThreadPoolTaskExecutor` bean (`core=2, max=2, queue=100`) and `@Async`. Add `@PreDestroy` shutdown on the bean config. Set concurrency to **2**, not 4, to keep contention low. (P-06)
9. `MaterialController`: publish the async job **after commit** using `TransactionSynchronizationManager.registerSynchronization(... afterCommit ...)` or an `ApplicationEventPublisher` + `@TransactionalEventListener(AFTER_COMMIT)`. (P-07)
10. `POST /api/harmonization/harmonize-all` becomes asynchronous: create a `HarmonizationJob`, return `202 Accepted` with `{jobId}`, process in the same executor. Frontend polls `/api/jobs/{id}`. (P-05)
11. `PythonMatchingClient`: use the batch endpoint from the job service so a 500-row ingest is a handful of HTTP calls, not 500. (P-10)
12. `bulkApproveHighConfidence`: require `tier == HIGH` **and** `explanation.conflicts` contains no entry tagged `identity_critical`. Enforce server-side. (D4)

**Gate WP3:**
```bash
# 1. Semantics
curl -s localhost:5000/compare -H 'Content-Type: application/json' -d '{
 "material_a":{"description":"CS SEAMLESS PIPE 50MM SCH40 ASTM A106 GRB","category":"PIPE"},
 "material_b":{"description":"SS SEAMLESS PIPE 200MM SCH80 ASTM A312 TP316","category":"PIPE"}}' | jq
# Must show predicted_relationship NOT_A_MATCH with a LOW match_probability.
# match_probability must NOT be high just because the model is sure it's a non-match.

# 2. Determinism — same ingest twice, identical grouping
# run the ingest, snapshot; reset; re-run; diff
docker compose exec postgres psql -U postgres -d material_master -At -c \
 "SELECT m.cpse_material_code, g.provisional_ref FROM material_mapping mm
  JOIN material m ON m.material_id=mm.material_id
  JOIN material_group g ON g.group_id=mm.group_id ORDER BY 1;" > run1.txt
# ... reset and repeat ... then:
diff run1.txt run2.txt && echo "DETERMINISTIC"

# 3. Concurrency — no DataIntegrityViolationException
# upload two 200-row CSVs simultaneously from two operator accounts, then:
docker compose logs backend | grep -c "DataIntegrityViolationException"   # must be 0
```

---

### WP4 — Close the governance loop *(RC-2)*

**Tasks:**
1. `V5__governance_seed_fix.sql`: set `cpse_id` on the two reviewer accounts per D3's table. Rename seed emails to `@numm.gov.in` (from WP0).
2. `GovernanceService`: extract a private `applyDecision(mapping, actor, status, notes)`. `decideMapping` = COI check + `applyDecision`. `supersedeMapping` = admin check + reason check + `applyDecision`, **no COI check**. (B-21)
3. `GovernanceService.mintGroup`: change the guard from "a different senior reviewer" to "the publisher is not the user who confirmed any mapping in this group." Keep the `SENIOR_REVIEWER` role requirement.
4. New `GET /api/groups/publishable` → groups in `PROPOSED` with ≥ 1 `CONFIRMED` mapping, with member counts and distinct-CPSE counts.
5. `createOrUpdateMapping` must not mutate a `CONFIRMED`/`REJECTED` mapping. Set the old one `SUPERSEDED` + audit, then insert a new `PENDING` row. (B-07)
6. `api.js`: add `getPublishableGroups()`, `publishGroup(groupId, notes)`.
7. New `/publish` screen (WP9 styles it; wire it here).
8. COI rejection must return **409 Conflict** with `{"error":"CONFLICT_OF_INTEREST","message":"...","reviewerCpse":"IOCL","materialCpse":"IOCL"}`, not a 500 from `IllegalStateException`. Add the mapping in `ApiExceptionHandler`.

**Gate WP4 — the full governance path, end to end:**
```bash
# As ONGC operator: ingest a pipe that duplicates an existing IOCL pipe.
# As reviewer.mech (IOCL): approve it.           → 200
# As reviewer.mech (IOCL): try to approve an IOCL-submitted item.  → 409 CONFLICT_OF_INTEREST
# As senior.reviewer: GET /api/groups/publishable → the group appears
# As senior.reviewer: POST /api/groups/{id}/mint  → 200, returns NUMM-40-14-07-000001-X
docker compose exec postgres psql -U postgres -d material_master -c \
 "SELECT common_material_code, status FROM material_group WHERE status='ACTIVE';"
# Must return at least one row with a well-formed code.
curl -s localhost:8080/api/codes/NUMM-40-14-07-000001-X/validate -H "Authorization: Bearer $TOKEN" | jq .valid
# Must be true.
```

---

### WP5 — Harden the audit chain *(RC-5)*

**Tasks:** implement D5 items 1–6 exactly.

Additional: `AuditService.logEvent` currently throws on an unknown `entityType`. Keep that, and add `GROUP_RELATION` to the allowed set (it is missing and `createGroupRelation` will need to audit).

**Gate WP5:**
```bash
# 1. Chain verifies after a full demo run
curl -s localhost:8080/api/mappings/audit/verify -H "Authorization: Bearer $SENIOR_TOKEN" | jq
# → {"valid": true, "chainLength": N, ...}

# 2. Direct UPDATE is refused by the database
docker compose exec postgres psql -U postgres -d material_master -c \
 "UPDATE audit_trail SET new_value='tampered' WHERE audit_id=2;"
# → ERROR: audit_trail is append-only (attempted UPDATE)

# 3. old_value is in the hash
curl -s -X POST localhost:8080/api/demo/tamper-audit/2 -H "Authorization: Bearer $ADMIN_TOKEN"
curl -s localhost:8080/api/mappings/audit/verify -H "Authorization: Bearer $SENIOR_TOKEN" | jq
# → {"valid": false, "brokenAtAuditId": 2, ...}

# 4. No chain fork under concurrency
# run two simultaneous bulk uploads, then:
docker compose exec postgres psql -U postgres -d material_master -c \
 "SELECT prev_hash, COUNT(*) FROM audit_trail GROUP BY prev_hash HAVING COUNT(*) > 1;"
# → 0 rows
```

---

### WP6 — Analytics and query performance

**Tasks:**
1. Replace `AnalyticsService.getDashboardStats`'s in-memory work with aggregate SQL. Specifically:
   - Category distribution → `@Query("SELECT c.name, COUNT(m) FROM Material m LEFT JOIN m.category c GROUP BY c.name")`. (P-01)
   - CPSE mapped counts → one `GROUP BY` query joining `material` and `material_mapping`, not a query per material. (P-02)
   - `duplicatesEliminated` → one query: `SELECT SUM(cnt - 1) FROM (SELECT COUNT(*) cnt FROM material_mapping mm JOIN material_group g ON ... WHERE g.status='ACTIVE' AND mm.status='CONFIRMED' GROUP BY g.group_id HAVING COUNT(*) > 1) t`. (P-03)
2. Rate-contract candidates and price variance → single aggregate queries with `HAVING COUNT(DISTINCT cpse_id) >= 2`, returning projections, not entities. (P-03)
3. Fix B-10: guard `min.compareTo(ZERO) > 0` before dividing.
4. Fix B-11: `cpsePriceMap` becomes `Map<String, List<BigDecimal>>`; report min/max/avg per CPSE.
5. `CodeController.searchCodes` → repository query with `LIKE` on indexed columns and a `Pageable`. Add `CREATE INDEX idx_group_desc_lower ON material_group (LOWER(standardized_description));` in a migration. (P-04)
6. `findUnmatched` / `countUnmatched` → `NOT EXISTS`. (P-08)
7. Add `@Transactional(readOnly = true)` to every read path that lacks it.
8. Set `spring.jpa.properties.hibernate.default_batch_fetch_size=32` to blunt remaining N+1s.

**Gate WP6:**
```bash
# Temporarily enable SQL logging, load 500 materials, hit the dashboard once:
docker compose logs backend | grep -c "^Hibernate:"
```
**Pass condition:** a single `GET /api/dashboard/stats` issues **fewer than 20 queries** total. Today it issues one per material plus one per CPSE plus one per group. Paste the before and after counts.

---

### WP7 — Rebuild authorization *(RC-3)*

**Tasks:**
1. Rewrite `SecurityConfig.securityFilterChain` to the matrix in D6, in that exact order. Add a comment above each rule naming the matrix row number.
2. Delete `config/CorsConfig.java`. Parameterize origins.
3. `JwtTokenProvider`: remove the hardcoded secret default; fail fast outside `dev`/`demo`.
4. `UserPrincipal.isEnabled()` → `user.getActive()`. (B-17)
5. Delete `CodeController.java:158`. Implement the per-role member scoping from D6. Replace the in-loop `SecurityContextHolder` lookup with a single `@AuthenticationPrincipal UserPrincipal` parameter passed down. (B-18, B-19)
6. `MaterialController.getAllMaterials`: if `OPERATOR` and `cpseId == null`, return `403`, not everything. (B-20)
7. New `@Profile("demo") AuthDemoController` with `GET /api/auth/demo-accounts`, built by reading the seeded users from the repository and pairing them with the known demo passwords held in a `demo` profile property (`demo.accounts.operator-password=operator123` etc.) — never hardcode a password in Java source. (D7)
8. Frontend `src/auth/roles.js` with `NAV` and `defaultRouteFor(role)`. `App.jsx`, `Sidebar.jsx`, `LoginView.jsx` all consume it. (F-01)
9. `AuthContext.logout()` calls `queryClient.clear()`.
10. `api.js` 401 handler: keep the `numm:unauthorized` event, and additionally `queryClient.clear()` so stale role data never survives a session change.

**Gate WP7 — the role matrix test.** Write `material-master-backend/src/test/java/.../SecurityMatrixTest.java` using `@WebMvcTest` + `@WithMockUser`, with one test per matrix row (21 rows × the roles that should be allowed and at least one that should not). Then:
```bash
mvn -q test -Dtest=SecurityMatrixTest
```
And manually, for each of the four roles: log in, click every sidebar item, confirm **zero 403s and zero blank screens**. Paste the browser network-tab status codes for all four walkthroughs. Any 403 reached by clicking a visible nav item is a failed gate.

---

### WP8 — Frontend cleanup and structure

**Delete outright:**
```
frontend/src/context/PersonaContext.jsx      (F-02)
frontend/src/components/Navbar.jsx           (F-03)
frontend/src/components/BulkUploadView.jsx   (F-04)
frontend/src/App.css                         (F-12)
frontend/src/assets/react.svg
frontend/src/assets/vite.svg
frontend/src/assets/hero.png                 (F-13)
```

**Restructure:**
```
frontend/src/
├── auth/roles.js               # NAV + defaultRouteFor  (new, from WP7)
├── services/api.js             # every backend call, including export + mint
├── components/
│   ├── layout/                 # AppShell, Sidebar, Header
│   ├── common/                 # Table, StatusBadge, ConfidenceBadge, CodeChip,
│   │                           # ErrorPanel, EmptyState, Skeleton, Modal, Toast
│   └── views/                  # the 11 screens from D9
└── styles/
    ├── tokens.css              # ~60 custom properties, nothing else
    ├── base.css                # reset, typography, focus rings
    └── components.css          # button, table, card, form, badge, modal
```

**Tasks:**
1. Move every `fetch` into `api.js`, including the export downloads currently inlined in `CatalogView`. Add `download(path, filename)` that handles blobs, sets the `Authorization` header, and calls `URL.revokeObjectURL` in a `finally`. (F-05, F-07)
2. Replace `alert()` with a `<Toast>` component. Grep for `alert(` across `src/` — must return zero. (F-06)
3. Convert `ReviewQueueView` to TanStack Query so the app has one data layer. (F-08)
4. Wire the sidebar pending-count badge to a real `useQuery(['mappings','PENDING'])` count, or delete the badge. Do not leave a permanently-zero state variable. (F-09)
5. Add the `/my-materials` and `/publish` screens.

**Gate WP8:**
```bash
cd frontend && npm run build && npm run lint
grep -rn "alert(" src/           # no output
grep -rn "usePersona\|PersonaContext" src/   # no output
grep -rn "fetch(" src/ | grep -v "services/api.js"   # no output
```
Build must complete with **zero warnings**, not just zero errors.

---

### WP9 — Frontend visual rebuild *(D8)*

**Tasks:**
1. Write `styles/tokens.css` implementing D8's palette, type scale, spacing, radius, shadow. One `:root` block. Every value used elsewhere must come from a token — grep for hardcoded hex in `components.css` must return zero.
2. Install fonts: `npm i -E @fontsource/ibm-plex-sans @fontsource/ibm-plex-mono @fontsource/noto-sans-devanagari`. Import the 400/500/600 weights only.
3. Rewrite `index.css` → `base.css` + `components.css`. **Target: under 700 lines combined** (from 1,538). If you are over, you are still styling one-off cases instead of building components.
4. Build the shared `<Table>` component (sticky header, zebra off, 1px `--line` row separators, right-aligned numerics, `IBM Plex Mono` for code columns). Convert catalog, review queue, audit, my-materials, admin-users to use it.
5. Apply the deletion list from D8 ("no unnecessary detail").
6. Remove every banned icon. Grep for `Sparkles|Rocket|Zap|Wand2` — must return zero.
7. Accessibility pass: focus rings, table captions and scopes, `aria-live` on async regions, `role="alert"` on errors.
8. **i18n decision.** Run a key-parity check: extract every `t("...")` literal from `src/`, compare against the `en` and `hi` dictionaries in `i18n.js`. If `hi` coverage is not 100%, either complete it or remove the language toggle and the `hi` dictionary. Do not ship partial. (F-10)

**Gate WP9:**
```bash
cd frontend
wc -l src/styles/*.css              # combined < 700
grep -rn "#[0-9a-fA-F]\{3,6\}" src/styles/components.css src/styles/base.css   # no output
grep -rn "Sparkles\|Rocket\|Zap\|Wand2\|Star" src/    # no output
grep -rnP "[\x{1F300}-\x{1FAFF}\x{2600}-\x{27BF}]" src/   # no emoji
node scripts/check-i18n-parity.mjs  # you write this; exits non-zero on any missing key
npm run build
```
Plus: screenshot all 11 screens at 1440px and at 390px. Every screen must be usable at 390px with no horizontal scroll on the page body (tables may scroll inside their own container).

---

### WP10 — Export and ERP interoperability

**Tasks:**
1. Add `api.js` methods for all three exports. Wire them into `/catalog` (SENIOR_REVIEWER, ADMIN) and `/my-materials` (OPERATOR, scoped to their own CPSE).
2. `ExportService`: confirm the SXSSF window is actually flushing (it claims a 100-row window — verify with a 50,000-row export and watch heap). Confirm the CSV path streams rather than buffering.
3. Every export writes an `EXPORT` audit entry naming the requesting user, the scope, and the row count.
4. The SAP template export must produce the exact column set `MATNR, MAKTX, MEINS, MATKL` plus a `NUMM_CODE` column, with a header row and no BOM issues on Windows Excel (write UTF-8 with BOM for the CSV path specifically — Excel needs it).

**Gate WP10:** download all three exports as each role that can reach them; open the xlsx in a spreadsheet application; confirm the operator export contains **only** their CPSE's rows. Paste row counts and a `head -3` of each CSV.

---

### WP11 — Documentation and claims correction

**This is not optional. Overstated claims in `docs/` are a bigger risk to your score than any remaining bug**, because a judge who catches one stops trusting all the others.

**Tasks:**
1. Rewrite `docs/SYSTEM_STATUS_AND_ARCHITECTURE.md` from verified reality. Every number must come from a command you ran. Corrections required:
   - "Damm Mod-36 ... detects 100% of single-character transcription errors" → now true, but only because WP2 replaced the algorithm. Say "ISO/IEC 7064 MOD 37,36" and cite the standard.
   - "React 18" → React 19.
   - "`/compare-detailed`" → the endpoint is `/compare`. It does not exist under that name.
   - "Zero Fabrication Policy ... Deleted all mock fallbacks" → verify with a grep and only then keep the claim.
   - "Replaced full-table scans with indexed category candidate queries" → only true after WP6. Before WP6 there were four `findAll()` calls.
   - "16 of 16 tests passed" → report the real number after WP2 and WP7 add tests, and describe what they actually assert.
   - The Mermaid diagram says Postgres is on 5432 — update to 5433 after WP0.
2. Add a **Scope & Limitations** section. State plainly:
   - The classifier is trained on synthetic data generated by `src/data_generation/`. Test macro-F1 is 0.84; `VARIANT` recall is 0.62 and `NEEDS_REVIEW` recall is 0.64. It has never seen a real CPSE catalog.
   - FR11 (ERP integration) is met via CSV import/export, not a live SAP connector.
   - JWTs are stored in `sessionStorage`; production would use httpOnly cookies with CSRF protection.
   - The audit chain is tamper-*evident*, not tamper-*proof*: a DBA with the ability to drop the trigger could rewrite history, though the chain would then need full recomputation to stay consistent.

   A limitations section reads as engineering maturity. Judges reward it. Hiding a limitation that gets found reads as the opposite.
3. Rewrite `docs/DEMO_SCRIPT.md` to §10 below.
4. Delete `docs/Material_Master_Project_CONTEXT_v2.md`, `v3.md`, `FULL_CONTEXT (1).md`, `STACK_ADDENDUM.md`, `Day2_*.md`. They are superseded working notes and they contradict each other. Keep `HANDOVER_CONTEXT.md` only if it is rewritten to match reality.
5. Update `README.md` with a 60-second quickstart that actually works from a clean clone.

**Gate WP11:** for every quantitative claim in `docs/SYSTEM_STATUS_AND_ARCHITECTURE.md`, paste the command that produces it.

---

## 7. Data model after all work packages

```
cpse ──┬── user ──┬── reviewer_assignment ── material_category
       │          │
       │          └── audit_trail (append-only, hash-chained)
       │
       └── material ── material_mapping ── material_group ── material_category
                            │                    │
                            │                    └── group_relation (self-join)
                            │
                        harmonization_job

material
  + extracted_attributes   JSONB         (WP1 — populated at ingest)
  + attributes_extracted_at TIMESTAMP    (WP1)

material_group
  attribute_signature      VARCHAR(64) NULL   (WP1 — was UNIQUE NOT NULL)
  + signature_complete     BOOLEAN            (WP1)
  UNIQUE INDEX WHERE attribute_signature IS NOT NULL   (WP1)

material_mapping
  + match_basis            VARCHAR(30)        (WP1 — DETERMINISTIC_SIGNATURE | ML_PROPOSED | NOVEL)
  UNIQUE INDEX (material_id) WHERE status IN ('PENDING','CONFIRMED')   (WP1 — widened)

audit_chain_head                                (WP5 — single-row append serializer)
```

**State machines:**

```
material_group:   PROPOSED ──publish──▶ ACTIVE ──▶ SUPERSEDED
                      │                    │
                      └──reject all──▶ REJECTED   └──▶ DEPRECATED

material_mapping: PENDING ──approve──▶ CONFIRMED ──re-harmonize──▶ SUPERSEDED
                     │
                     └──reject───▶ REJECTED
```

---

## 8. Acceptance test suite

The agent writes these. They are the definition of "no bugs" for this project.

### Backend integration tests (`src/test/java/.../integration/`)

| Test | Asserts |
|---|---|
| `IdentityModelIT.differentPipesGetDifferentGroups` | Ingest 6 distinct pipes → 6 groups. **Directly guards RC-1.** |
| `IdentityModelIT.identicalAttributesMergeDeterministically` | Two records with identical identity-critical attributes → same group, `match_basis = DETERMINISTIC_SIGNATURE` |
| `IdentityModelIT.incompleteAttributesDoNotForceMerge` | Two records missing a size → two groups, both `signature_complete = false` |
| `GovernanceIT.fullLifecycleMintsCode` | ingest → confirm → publish → `status = ACTIVE` and code validates |
| `GovernanceIT.sameCpseReviewerIsBlocked` | IOCL reviewer approving an IOCL item → 409 |
| `GovernanceIT.crossCpseReviewerIsAllowed` | IOCL reviewer approving an ONGC item → 200 |
| `GovernanceIT.publisherCannotBeConfirmer` | Same user confirming then publishing → 409 |
| `GovernanceIT.reharmonizationDoesNotRevertConfirmed` | Confirm, re-harmonize → old mapping `SUPERSEDED`, not silently `PENDING` (**B-07**) |
| `AuditChainIT.chainVerifiesAfterFullLifecycle` | `valid: true` |
| `AuditChainIT.oldValueTamperingIsDetected` | mutate `old_value` → `valid: false` (**B-12**) |
| `AuditChainIT.concurrentWritesDoNotFork` | 50 parallel `logEvent` calls → no duplicate `prev_hash` (**B-13**) |
| `AuditChainIT.directUpdateIsRefused` | raw `UPDATE` → exception |
| `SecurityMatrixTest` | One case per D6 matrix row, allowed and denied (**RC-3**) |
| `ConfidenceIT.notAMatchIsNeverHighTier` | A confident `NOT_A_MATCH` → `tier != HIGH` (**B-06**) |
| `Iso7064Mod3736Test` | 100% single-char and adjacent-transposition detection (**RC-4**) |
| `AnalyticsIT.zeroMinPriceDoesNotThrow` | A group with a `0.00` price → no `ArithmeticException` (**B-10**) |

### Matching service tests (`matching-service/tests/`)

| Test | Asserts |
|---|---|
| `test_extract_attributes_endpoint` | batch shape, `identity_critical_present` flag correctness |
| `test_match_probability_vs_label_probability` | a confident `NOT_A_MATCH` has low `match_probability` |
| `test_near_miss_pairs_rejected` | the Day-3 near-miss pairs (different size, same wording) are not classified as duplicates |
| `test_true_match_pairs_accepted` | the 28 known true-match pairs still pass |
| `test_malformed_input_never_500s` | empty body, missing fields, wrong types, absurd `top_k` → 400 or a graceful result, never 500 |

### Manual walkthrough (run before every demo)

Four logins × every nav item × zero 403s × zero blank screens × zero console errors.

---

## 9. Suggested execution order and effort shape

| Phase | Work packages | What it buys you |
|---|---|---|
| **P0 — must ship** | WP0, WP1, WP4, WP7 | The product actually works: distinct groups, a mintable code, a non-empty catalog, an auth system that doesn't 403 on every click. If you do nothing else, do these four. |
| **P1 — credibility** | WP2, WP3, WP5 | The three headline technical claims (checksum, ML confidence, tamper-evident audit) become true instead of aspirational. |
| **P2 — presentation** | WP8, WP9, WP11 | The interface stops undermining the engineering, and the documentation stops overstating it. |
| **P3 — if time remains** | WP6, WP10 | Performance and export polish. Real, but a judge is unlikely to load 500,000 rows. |

If time is short, **WP11 outranks WP6 and WP10.** A working system described honestly beats a faster one described falsely.

---

## 10. Demo script (five acts, post-remediation)

Rewrite `docs/DEMO_SCRIPT.md` to this. Every step must work after WP7.

**Act 0 — Reset (before the judges arrive)**
`docker compose down -v && docker compose up -d`, wait for health, confirm the catalog is empty. Starting from zero and building the catalog live is far stronger than showing a pre-populated one.

**Act 1 — The problem, in their own data (60s)**
Log in as **ONGC Operator** (one click). `/ingest`: upload an ONGC SAP export. Show the column auto-mapping binding `mat_no` → `cpse_material_code`, `short_text` → `description`. Submit. Watch the job progress. Land on `/my-materials` — 12 rows, all `PENDING`.
*Say:* "ONGC's system calls this CS SEAMLESS PIPE 50MM SCH40. Nobody else does."

**Act 2 — Attribute-aware matching (90s)**
`/compare`. Paste the ONGC pipe against the IOCL pipe. Show:
- The attribute parity matrix — same nominal size, same schedule, same grade, different standard reference.
- `match_probability`, and separately the class distribution.
- Then paste a **near-miss**: same wording pattern, 200MM instead of 50MM. Show the conflict panel flagging `nominal_size` as `identity_critical` and the match probability collapsing.
*Say:* "Text similarity alone rates the near-miss higher than the true match. That's why we extract attributes and compare them structurally — the feature importances show attribute agreement and conflict outweighing raw text similarity."

**Act 3 — Four-eyes governance (90s)**
Log in as **Mechanical Reviewer (IOCL)**. `/review`: the ONGC pipe is queued, MEDIUM tier, 40 hours old, SLA warning. Open it. Approve — succeeds.
Now try to approve an **IOCL-submitted** item. **409, conflict of interest, named explicitly.**
*Say:* "A reviewer cannot adjudicate their own enterprise's submission. That's enforced server-side; the button existing in the UI doesn't help you."

**Act 4 — Publication and the national code (60s)**
Log in as **Senior Reviewer**. `/publish`: the group appears with 2 confirmed members across 2 CPSEs. Publish. The code `NUMM-40-14-07-000001-X` is minted **in that transaction**.
Go to `/codes/NUMM-40-14-07-000001-X`. Show the canonical record, both members, the checksum validator. Then change one character in the URL and show it rejected.
*Say:* "One character typed wrong in a tender document is caught by the check character. ISO 7064 MOD 37,36 — 100% detection of single-character errors and adjacent transpositions. We tested that property exhaustively, not with two examples."

**Act 5 — Audit and aggregate value (90s)**
`/audit`: the chain of events from ingest to mint. Click **Verify chain integrity** → green, N blocks from genesis.
Then run the tamper demo → verify again → **red, broken at audit #7**.
Then show the database refusing a direct `UPDATE` in a terminal.
Finally `/dashboard`: dedup rate and savings, with the **Assumptions panel open** showing exactly which numbers drive the rupee figure and where they came from.
*Say:* "The savings figure is a formula over four stated assumptions, editable and audited. It is not a number we chose because it looked good."

**Closing:** open `docs/SYSTEM_STATUS_AND_ARCHITECTURE.md` at the **Scope & Limitations** section and read two lines aloud. Volunteering the limits before you are asked is the strongest move available.

---

## 11. Claims in the current docs that are false

Correct each one in WP11. Listed here so nothing is missed.

| Claim | Location | Reality |
|---|---|---|
| "Detects 100% of single-character transcription errors and 100% of adjacent character transposition errors" | §3.2.1 | Measured 72.2% / 88.8%. The table is not a quasigroup. Becomes true only after WP2. |
| "React 18 / Vite Accessible SPA" | architecture diagram | `package.json` pins React 19.2 |
| "`app.py` exposing `/find-matches`, `/compare-detailed`, `/health`, `/generate-code`" | §3.1 | The endpoint is `/compare`. `/compare-detailed` does not exist. |
| "Zero Fabrication Policy: Deleted all mock fallbacks" | BUG-01 row | Mostly true in the frontend, but `PythonMatchingClient.generateCode` fabricates a code from a random UUID on failure, and `HarmonizationService` writes a hardcoded `1.0 / HIGH` confidence for no-match cases (B-22) |
| "Replaced full-table scans with indexed category candidate queries (`findCandidatesByCategory`)" | BUG-07 row | `AnalyticsService` calls `findAll()` three times; `CodeController.searchCodes` calls `findAll()` once. Becomes true after WP6. |
| "Group Lifecycle State Machine: canonical code is minted only upon reviewer sign-off" | BUG-03 row | Technically true and practically false — no code can ever be minted (RC-2) |
| "Conflict of Interest Barrier: a reviewer from ONGC is blocked from approving items submitted by ONGC" | §3.2.3 | Unreachable: every seeded reviewer has `cpse_id = NULL`, so the guard never evaluates (B-05) |
| "Cryptographic Chained SHA-256 Ledger ... `prev_hash` → `row_hash`" | BUG-12 row | `old_value` is excluded from the hash, and `V3` rewrites the entire chain (RC-5) |
| "16 of 16 tests passed" with per-class breakdowns | §5.1 | The tests pass but do not test the claimed properties. `DammAlgorithmTest`'s "detects single character substitution" checks exactly one substitution. |
| "`numm-postgres` (Postgres 16, Port 5432) — Healthy" | §5.3 | Collides with a native Postgres install; moving to 5433 in WP0 |
| "Streaming ... million-record XLSX files without JVM Heap exhaustion" | §3.2.5 | Never tested at that scale. Either test it in WP10 or soften the claim. |
| "GIGW 3.0 & WCAG 2.1 AA compliance" | §1.2 | Partially implemented (skip link, some ARIA). "Compliance" is a formal claim; say "built against GIGW 3.0 and WCAG 2.1 AA guidance" unless you have run an audit. |

---

## 12. File disposition summary

**Delete:**
```
frontend/src/context/PersonaContext.jsx
frontend/src/components/Navbar.jsx
frontend/src/components/BulkUploadView.jsx
frontend/src/App.css
frontend/src/assets/{react.svg,vite.svg,hero.png}
material-master-backend/src/main/java/.../config/CorsConfig.java
material-master-backend/src/main/java/.../util/DammAlgorithm.java
material-master-backend/src/test/java/.../util/DammAlgorithmTest.java
material-master-backend/src/main/resources/data.sql
material-master-backend/src/main/resources/schema.sql
material-master-backend/data/numm-demo.*.db
docs/Material_Master_Project_CONTEXT_v2.md
docs/Material_Master_Project_CONTEXT_v3.md
docs/Material_Master_Project_FULL_CONTEXT (1).md
docs/Material_Master_Project_STACK_ADDENDUM.md
docs/Day2_Complete_Summary.md
docs/Day2_Session_Summary.md
```

**Create:**
```
material-master-backend/src/main/java/.../util/Iso7064Mod3736.java
material-master-backend/src/main/java/.../controller/AuthDemoController.java     (@Profile("demo"))
material-master-backend/src/main/java/.../config/AsyncConfig.java
material-master-backend/src/main/resources/db/migration/V4__attribute_identity.sql
material-master-backend/src/main/resources/db/migration/V5__audit_chain_head.sql
material-master-backend/src/main/resources/db/migration/V6__governance_seed_fix.sql
material-master-backend/src/test/java/.../util/Iso7064Mod3736Test.java
material-master-backend/src/test/java/.../SecurityMatrixTest.java
material-master-backend/src/test/java/.../integration/{IdentityModelIT,GovernanceIT,AuditChainIT,ConfidenceIT,AnalyticsIT}.java
frontend/src/auth/roles.js
frontend/src/styles/{tokens.css,base.css,components.css}
frontend/src/components/views/MyMaterialsView.jsx
frontend/src/components/views/PublishQueueView.jsx
frontend/src/components/common/{Table.jsx,Toast.jsx,EmptyState.jsx}
frontend/scripts/check-i18n-parity.mjs
docs/RUNBOOK.md
docs/FOUND_ISSUES.md
```

**Rewrite substantially:**
```
material-master-backend/src/main/java/.../config/SecurityConfig.java       (WP7)
material-master-backend/src/main/java/.../service/HarmonizationService.java (WP1, WP3)
material-master-backend/src/main/java/.../service/GovernanceService.java    (WP4)
material-master-backend/src/main/java/.../service/AuditService.java         (WP5)
material-master-backend/src/main/java/.../service/AnalyticsService.java     (WP6)
material-master-backend/src/main/java/.../controller/CodeController.java    (WP6, WP7)
matching-service/app.py                                                     (WP1, WP3)
matching-service/src/matching/inference.py                                  (WP3)
frontend/src/index.css → styles/                                            (WP9)
frontend/src/services/api.js                                                (WP8)
frontend/src/App.jsx, components/common/Sidebar.jsx, components/LoginView.jsx (WP7, WP8, WP9)
docs/SYSTEM_STATUS_AND_ARCHITECTURE.md, docs/DEMO_SCRIPT.md, README.md      (WP11)
```

**Do not touch:**
```
matching-service/src/preprocessing/attribute_extraction.py
matching-service/src/features/feature_engineering.py
matching-service/src/data_generation/**
matching-service/models/**            (except .gitignore treatment)
material-master-backend/src/main/resources/db/migration/V1*.sql, V2*.sql, V3*.sql
```
(Applied migrations are immutable. All schema changes go in V4 and later.)

---

## 13. Final checklist before submission

- [ ] `docker compose down -v && docker compose up -d` brings up four healthy containers from a clean state
- [ ] All four demo accounts log in; every visible nav item loads without a 403
- [ ] Ingesting the synthetic set produces **more than one group per category**
- [ ] A national code is minted through the UI and validates
- [ ] A corrupted code fails validation
- [ ] The COI barrier fires and is visible
- [ ] Audit chain verifies green, then red after the tamper demo
- [ ] Postgres refuses a direct `UPDATE` on `audit_trail`
- [ ] Dashboard shows non-zero dedup and savings, with assumptions visible
- [ ] Exports download and open, scoped correctly per role
- [ ] `mvn test` and `pytest` both green; paste the counts
- [ ] `npm run build` and `npm run lint` clean, zero warnings
- [ ] Zero `alert()`, zero emoji, zero banned icons, zero hardcoded hex outside tokens
- [ ] Every screen usable at 390px width
- [ ] Every quantitative claim in `docs/` traced to a command
- [ ] Scope & Limitations section written and honest
- [ ] Full keyboard-only run of the five-act demo
