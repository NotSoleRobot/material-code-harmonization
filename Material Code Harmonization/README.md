# NUMM — National Unified Material Master

**AI-Driven Standardization and Harmonization of Material Codes Across CPSEs**  
*Smart India Hackathon 2026 | Problem Statement: PS 26099 | Ministry of Petroleum & Natural Gas (MoPNG)*

NUMM is an enterprise-scale material master harmonizer built for India's Central Public Sector Enterprises (CPSEs like ONGC, IOCL, GAIL, BHEL, and SAIL). Each enterprise maintains decades of fragmented, free-text ERP material records with conflicting naming conventions, imperial/metric mixtures, vendor aliases, and non-standardized abbreviations. NUMM ingests these messy catalogs, extracts standardized engineering attributes, detects cross-enterprise duplicates and functional equivalents via a hybrid machine learning pipeline, routes uncertain records through a 4-eyes technical review queue, and mints permanent, canonical national material codes (`NUMM-CCCCCC-MM-DDD-RRR-NNNNNN-K`) protected by an ISO/IEC 7064 MOD 37,36 check character.

The entire system is self-contained. No external cloud LLMs. No sensitive operational data leaks to commercial APIs. Deterministic attribute hashing, a 17-feature Random Forest classifier, tamper-evident cryptographic audit logs, and an asynchronous streaming ingestion pipeline all run within your enterprise infrastructure or self-hosted cloud.

---

## What it actually does

When a CPSE operator uploads a material catalog, NUMM:

1. **Validates and ingests asynchronously** — Ingests CSV or XLSX catalogs up to tens of thousands of rows. Uses resilient schema aliasing (`material_id` / `matnr` / `item_code`, `description` / `item_desc` / `short_text`, `specification` / `norm`, `uom` / `meins`) and streams real-time parsing progress back to the browser via Server-Sent Events (SSE).

2. **Extracts engineering attributes** — Normalizes raw industrial descriptions into structured attributes (nominal diameter in mm, schedule, pressure rating, material grade, voltage, power in kW, bearing number) across 10 UNSPSC-aligned commodity categories using deterministic token parsing, metric standardization, and regex attribute grammars.

3. **Generates deterministic attribute signatures** — When all identity-critical attributes are present for an item, the system computes a SHA-256 attribute signature namespaced by UNSPSC commodity class. Identical engineering specifications from different CPSEs match deterministically in $O(1)$ time with zero ML variance.

4. **Retrieves candidates via two-stage blocking** — Prevents combinatorial $O(N^2)$ cross-comparison across millions of national records:
   - **Stage 1 (Category blocking):** Restricts candidates strictly to the same UNSPSC commodity category.
   - **Stage 2 (TF-IDF pre-filter):** Ranks candidate matches by word-level (1–2 gram) and character-level (2–4 gram) TF-IDF cosine similarity, passing the top 25 candidates to the classifier. Candidates below 0.30 probability are pruned.

5. **Classifies pairwise relationships via hybrid ML** — A trained Random Forest classifier evaluates candidate pairs against 17 engineered features combining character n-grams, token sort ratios, attribute agreement fractions, and conflict flags (identity-critical vs variant-critical). It predicts one of 6 semantic labels:
   - `EXACT_DUPLICATE` — Identical specifications and identity attributes.
   - `NEAR_DUPLICATE` — Minor text/brand variations with matching core parameters.
   - `FUNCTIONALLY_EQUIVALENT` — Cross-standard equivalents (e.g., ASTM A106 vs IS 1239 pipe).
   - `VARIANT` — Same commodity family but differing in a variant-critical parameter (e.g., schedule or pressure rating).
   - `NOT_A_MATCH` — Distinct engineering items.
   - `NEEDS_REVIEW` — Borderline ambiguity requiring expert human adjudication.

6. **Executes the 4-step D1 decision pipeline** — Every material passes through a strict four-level resolution hierarchy:
   ```
   Input Material
         │
         ├── 1. Exact SHA-256 Signature Match?
         │         └── YES ──► Auto-link to existing Canonical Group
         │
         ├── 2. AI Hybrid Score >= 0.85 & Margin >= 0.10 & No Critical Conflicts?
         │         └── YES ──► Auto-confirm Match Proposal (or route to Reviewer)
         │
         ├── 3. FUNCTIONALLY_EQUIVALENT or VARIANT Classification?
         │         └── YES ──► Create distinct Group & link via `group_relation` graph
         │
         └── 4. Max Candidate Score < 0.30 (Novel Item)?
                   └── YES ──► Register Novel Group with Provisional Ref: `PROV-{COMMODITY}-{SERIAL}`
   ```

7. **Enforces multi-role 4-eyes governance** — Clear separation of duties across 4 role tiers:
   - **CPSE Operator:** Ingests enterprise catalogs and views mapping statuses.
   - **Domain Reviewer:** Specialist technical reviewer assigned by UNSPSC commodity (e.g., Mechanical vs Electrical) who reviews proposed mappings with side-by-side attribute diffs.
   - **Senior Reviewer:** Authorizes canonical groupings and triggers formal national code minting.
   - **System Administrator:** Oversees cross-CPSE users, audit chain integrity, and procurement ROI assumptions.

8. **Mints standardized national codes with ISO/IEC 7064 check digits** — Upon senior reviewer approval, the item receives a 7-segment national code:
   ```
   NUMM - CCCCCC - MM - DDD - RRR - NNNNNN - K
     │       │     │     │     │      │     │
     │       │     │     │     │      │     └── ISO/IEC 7064 MOD 37,36 Check Character
     │       │     │     │     │      └──────── Per-commodity atomic serial (6 digits)
     │       │     │     │     └─────────────── Rating / Schedule key (e.g., S40, 150)
     │       │     │     └───────────────────── Nominal dimension in mm (e.g., 050)
     │       │     └─────────────────────────── Material grade (e.g., CS, SS, CI, MS)
     │       └───────────────────────────────── UNSPSC commodity code (6 digits, e.g., 401407)
     └───────────────────────────────────────── System identifier
   ```
   Example: `NUMM-401407-CS-050-S40-000042-K`  
   The hybrid MOD 37,36 check character guarantees 100% detection of single-character transcription errors and adjacent character transpositions.

9. **Guarantees audit immutability via cryptographic chaining** — Every lifecycle transition (upload, match suggestion, reviewer adjudication, code minting) is permanently recorded in a SHA-256 cryptographically chained audit log:
   $$\text{Hash}_n = \text{SHA-256}(\text{Hash}_{n-1} \parallel \text{user\_id} \parallel \text{action} \parallel \text{entity} \parallel \text{timestamp} \parallel \text{payload})$$
   Serialized through an atomic single-row head lock and enforced as append-only via database trigger constraints.

10. **Calculates cross-CPSE procurement intelligence & ROI** — Detects cross-enterprise duplicate procurement, identifies surplus inventory sharing opportunities across CPSEs, and projects volume bundling savings based on configurable annual procurement assumptions.

---

## Evaluation numbers

These metrics measure fit to the synthetic reference dataset (24,750 pairs generated across 10 simulated CPSEs and 10 UNSPSC material categories) using a **canonical-group split** to guarantee zero data leakage between training, validation, and test sets.

| Metric | Naive Baseline (TF-IDF Cosine >= 0.30) | NUMM Hybrid Model |
|--------|----------------------------------------|-------------------|
| **Test Binary Precision** | 0.799 | **0.929** |
| **Test Binary Recall** | 0.745 | **0.957** |
| **Test Binary F1-Score** | 0.771 | **0.943** |
| **False-Merge Rate on Hard Negatives** | 22.3% | **5.6%** |
| **Validation Macro F1** | — | **0.822** |
| **Test Macro F1** | — | **0.838** |
| **Overall Classification Accuracy** | 0.781 | **0.910** (2,635 test samples) |
| **ISO 7064 MOD 37,36 Error Detection** | — | **100%** (0 undetected in 2,000 swap/substitution tests) |
| **Backend Integration Suite** | — | **68 tests, 7 suites, 0 failures** |
| **Matching Engine Test Suite** | — | **10 tests, 0 failures** |

### Per-class test performance (n = 2,635)

| Semantic Relationship | Precision | Recall | F1-Score | Support |
|-----------------------|-----------|--------|----------|---------|
| `EXACT_DUPLICATE` | 0.95 | 0.99 | 0.97 | 281 |
| `NEAR_DUPLICATE` | 0.92 | 0.95 | 0.93 | 1,495 |
| `FUNCTIONALLY_EQUIVALENT` | 0.83 | 0.83 | 0.83 | 6 |
| `VARIANT` | 0.70 | 0.62 | 0.66 | 50 |
| `NOT_A_MATCH` | 0.95 | 0.94 | 0.95 | 507 |
| `NEEDS_REVIEW` | 0.74 | 0.64 | 0.68 | 296 |

---

## Feature importance (17 Engineered Signals)

The Random Forest model relies on 17 features combining lexical, character-level, and schema-aware attribute logic:

| Rank | Feature | Importance | Description |
|:----:|---------|:----------:|-------------|
| 1 | `frac_agree` | 0.1110 | Fraction of comparable extracted attributes that exactly agree |
| 2 | `tfidf_char_cosine` | 0.1043 | Character n-gram (2–4 chars) TF-IDF cosine similarity |
| 3 | `n_attrs_conflict` | 0.1008 | Total count of extracted attributes with conflicting values |
| 4 | `fuzz_ratio` | 0.0892 | Normalized Levenshtein similarity across full descriptions |
| 5 | `tfidf_word_cosine` | 0.0871 | Word n-gram (1–2 words) TF-IDF cosine similarity |
| 6 | `any_type_conflict` | 0.0836 | Boolean flag for commodity subtype mismatch (e.g., ball vs gate valve) |
| 7 | `fuzz_token_sort` | 0.0765 | Token-sorted Levenshtein ratio (order-invariant description match) |
| 8 | `variant_critical_conflict` | 0.0632 | Conflict on variant-defining attributes (schedule, pressure rating) |
| 9 | `fuzz_partial` | 0.0618 | Partial substring Levenshtein alignment score |
| 10 | `n_attrs_agree` | 0.0533 | Absolute count of matching extracted engineering attributes |
| 11 | `len_ratio` | 0.0434 | Ratio of string lengths between query and candidate descriptions |
| 12 | `identity_critical_conflict` | 0.0358 | Hard conflict on identity-critical keys (e.g., material grade/alloy) |
| 13 | `n_attrs_compared` | 0.0329 | Total number of attributes present and comparable in both records |
| 14 | `n_critical_missing` | 0.0268 | Total critical attributes missing from either or both records |
| 15 | `variant_critical_missing` | 0.0178 | Missing attributes that define product variants |
| 16 | `identity_critical_missing` | 0.0081 | Missing core identity attributes |
| 17 | `same_category` | 0.0043 | UNSPSC category concordance indicator |

---

## Supported Material Categories

NUMM ships with 10 engineering commodity schemas defining identity-critical and variant-critical boundaries:

| Category | Identity-Critical Attributes | Variant-Critical Attributes | Reference Standards |
|----------|------------------------------|-----------------------------|---------------------|
| `PIPE` | material | nominal_size_mm, schedule, grade | ASTM A106, ASTM A312, IS 1239, API 5L |
| `VALVE` | valve_type, body_material | nominal_size_mm, pressure_class | API 600, ASME B16.34, IS 780 |
| `FLANGE` | flange_type, material | nominal_size_mm, pressure_class | ASME B16.5, IS 6392 |
| `GASKET` | gasket_type, material | nominal_size_mm, pressure_class | ASME B16.20, ASME B16.21 |
| `BEARING` | bearing_type, bearing_number | bore_mm, seal_type | ISO 15, DIN 625 |
| `MOTOR` | voltage_v, phase | power_kw, rpm | IS 325, IEC 60034 |
| `CABLE` | conductor | cores, csa_sqmm, voltage_grade_kv | IS 1554, IS 7098 |
| `PUMP` | pump_type | power_hp, phase | API 610, IS 5120 |
| `FASTENER` | fastener_type | size, grade | ASTM A193, IS 1364 |
| `FILTER` | filter_type | micron_rating, connection_size_mm | ISO 16889 |

*Open-Domain Fallback:* If records belong to categories outside these 10 schemas, NUMM dynamically engages a weighted lexical fallback model ($0.40 \times \text{word TF-IDF} + 0.25 \times \text{char TF-IDF} + 0.20 \times \text{token sort} + 0.15 \times \text{fuzz ratio}$).

---

## Project structure

```
Material Code Harmonization/
├── material-master-backend/            Spring Boot 3.3 REST API (Java 17)
│   ├── src/main/java/.../
│   │   ├── controller/                 REST controllers (Jobs, Materials, Codes, Governance, Analytics)
│   │   ├── service/                    HarmonizationService (D1 pipeline), NationalCodeGenerator, AuditService
│   │   ├── security/                   JWT filters, UserPrincipal, RBAC configuration
│   │   └── repository/                 Spring Data JPA repositories
│   └── src/main/resources/
│       ├── db/migration/               17 versioned Flyway SQL migrations (schema, seeds, indexes)
│       └── application*.properties     Base, demo, and hosted configuration profiles
├── matching-service/                   Python 3.11 Flask ML Matching Engine
│   ├── src/
│   │   ├── matching/                   Inference engine (compare, find-matches), training pipeline
│   │   ├── features/                   17-feature engineering extractor (RapidFuzz, TF-IDF)
│   │   ├── preprocessing/              Engineering attribute extractor & text normalizer
│   │   └── data_generation/            Synthetic pair generator & commodity schemas
│   ├── models/                         Pre-trained Random Forest classifier & TF-IDF vectorizers
│   ├── data/                           Sample demo catalogs (synthetic_materials_v2.csv)
│   └── tests/                          Pytest test suite
├── frontend/                           React 19 + Vite SPA
│   ├── src/
│   │   ├── pages/                      Ingest, MyMaterials, Review, SeniorReview, Catalog, Analytics
│   │   ├── components/                 Design system, SSE progress bars, Side-by-side diff modal
│   │   └── api/                        Axios HTTP client with JWT interceptor & SSE listeners
│   └── nginx.conf                      Docker reverse proxy configuration
├── docs/                               Technical documentation & operational runbooks
│   ├── RUNBOOK.md                      Ports, profiles, and local execution reference
│   ├── SYSTEM_STATUS_AND_ARCHITECTURE.md Architecture specifications and test audit records
│   ├── DEMO_SCRIPT.md                  Step-by-step 5-minute presentation script
│   └── RENDER_DEPLOYMENT.md            Cloud production guide (Render + Supabase)
├── scripts/                            Preflight verification and database seeding utilities
├── docker-compose.yml                  4-service local production stack definition
└── render.yaml                         Render Blueprint specification for hosted cloud
```

---

## Setup & Quick Start

### Prerequisites
- **Docker Desktop** (version 24+) with Compose V2
- *Or for manual run:* Java 17+, Maven 3.9+, Python 3.11, Node.js 20+

### Option 1: Docker Compose (Recommended)

Start the complete 4-service stack with a single command from this directory:

```powershell
docker compose up -d --build
```

Wait ~60 seconds for containers to initialize and apply migrations, then navigate to:
**`http://localhost:3000`**

| Service | Port | Description | Health Endpoint |
|---------|------|-------------|-----------------|
| **Frontend** | `3000` | React 19 SPA served via nginx | `http://localhost:3000` |
| **Backend API** | `8080` | Spring Boot 3.3 REST service | `http://localhost:8080/actuator/health` |
| **Matching Engine** | `5000` | Python Flask ML microservice | `http://localhost:5000/health` |
| **Database** | `5433` | PostgreSQL 16 with Flyway migrations | Mapped to `5433` to prevent local conflicts |

### Seeded Demo Accounts

The local stack starts pre-seeded with role-specific accounts for instant evaluation:

| Role | Email | Password | Access Level |
|------|-------|----------|--------------|
| **CPSE Operator (ONGC)** | `operator@ongc.co.in` | `operator123` | Catalog ingestion, enterprise mapping overview |
| **Domain Reviewer (Mechanical)**| `lead.reviewer@numm.gov.in` | `reviewer123` | Pipe, Valve, Flange, Pump, Bearing review queues |
| **Domain Reviewer (Electrical)**| `reviewer.electrical@numm.gov.in` | `reviewer123` | Motor, Cable review queues |
| **Senior Reviewer** | `senior.reviewer@numm.gov.in` | `reviewer123` | Dual-authorization, catalog publication, code minting |
| **System Administrator** | `admin@numm.gov.in` | `admin123` | User administration, audit trail, ROI parameter tuning |

### Preflight Verification

To verify that all services, endpoints, and inference pathways are healthy:

```powershell
.\scripts\preflight_demo.ps1
```

### Option 2: Local Development Run

**Terminal 1 — Database & Matching Engine:**
```powershell
docker compose up -d postgres matching-service
```

**Terminal 2 — Spring Boot Backend:**
```powershell
cd material-master-backend
mvn spring-boot:run -Dspring-boot.run.profiles=python-matching,demo
```

**Terminal 3 — React Frontend:**
```powershell
cd frontend
npm install
npm run dev
```

Open `http://localhost:5173/login`.

---

## Data format

Upload a CSV or XLSX file. Column headers are automatically resolved using resilient aliasing:

| Standard Field | Accepted Column Aliases | Required | Description |
|----------------|-------------------------|:--------:|-------------|
| `material_id` | `item_code`, `code`, `matnr`, `material_code` | Yes | CPSE-specific local identifier |
| `description` | `item_description`, `short_text`, `material_desc` | Yes | Raw engineering text string |
| `specification`| `spec`, `standard`, `norm`, `tech_spec` | Optional | Engineering standard (e.g., ASTM A106, IS 1239) |
| `uom` | `unit`, `unit_of_measure`, `meins` | Optional | Unit of measure (e.g., MTR, EA, KG, NO) |
| `category` | `matkl`, `commodity`, `material_group` | Optional | UNSPSC commodity classification |

A benchmark demo dataset with sample items across ONGC, IOCL, GAIL, and BHEL is located at:  
`matching-service/data/synthetic_materials_v2.csv`

---

## What this is not

- **Not an ungrounded LLM wrapper:** NUMM does not use opaque third-party API prompts that hallucinate engineering specifications. It utilizes regex-anchored attribute grammars, deterministic SHA-256 signatures, and a 17-feature Random Forest classifier trained specifically on industrial MRO parameters.
- **Not an autonomous code minter:** The AI does not unilaterally assign permanent national codes. It provides ranked confidence scores, relationship classifications, and side-by-side attribute diffs. Permanent national codes are only minted when an authorized Senior Reviewer reviews and approves the grouping.
- **Not a live ERP transaction gateway:** NUMM does not execute live purchase orders or modify upstream ERP transactional tables directly. It ingests catalog snapshots and exports standardized cross-reference tables and SAP-compatible master records (`MATNR`, `MAKTX`, `MEINS`, `MATKL`, `NUMM_CODE`) with UTF-8 BOM encoding.
- **Note on evaluation data:** All reported ML benchmark metrics are calculated on the 24,750-pair synthetic benchmark dataset generated for SIH 2026 PS 26099. Real CPSE catalogs will exhibit domain-specific nuances that will be calibrated during on-premise CPSE pilot onboarding.

---

## Technology Stack

- **Backend:** Java 17, Spring Boot 3.3.4, Spring Security, Spring Data JPA, Flyway 10, Apache POI 5.2.5
- **Machine Learning & NLP:** Python 3.11, Flask 3.1.0, scikit-learn 1.8.0, RapidFuzz 3.10.1, NumPy, Joblib
- **Database:** PostgreSQL 16, pg_trgm (trigram search indexing)
- **Frontend:** React 19, Vite, TanStack Query, Lucide Icons, Vanilla CSS Design System (WCAG 2.1 AA compliant)
- **Infrastructure:** Docker Compose V2, Nginx, Render Blueprint (`render.yaml`), Supabase PostgreSQL
