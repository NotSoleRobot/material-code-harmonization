# TASK SPECIFICATION: Production-Grade Remediation & Generalization for National Unified Material Master (NUMM)

## 1. Context & Architectural Overview
You are tasked with resolving 4 critical production bottlenecks and architectural deficiencies in the NUMM (National Unified Material Master) codebase. 
The system is currently over-fitted to a synthetic 9-category taxonomy with static multipliers and synchronous ingestion limitations. You must refactor the system into an enterprise-ready, open-domain MRO harmonization platform.

### Repository Layout
- `material-master-backend/` (Spring Boot 3.x, Java 21, JPA/Hibernate, Flyway, PostgreSQL)
- `matching-service/` (Flask, Python 3.10+, Scikit-Learn, TF-IDF, Regex NLP)
- `frontend/` (React, Vite, React Query, Lucide Icons, Vanilla/Tailwind CSS)

---

## 2. Inconsistencies & Targeted Engineering Directives

### TASK 1: Open-Domain Material Ingestion & Fallback NLP Matching (Beyond the 9 Synthetic Categories)
#### Identified Inconsistencies:
1. In `MaterialController.java` (`resolveCategory`), any uploaded material whose category is not among the 9 predefined categories (`PIPE`, `VALVE`, `FLANGE`, `GASKET`, `BEARING`, `MOTOR`, `CABLE`, `PUMP`, `FASTENER`, `FILTER`) is rejected with:
   `"is outside the configured taxonomy; row skipped."`
2. In `matching-service/app.py` and `matching-service/src/data_generation/schemas.py`, `/extract-attributes` and `/schema/<category>` return HTTP 404 or empty sets for general industrial MRO categories (e.g., `ELECTRICAL`, `CIVIL`, `SAFETY`, `LUBRICANT`, `INSTRUMENTATION`).
3. In `inference.py`, non-standard categories lack feature extraction weighting and risk pipeline exceptions during similarity scoring.

#### Step-by-Step Implementation Instructions:
1. **Backend Database & Taxonomy Resolution (`material-master-backend/`)**:
   - In `MaterialCategoryRepository.java`:
     - Ensure lookup by name is case-insensitive.
     - Add a lookup or create method for a default general category: `GENERAL_MRO` with category code `GEN` or auto-provision new categories dynamically if marked open.
   - In `MaterialController.java`:
     - Modify `resolveCategory(String categoryLabel)`: If the category label is not in `CATEGORY_DISPLAY_LABELS` and does not exist in `material_categories`, do NOT drop the row. Instead, fall back to resolving or lazily creating a canonical `GENERAL_MRO` category record (`is_custom = true`).
2. **Matching Service Fallback NLP (`matching-service/`)**:
   - In `matching-service/src/preprocessing/attribute_extraction.py`:
     - Implement generic open-domain feature extraction for unlisted categories:
       - **Dimensions/Sizes**: `(?i)(\b\d+(?:\.\d+)?\s*(?:MM|INCH|IN|M|CM|DN|NB|OD|ID)\b)`
       - **Standards & Grades**: `(?i)\b(ASTM|ASME|IS|DIN|ISO|BS|SAE|AISI|IEC|API)\s*[\w\d.-]+`
       - **Core Industrial Materials**: `(?i)\b(CARBON STEEL|MILD STEEL|STAINLESS STEEL|CS|MS|SS304|SS316|BRASS|BRONZE|COPPER|PVC|HDPE|ALUMINIUM|ALUMINUM)\b`
       - **Model / Part Identifiers**: `\b[A-Z0-9]{3,}(?:-[A-Z0-9]+)+\b`
   - In `matching-service/app.py`:
     - For `/schema/<category>`: If the category is not present in `CATEGORIES`, return HTTP 200 with standard fallback fields rather than a 404:
       ```json
       {
         "category": "<CATEGORY>",
         "identity_critical": ["material"],
         "variant_critical": ["nominal_size_mm", "standard"],
         "all_fields": ["material", "grade", "nominal_size_mm", "standard", "model"]
       }
       ```
   - In `matching-service/src/matching/inference.py`:
     - Prevent `KeyError` exceptions when comparing items from open categories. Fall back to weighted text similarity (TF-IDF + token Cosine similarity + Levenshtein distance) when domain-specific attributes are sparse.

---

### TASK 2: Chunked, Stream-Based CSV Ingestion & Worker Decoupling
#### Identified Inconsistencies:
1. In `MaterialController.java` (`processCsv`), large CSV files are read and processed synchronously inside the controller thread. For large datasets (>10,000 items), this creates HTTP gateway timeouts (504) on proxies (Nginx/Render).
2. The entire list of newly created IDs is accumulated in-memory (`List<Long> newlyCreatedIds = new ArrayList<>()`), risking heap pressure and Out-Of-Memory (OOM) errors during bulk loads.

#### Step-by-Step Implementation Instructions:
1. **Asynchronous Controller Endpoint (`MaterialController.java`)**:
   - Refactor `POST /api/materials/bulk-csv`:
     - Stream incoming CSV into a temporary spool file or pass the stream directly to an async task runner.
     - Immediately register a `HarmonizationJob` with status `QUEUED` or `PROCESSING_INGESTION`.
     - Return HTTP 202 Accepted with a payload:
       ```json
       {
         "jobId": 123,
         "status": "QUEUED",
         "message": "File accepted for background ingestion and harmonization."
       }
       ```
2. **Chunked Background Ingestion (`HarmonizationJobService.java`)**:
   - Implement `@Async("taskExecutor") public void processCsvAsync(Long jobId, InputStream inputStream, Long operatorCpseId, User user)`:
     - Use Apache Commons CSV to stream rows in fixed batches (`BATCH_SIZE = 500`).
     - Insert/Upsert batches via JPA/JdbcTemplate and invoke `entityManager.flush()` and `entityManager.clear()` after each batch to prevent Hibernate first-level cache memory leaks.
     - Periodically update `HarmonizationJob` record counts (`processed_records`, `total_records`, `skipped_records`).
     - Dispatch harmonization matching tasks in parallel chunks of 100-200 materials as they are committed.
3. **Frontend Polling & Visual Feedback (`frontend/src/components/IngestionWizard.jsx`)**:
   - Ensure the wizard transitions smoothly to job progress tracking upon receiving HTTP 202, polling `/api/jobs/{id}` until completion and rendering accurate row-level progress bars.

---

### TASK 3: Defensible, Dynamic Financial ROI & Procurement Analytics
#### Identified Inconsistencies:
1. In `AnalyticsService.java`, the savings calculation uses static, hardcoded multiplier constants:
   `BigDecimal carryingCost = 45000; BigDecimal cleanupAvoided = 25000; BigDecimal adminSavings = 15000;`
   `totalSavings = (carryingCost + cleanupAvoided + adminSavings) * duplicatesEliminated;`
   This is an artificial metric that fails scrutiny during executive procurement audits.
2. The platform now captures `nominal_price` across materials and mappings, but does not use it to compute actual price arbitrage across CPSEs.

#### Step-by-Step Implementation Instructions:
1. **Dynamic Savings Engine (`AnalyticsService.java`)**:
   - Refactor `getDashboardStats()` to compute real, defensible savings:
     - **Direct Procurement Arbitrage (Inter-CPSE Price Spread)**:
       For every active `MaterialGroup` with 2 or more CPSE members having recorded `nominal_price`:
       $$\text{Arbitrage Savings} = \sum_{g \in \text{Groups}} (\max(P_g) - \min(P_g)) \times Q_g$$
       *(Where $Q_g$ defaults to estimated annual volume or 1 if volume data is absent).*
     - **Inventory Carrying Cost Savings**:
       Derive annual inventory carrying savings dynamically as a defensible percentage (default: 20% p.a., standard industrial inventory holding cost) of the median nominal value of redundant catalog items:
       $$\text{Carrying Savings} = \sum_{m \in \text{DuplicateItems}} (P_m \times \text{CarryingCostRate})$$
     - **Administrative & Master Data Overhead Avoidance**:
       Retain configurable baseline constants via `ProcurementAssumptionRepository`, but categorize them explicitly into transparent line items:
       - Direct Price Arbitrage Savings (Lakhs INR)
       - Inventory Holding Avoidance (Lakhs INR)
       - Admin & Data Cleanup Avoidance (Lakhs INR)
2. **DTO & UI Synchronization**:
   - Update `DashboardStatsDto.java` to expose these granular breakdowns.
   - In `DashboardView.jsx`: Render the split-savings breakdown card with clear tooltips explaining the procurement formulas.

---

### TASK 4: Search & Catalog Full-Page Pagination (Backend & Frontend)
#### Identified Inconsistencies:
1. In `CodeController.java` (`searchCodes`):
   `groupRepository.searchGroups(query, effectiveStatus, PageRequest.of(0, 50)).getContent();`
   Hardcodes `PageRequest.of(0, 50)`, preventing users from retrieving more than 50 national codes.
2. In `CatalogView.jsx`, pagination controls are absent, restricting catalog inspection.

#### Step-by-Step Implementation Instructions:
1. **Backend Pageable Endpoint (`CodeController.java`)**:
   - Update `/api/codes/search`:
     ```java
     @GetMapping("/search")
     public ResponseEntity<Page<NationalCodeDetailsDto>> searchCodes(
             @RequestParam(required = false) String q,
             @RequestParam(required = false, defaultValue = "ACTIVE") String status,
             @RequestParam(defaultValue = "0") int page,
             @RequestParam(defaultValue = "20") int size,
             @AuthenticationPrincipal UserPrincipal viewer)
     ```
   - Bind `Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100), Sort.by(Sort.Direction.DESC, "createdAt"));`.
2. **Frontend Pagination (`frontend/`)**:
   - In `frontend/src/services/api.js`:
     - Update `searchCodes(query, status, page = 0, size = 20)` to pass `page` and `size` query parameters.
   - In `frontend/src/components/CatalogView.jsx`:
     - Add `page` and `pageSize` state synced with URL search parameters.
     - Implement table pagination controls:
       - Page indicator ("Page X of Y")
       - Next / Previous buttons (disabled on bounds)
       - Page size selector dropdown (`20`, `50`, `100`).

---

## 3. Quality Gates & Verification Commands

Execute the following verification suite prior to finalizing any pull request or deployment:

### 1. Python Matching Engine Tests
```bash
cd "matching-service"
pytest -v tests/
```
*Expected: All 10+ matching, inference, and attribute extraction tests pass, including open-domain schemas.*

### 2. Spring Boot Backend Tests
```bash
cd "material-master-backend"
./mvnw clean test
```
*Expected: All 69+ unit/integration tests pass with 0 failures.*

### 3. Frontend Production Build
```bash
cd "frontend"
npm run build
```
*Expected: Vite builds bundle to `dist/` cleanly without JSX syntax or lint errors.*

---

## 4. Deliverable Constraints & Coding Standards
- Do NOT use placeholder comments, incomplete stubs, or `// TODO` statements.
- Ensure all database queries leverage existing GIN/trigram indexes without full table scans.
- Preserve backward compatibility for all existing API consumers and automated seed scripts.
