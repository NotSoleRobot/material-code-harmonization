# NUMM: code-verified presentation analysis

Verified against this checkout on 28 September 2026. This is a code audit, not evidence of deployment, national adoption, engineering interchangeability or procurement savings. The requested `NUMM_SIH_Presentation_Analysis.md` was not found, including a hidden/ignored-file search; therefore a line-by-line correction of that document was not possible. This replacement covers A1–C9.

Verification: matching-service pytest **11 passed**; Maven **68 tests, 0 failures/errors/skips** (39 security-route, 2 analytics, 3 audit, 3 governance, 3 harmonization, 4 code-generator, 14 checksum invocations). The security tests use a probe controller; service tests mock repositories. They do not constitute a PostgreSQL/full-browser integration test. Raw model/extraction outputs are in [presentation_verification_results.json][probes]; checksum execution is in [presentation_checksum_results.json][checksumresults]. Saved ablation metrics are historical results, not a newly rerun training evaluation.

## 1. Proposed Solution (detailed explanation of the solution)

| Point | What the code does | Evidence (file:function) | Numbers |
|---|---|---|---|
| A1: ingest | Browser parses source text, suggests header aliases, permits manual mapping, previews validation and emits canonical CSV. Server parses CSV and upserts by CPSE + plant code. | [IngestionWizard.jsx][wizard]: `processCsvText`, `autoMap`, `generateStandardCsv`; [MaterialController.java][ingest]: `processCsv` | 4 wizard stages; first 6 preview rows |
| A1: process | After transaction commit, async job extracts attributes, prepares candidate comparisons, then proposes each mapping. | [MaterialController.java][ingest]: `dispatchAfterCommit`; [HarmonizationJobService.java][job]: `processAsync` | batches of 10; progress saved every 5; UI polls every 1500 ms |
| A2: extraction | Category-specific regex/vocabulary extraction returns null for unavailable fields; HTTP layer renames raw fields into schema keys. | [attribute_extraction.py][extract]: `extract_attributes`; [app.py][flask]: `extract_attributes_endpoint`; [feature_engineering.py][features]: `FIELD_TO_SCHEMA_KEY` | 10 Python categories; default UI/DB taxonomy has 8 |
| A3: identity | SHA-256 of sorted JSON containing category and identity-critical values; incomplete identity returns no signature. Complete signature is an early group lookup key. | [NationalCodeGenerator.java][code]: `computeAttributeSignature`; [HarmonizationService.java][harm]: `harmonizeMaterial` | 64 hexadecimal characters; deterministic proposal score 1.0 |
| A4: retrieval | First same-category DB rows by material ID, then Python category blocking, word TF-IDF shortlist, full hybrid scoring and probability filtering. | [MaterialRepository.java][materials]: `findCandidatesByCategory`; [inference.py][infer]: `find_matches` | DB 50 → TF-IDF 25 → default 5 results; keep probability ≥0.30 |
| A4: proposal | Exact/near duplicate with sufficient match probability can join a candidate's group; equivalent/variant can create a group relation; otherwise create/reuse a proposed group. Every resulting mapping remains PENDING. | [HarmonizationService.java][harm]: `harmonizeMaterial`, `createOrUpdateMapping` | Proposal threshold **0.60**, not comment's 0.85; HIGH ≥0.85, MEDIUM ≥0.60, otherwise LOW |
| A5: model | Random forest consumes lexical similarity and attribute agreement/conflict/missingness features. | [train.py][train]: `main`, `fit_tfidf`; [feature_engineering.py][features]: `FEATURE_NAMES`; [probe output][probes]: `artifact_classifier_params` | 17 features; 300 trees; depth 18; balanced weights; seed 42; n_jobs=-1 |
| A6: governance | Reviewer confirmation is separate from senior publication; same ordinary reviewer's CPSE is blocked; publisher cannot be a confirmer of any currently confirmed mapping in the group. | [GovernanceService.java][gov]: `decideMapping`, `mintGroup`; [SecurityConfig.java][security]: `securityFilterChain` | At least 1 confirmed mapping; distinct confirmer and publisher |
| A7: national code | Category supplies segment/family/class; PostgreSQL sequence supplies serial; check character covers the delimiter-free entire body, including NUMM. | [NationalCodeGenerator.java][code]: `mintNationalCode`; [Iso7064Mod3736.java][iso]: `computeCheckChar` | `NUMM-SS-FF-CC-NNNNNN-K`; executed example `NUMM-40-14-07-000042-E` |
| A8: output | Cross-reference and catalog CSV/XLSX; SAP-column CSV; authenticated code lookup/validation; dashboard and procurement reports. | [ExportService.java][export]: `streamCrossReference`, `streamMasterCatalog`, `streamSapErpTemplate`; [CodeController.java][codes]: `validateCode`; [AnalyticsService.java][analytics] | Exact columns/formulas below |

### A1. One row end to end

Use the existing wizard sample row:

```csv
mat_no,short_text,spec_grade,base_uom,material_group
ONGC-PP-2001,CARBON STEEL PIPE DN50 SCH40 SMLS,IS 1239,MTR,PIPE
```

1. **Upload/parsing:** `IngestionWizard.processCsvText` calls `Papa.parse` with `header:true, skipEmptyLines:true`; output is header names and row objects. `.csv/.tsv/.txt` are offered. XLSX import is **NOT IMPLEMENTED**. [wizard]
2. **Mapping:** `autoMap` searches substring aliases: `mat_no→cpse_material_code`, `short_text→description`, `spec_grade→specification`, `base_uom→unit_of_measure`, `material_group→category`. The operator can correct these. `generateStandardCsv` adds selected CPSE and defaults missing UOM to `NOS`. [wizard]
3. **Storage:** `uploadCsvText → processCsv` uses Apache Commons CSV, case-insensitive headers and trimming. An OPERATOR's CPSE comes from their principal, ignoring CSV CPSE. It resolves category and upserts `(cpse_id, cpse_material_code)`, storing description/spec/UOM/category. Blank description/code/category and unsupported categories are skipped with row messages. Unknown CPSE on the admin path raises an error. [ingest]
4. **Dispatch/extract:** upload returns imported/skipped counts/messages/job ID. `dispatchAfterCommit` starts `processAsync`; it calls Python `/extract-attributes` through `PythonMatchingClient.extractAttributes`. The example gives schema fields `material=CS, nominal_size_mm=50.0, schedule=SCH40, standard=IS1239, grade=null`; Java stores the attribute JSON and extraction timestamp. This row's attributes follow directly from the extractor; the executed seed examples below provide runtime evidence. [job] [client] [extract] [flask]
5. **Signature:** PIPE identity keys are only `[material]`; payload is `{"__category":"PIPE","material":"CS"}`. Hash is `227358EAA9F31C1B8C62BE4FEABC89FAB7328FECB57AFFD9CB23E6526E1AB2CD`. If a group already has that signature, the mapping goes to it with basis `DETERMINISTIC_SIGNATURE`, HIGH/1.0, bypassing ML decision logic. [schema] [code] [harm] [probes]
6. **Candidate/scoring branch:** if no signature hit, fetch up to 50 candidates, use up to 25 TF-IDF candidates, score and return up to 5. Empty candidates create a novel group. An exact/near duplicate at ≥0.60 can join an existing group. In the batch path, ML results are prepared **before** per-item signature checks, so the shortcut does not avoid that already-performed batch work. [harm] [job] [infer]
7. **Review:** group initially PROPOSED, reference `PROV-<current year>-<sequence padded to 6>`; mapping PENDING. Assigned reviewer calls `/api/mappings/{id}/approve`; successful decision becomes CONFIRMED, group remains PROPOSED. [harm] [gov]
8. **Publication/code:** a different SENIOR_REVIEWER calls `/api/groups/{id}/mint`; group becomes ACTIVE and gets a new sequence-derived NUMM code. Exact IDs/serials depend on existing DB state. Provisional creation and final minting both consume the same sequence, so their serials need not match. [gov] [code] [security]

These are alternative branches, not an assertion that every uploaded row passes through ML. No live ingestion/publication was performed during this audit.

### A2/C2. All category fields and their classification

Below are **HTTP/schema key names**, after `FIELD_TO_SCHEMA_KEY` translation. All fields are nullable. Source: [attribute_extraction.py][extract] `CATEGORY_FIELDS`, [feature_engineering.py][features] `FIELD_TO_SCHEMA_KEY`, [schemas.py][schema] `CATEGORIES`.

| Category | Every extracted field | Identity-critical | Variant-critical |
|---|---|---|---|
| PIPE | material, nominal_size_mm, grade, standard, schedule | material | nominal_size_mm, schedule, grade |
| VALVE | valve_type, body_material, nominal_size_mm, pressure_class, standard | valve_type, body_material | nominal_size_mm, pressure_class |
| FLANGE | flange_type, material, nominal_size_mm, pressure_class, standard | flange_type, material | nominal_size_mm, pressure_class |
| GASKET | gasket_type, material, nominal_size_mm, pressure_class, standard | gasket_type, material | nominal_size_mm, pressure_class |
| BEARING | bearing_type, bearing_number, bore_mm, seal_type | bearing_type, bearing_number | bore_mm, seal_type |
| MOTOR | power_kw, voltage_v, phase, rpm | voltage_v, phase | power_kw, rpm |
| CABLE | conductor, cores, csa_sqmm, voltage_grade_kv, standard | conductor | cores, csa_sqmm, voltage_grade_kv |
| PUMP | pump_type, power_hp, phase | pump_type | power_hp, phase |
| FASTENER | fastener_type, size, grade | fastener_type | size, grade |
| FILTER | filter_type, micron_rating, connection_size_mm | filter_type | micron_rating, connection_size_mm |

The schema also describes non-critical fields that are **not extracted** in some categories: manufacturer, enclosure, armour and fastener coating. Motor/pump/fastener standards are likewise not in their extractor field lists. The schema endpoint's `all_fields` comes from generation-domain `attrs`, so it is not an exact list of extraction output keys. [schema] [flask]

**Five seed descriptions plus an alias probe → exact attributes:** descriptions/specifications 1–5 come from `scripts/seed_demo.ps1`; input 6 is an explicitly constructed extractor probe, not production CPSE data. All outputs were produced by the real Flask extraction function. [seed] [probes] [extract] [flask]

| Input description; separate specification | Exact returned attributes |
|---|---|
| PIPE: `CS SEAMLESS PIPE 50MM SCH40 ASTM A106 GRB`; `ASTM A106` | `{"grade":"B","material":"CS","nominal_size_mm":50.0,"schedule":"SCH40","standard":"ASTMA106"}` |
| PIPE: `CARBON STEEL PIPE DN50 SCHEDULE 40 GR.B IS1239`; `IS 1239` | `{"grade":"B","material":"CS","nominal_size_mm":50.0,"schedule":null,"standard":"IS1239"}` |
| PIPE: `SS 316L PIPE 1IN SCH10S ASTM A312`; `ASTM A312` | `{"grade":null,"material":"SS","nominal_size_mm":null,"schedule":null,"standard":"ASTMA312"}` |
| VALVE: `GATE VALVE CS CLASS150 2INCH RF API600`; `API 600` | `{"body_material":"CS","nominal_size_mm":50.0,"pressure_class":"CLASS150","standard":"API600","valve_type":"GATE"}` |
| BEARING: `BALL BEARING 6308 2RS C3`; `ISO 15` | `{"bearing_number":"6308","bearing_type":null,"bore_mm":null,"seal_type":"2RS"}` |
| FLANGE: `WELD-NECK FLANGE SS304 DN50 150# ASME B16.5`; empty specification | `{"flange_type":"WELDNECK","material":"SS304","nominal_size_mm":50.0,"pressure_class":"CLASS150","standard":"ASMEB16.5"}` |

The bearing returns `identity_critical_present:false` and `missing_identity_keys:["bearing_type"]`; the other five return true, including the severely incomplete third row. This flag means identity keys present, not complete engineering specification. [probes]

`normalize_size_mm` tries inch tokens first, DN second, mm third. Exact nominal table: `{0.5:15,1:25,1.5:40,2:50,2.5:65,3:80,4:100,6:150,8:200}`; other inch values use `round(inch*25.4,1)`. Thus **2 inch → 50.0**, not 50.8. This table applies wherever this helper is used, including bearing bore; it is not a universal dimensional conversion. `1IN` is unsupported. [extract]

Aliases include Carbon Steel/CS→CS, Stainless Steel/SS→SS, SS304→SS304, SS316→SS316, plus MS/CI/GI/PVC/bronze/copper/aluminium/CAF/PTFE/graphite. `TP304` and `TYPE 304` become grade TP304; `SS304` is a material token, not automatically split into SS + TP304. Executed `SS PIPE 2 inch SCH40 TP304 ASTM A312` and `STAINLESS STEEL PIPE 50mm SCH40 TYPE 304 ASTM A312` produce identical attributes and NEAR_DUPLICATE/0.9967/HIGH. [extract] [probes]

Standard regexes cover IS, ASTM, DIN, API, ASME, ISO, IEC; return the first matching **pattern family**, uppercase with spaces removed. Multiple standards are not collected. `API 5L` is not fully recognized by `API\s?\d+\b`; `SCHEDULE 40` and `SCH10S` are unsupported by `extract_schedule`. [extract]

### A3–A5. Signature, stages and model details

**Signature:** `computeAttributeSignature` requires a nonempty identity-key list and nonblank values for every key. It returns `(null,false)` otherwise. It builds a `TreeMap`, adds uppercase trimmed category under `__category`, and uppercase trimmed **string** values for identity keys; serializes JSON and hashes UTF-8 bytes. It does not include variants, standard, UOM, CPSE or plant code. Numeric strings are not canonicalized, so `50` and `50.0` can differ if manually supplied. Complete signatures have a partial unique DB index; incomplete groups can coexist with null signatures. [code] [v4]

**Risk:** PIPE CS/50mm and CS/200mm have the same signature. The early signature lookup has no group-status filter and emits an explanation with no conflicts. This can bypass the model's correct VARIANT result and attach to a REJECTED/DEPRECATED group. A SHA-256 hash does not fix an under-specified identity schema. [harm] [schema]

**Exact retrieval/decision order:** [harm] `harmonizeMaterial`, `findCandidateEntities`; [materials] repository queries; [infer] `find_matches`, `compare_materials`.

1. Extract if attributes absent; obtain cached category schema; compute signature.
2. Complete signature hit → immediate pending mapping, score 1.0, HIGH.
3. Otherwise retrieve first 50 same-category rows ordered by ascending material ID, excluding target. If empty/category absent, fallback to first 50 global rows.
4. Python rejects other categories; word TF-IDF cosine sorts the surviving pool and retains 25. These caps bound scoring cost; they can lose a better candidate outside the first 50.
5. Full classifier computes probabilities on 17 features. Keep candidates with rounded `match_probability >=0.30`; sort by it; return top 5 (HTTP top_k accepts 1–50).
6. Backend uses top candidate only. EXACT_DUPLICATE/NEAR_DUPLICATE and score ≥0.60 → duplicate proposal; FUNCTIONALLY_EQUIVALENT/VARIANT and score ≥0.60 → possible relation between different groups. Other cases create/reuse a group. VARIANT has a structural mismatch here: its own class probability is **excluded** from match probability, so a confidently predicted VARIANT is normally filtered out before relation creation.
7. Every new mapping is PENDING, including HIGH. There is no automatic final publication.

`PythonMatchingClient.getCategorySchema` caches schema for 10 minutes; network defaults are connect 5000 ms/read 60000 ms. Numeric attribute comparisons universally use absolute difference `<0.6`, including sizes, powers, voltages, core counts and micron ratings; categorical comparison ignores case/spaces. This is a heuristic, not category-specific engineering tolerance. [client] [features]

**All features**, in model vector order, from [features] `FEATURE_NAMES` and associated functions:

| Family | Feature | Meaning |
|---|---|---|
| Structured | same_category | category equality flag |
| Structured | n_attrs_compared, n_attrs_agree, n_attrs_conflict | counts where both values exist; agreement/conflict counts |
| Structured | frac_agree | agree/compared; defaults to 0.5 if nothing comparable |
| Structured | identity_critical_conflict, variant_critical_conflict | any disagreement in the respective schema class |
| Structured | any_type_conflict | OR of the preceding conflict flags, not only subtype conflict |
| Structured | identity_critical_missing, variant_critical_missing | critical value present on exactly one side |
| Structured | n_critical_missing | count of such one-sided missing critical fields; both missing is not counted |
| Text | fuzz_ratio, fuzz_token_sort, fuzz_partial | RapidFuzz ratios divided by 100 |
| Text | len_ratio | min/max normalized-text lengths; 1.0 if both empty |
| Text | tfidf_word_cosine, tfidf_char_cosine | vector dot products on normalized description + specification |

Text normalization uppercases, replaces non-word/non-whitespace characters with spaces, then collapses whitespace. TF-IDF uses word ngrams `(1,2)` and `char_wb` ngrams `(2,4)`, both `min_df=2`. Random forest explicit parameters are shown in the table; loaded artifact also has `criterion=gini`, `max_features=sqrt`, `bootstrap=true`, `min_samples_split=2`, `min_samples_leaf=1`, `ccp_alpha=0.0`. Full parameters and artifact hashes are saved in the probe JSON. [features] [train] [probes]

Six labels are EXACT_DUPLICATE, NEAR_DUPLICATE, FUNCTIONALLY_EQUIVALENT, VARIANT, NOT_A_MATCH, NEEDS_REVIEW. Predicted relationship is the class with maximum probability; `label_probability` is that probability. Runtime `match_probability = P(EXACT)+P(NEAR)+P(FUNCTIONALLY_EQUIVALENT)`; `confidence` is its alias. Returned probabilities round to 4 decimal places; tier uses the unrounded probability. Empty description or comparison exception returns NEEDS_REVIEW, all headline probabilities 0.0, LOW, and a warning. Missing attributes alone do **not** force NEEDS_REVIEW. [infer]

Training generator creates canonical attribute combinations, textual variants, same-canonical positives, one-attribute neighbor negatives/variants, curated equivalence twins, random same-/cross-category negatives, and critical-attribute omission pairs. Defaults: seed 42, 4 renderings/canonical, max 250/category, max 6 positives/canonical, 6000 random negatives, 2000 cross-category negatives, 1500 needs-review attempts, 15 twins/equivalence rule and up to 3 pairs/twin. These are generator defaults, not asserted dataset totals. Random different-canonical same-category pairs are labelled NOT_A_MATCH without rechecking engineering equivalence. [generate] `build_canonical_materials`, `build_pairs`, `main`.

`canonical_group_split` assigns canonical IDs with seed 42 into 0.70 train, 0.15 validation, remaining test. A pair is retained only if both IDs share a split; cross-split pairs are dropped. TF-IDF is fit on training text only. Saved pair counts are **19601 / 2514 / 2635**, not a 70/15/15 split of rows. ID separation does not prove that equivalent attribute combinations have unique canonical IDs. [train] [metrics]

### A6. Actual state machine and permissions

| Object/action | Transition/check | Effective caller and error |
|---|---|---|
| Ingest/harmonize | New mapping→PENDING; existing PENDING updated | OPERATOR own material/CPSE, or ADMIN; admin-only harmonize-all |
| Individual review | PENDING→CONFIRMED or REJECTED (`APPROVE` aliases CONFIRMED) | REVIEWER assigned category or SENIOR_REVIEWER; own-CPSE ordinary reviewer gets 409 CONFLICT_OF_INTEREST; unassigned gets 403; already decided gets 409 |
| Edit | PENDING remains PENDING; only PROPOSED group's description/spec/UOM can change | REVIEWER assigned category or SENIOR_REVIEWER; wrong mapping/group state gets 409. Edit does not recompute signature/attributes. |
| Bulk approve | HIGH PENDING candidates→CONFIRMED, through individual decision logic | REVIEWER/SENIOR_REVIEWER; skips explanation strings containing `identity_critical`; skips individual errors including COI |
| Re-harmonize confirmed material | Old CONFIRMED→SUPERSEDED; new PENDING row | OPERATOR own material or ADMIN. Repository excludes REJECTED from active lookup, so the code's REJECTED supersede branch is not reached through that query. |
| Administrative supersede endpoint | Existing row directly→CONFIRMED or REJECTED | ADMIN/SENIOR_REVIEWER; reason trimmed length ≥5, otherwise 400. No prior-state restriction; any newDecision other than CONFIRMED/APPROVE maps to REJECTED. Despite its comment/name it does not set old row SUPERSEDED or create a replacement. |
| Publish group | PROPOSED→ACTIVE; mint code | HTTP permits SENIOR_REVIEWER only, although service also allows ADMIN. At least 1 CONFIRMED mapping, otherwise 409; same publisher as any current confirmer gets 403; non-PROPOSED gets 409. |
| Last usable mapping rejected | PROPOSED→REJECTED or ACTIVE→DEPRECATED if no CONFIRMED/PENDING remains | Triggered by review/override, not a standalone group-management transition |
| Group SUPERSEDED | Mentioned in schema/entity comments | **NOT IMPLEMENTED** as an operational transition in the inspected services |

Evidence: [gov] `decideMapping`, `applyDecision`, `editMapping`, `bulkApproveHighConfidence`, `checkAssignment`, `supersedeMapping`, `mintGroup`; [harm] `createOrUpdateMapping`; [mappings] `findActiveByMaterialId`; [security] `securityFilterChain`; [errors] exception handlers; [v1] group/mapping schema.

Senior reviewers bypass ordinary category assignment and own-CPSE checks. The COI exception says “escalated,” but automatic reassignment/escalation state is **NOT IMPLEMENTED**. Bulk conflict checking parses explanatory strings and treats missing/malformed JSON as no conflict. Rejecting/re-harmonizing does not enforce a complete global group lifecycle. [gov] [harm]

### A7. Code construction and validation

Seed category prefixes are PIPE `40-14-07`, VALVE `40-14-16`, FLANGE `40-14-17`, PUMP `40-15-15`, BEARING `31-17-15`, FASTENER `31-16-15`, MOTOR `26-10-11`, CABLE `26-12-16`. GASKET/FILTER have no seeded leaf prefixes. These are the project's seeded “UNSPSC-aligned” values, not externally verified classification compliance. Missing category segments default individually to `40`, `14`, `00`. [v2] [code]

`getNextSerial` executes `SELECT nextval('numm_serial_seq')`; formatting uses `%06d` (minimum width). The validator requires **exactly six digits**, so serials above 999999 are an unhandled format limit. DB UNIQUE constraints protect national/provisional codes; minting locks the group row. [code] [iso] [v1] [gov]

Algorithm from `Iso7064Mod3736.computeCheckChar`: alphabet `0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ`, initialize `p=36`; for each body character value `a`, set `s=(p%37)+a`, `m=s%36`, `p=(m==0?36:m)*2`; final character is alphabet index `(37-(p%37))%36`. `validate` trims/uppercases, requires `^NUMM-\d{2}-\d{2}-\d{2}-\d{6}-[0-9A-Z]$`, then recomputes check character. [iso]

`GET /api/codes/{code}/validate` returns HTTP 200 with valid/message and parsed segments, even when invalid; it checks syntax/checksum, **not DB existence/publication**. `/api/codes/{code}` lookup separately requires an ACTIVE group. Python `/generate-code` produces a different provisional hash-style `PIPE-<8 hex>` string and is not authoritative minting. [codes] [infer] `generate_national_code`.

### A8. Outputs and exact analytics

| Output | Columns / computation | Evidence |
|---|---|---|
| Cross-reference CSV | CPSE, Plant_Material_Code, Raw_Description, Specification, National_Material_Code, Provisional_Ref, Standardized_Description, Commodity_Class, UOM, Mapping_Status, Confidence_Tier, Confidence_Score | [export] `writeCrossReferenceCsv` |
| Cross-reference XLSX | CPSE, Plant Code, Raw Description, Specification, National Code, Provisional Ref, Standardized Description, Category, UOM, Status, Confidence Tier, Score | [export] `writeCrossReferenceXlsx` |
| Catalog CSV | National_Material_Code, Provisional_Ref, Status, Standardized_Description, Specification, UOM, Category, UNSPSC_Segment, UNSPSC_Family, UNSPSC_Class, Linked_CPSE_Count, Total_Member_Materials | [export] `writeMasterCatalogCsv` |
| Catalog XLSX | National Code, Provisional Ref, Status, Standardized Description, Specification, UOM, Category, Segment, Family, Class, CPSE Count, Total Mappings | [export] `writeMasterCatalogXlsx` |
| SAP CSV | MATNR=plant code; MAKTX=group standardized description; MEINS=raw UOM/default NOS; MATKL=category name; NUMM_CODE=national code or provisional reference. UTF-8 BOM. | [export] `streamSapErpTemplate` |
| Dedup | D=Σ over ACTIVE groups with n>1 CONFIRMED mappings (n−1); reviewed=CONFIRMED count+REJECTED count; rate=round(100D/reviewed,1), zero if reviewed=0 | [analytics] `getDashboardStats`; [mappings] `countConfirmedDuplicatesEliminated` |
| Estimated savings | `D*(carrying_cost_annual_inr + master_data_cleanup_avoided_inr + admin_overhead_reduction_inr)/100000`, HALF_UP 2 decimal places in lakhs. Defaults 45000+25000+15000. | [analytics] `getDashboardStats`; [v2] assumptions |
| Rate-contract candidates | CONFIRMED mappings grouped by material group; distinct CPSE count ≥2; priority string HIGH_PRIORITY_JOINT_GEM_CONTRACT if ≥3, otherwise RECOMMENDED_RATE_CONTRACT. Member count is mapping count. | [mappings] `findRateContractCandidates`; [analytics] `getRateContractCandidates` |
| Price variance | CONFIRMED, non-null nominal_price; ≥2 distinct CPSEs; max>min. Spread=max−min; percent=`round_HALF_UP(spread/min,4)*100`, or 0 when min≤0. Per-CPSE min/max/average, average rounded HALF_UP to 2 decimals. | [mappings] `findPriceVarianceGroups`, `findCpsePriceSummaries`; [analytics] `getPriceVarianceReport` |

Export endpoints are `/api/export/cross-reference?format=csv|xlsx`, `/api/export/catalog?format=csv|xlsx`, `/api/export/erp-template`. Operator exports force own CPSE; catalog export is senior/admin. Master export uses **all groups and all mapping statuses** for counts, unlike ACTIVE-only code search and confirmed-only analytics. SAP export can contain provisional codes and blank MAKTX for unmapped records. [exportcontroller] [export]

Dashboard also counts all materials/groups, mapping statuses, unmatched materials, CPSEs, category distribution, and per-CPSE distinct materials with PENDING/CONFIRMED mappings. “Total groups” is not “minted national codes.” Rate/price queries do not require ACTIVE group status. “Savings” mixes recurring, one-off and per-tender assumptions; the seeded `price_variance_recovery_pct=3.50` is not used by this savings formula. [analytics] [materials] [mappings] [v2]

**Five strongest points**

1. Legacy CSV headers are mapped to canonical fields before a CPSE-scoped server upsert. [wizard] [ingest]
2. The matching service extracts category-specific structured attributes and preserves unknowns as null. [extract]
3. Candidate scoring combines 17 lexical/attribute features in a local random forest. [features] [train]
4. Proposed mappings require review, and a separate senior account publishes national codes. [gov] [security]
5. Published codes, cross-references, CSV/XLSX and SAP-column CSV are implemented outputs. [codes] [export]

**Gaps and risks**

- Signature shortcut excludes variant-critical fields and can collapse distinct sizes/grades; its 1.0 score is not measured certainty. [code] [harm]
- Python handles 10 categories; default ingestion taxonomy exposes only 8, excluding GASKET/FILTER. [wizard] [v2]
- The first-50 DB cap and the probability filter can hide suitable candidates or variant relations. [harm] [infer]
- Missing attributes do not guarantee NEEDS_REVIEW; numerical tolerance is shared across units. [features] [infer]
- XLSX input, direct SAP writeback and operational group supersession are **NOT IMPLEMENTED**. [wizard] [export] [gov]

## 2. How it addresses the problem

| Point | What the code does; actual example/result; limitation | Evidence (file:function) | Numbers |
|---|---|---|---|
| B1: cross-CPSE descriptions | Aliases and nominal units can align descriptions. Executed SS/TP304/2-inch vs Stainless Steel/TYPE304/50mm pair gives NEAR_DUPLICATE. Existing ONGC/IOCL CS seed pair actually gives NOT_A_MATCH in direct comparison; signature may still group it. | [extract] `extract_attributes`; [infer] `compare_materials`; [probes] comparison examples 2–3 (zero-based) | Successful pair 0.9967 HIGH; seed pair match 0.34 LOW, NOT_A_MATCH label probability 0.66 |
| B2: false merges | Explicit attribute-conflict features distinguish highly similar text: 50mm vs 200mm→VARIANT; CS vs SS→NOT_A_MATCH. No universal hard conflict veto in inference; signature shortcut defeats size protection in full workflow. | [features] `attribute_comparison_features`; [infer] `compare_materials`; [harm] signature branch; [probes] examples 0–1 | Size pair match 0.0; material pair 0.0033 |
| B3: incomplete data | Null extraction, completeness flag, missingness features and warnings; blank descriptions get NEEDS_REVIEW. Removing CS from the pipe description still gives NEAR_DUPLICATE, not NEEDS_REVIEW. | [extract]; [flask] `extract_attributes_endpoint`; [infer] `compare_materials`; [probes] example 4 | Incomplete pair match 0.7806 MEDIUM; NEEDS_REVIEW probability 0.2116 |
| B4: absent catalog | Novel group gets provisional ref; reviewed group gets sequence-backed unique code; code search exposes ACTIVE groups. Uniqueness is syntactic/DB-enforced, not proof of correct material equivalence. | [harm] `createOrFindGroupForMaterial`; [gov] `mintGroup`; [v1], [v4]; [codes] `searchCodes` | ≥1 confirmation to publish; test serial 42→`NUMM-40-14-07-000042-E` |
| B5: joint procurement | `/api/analytics/rate-contract-candidates` aggregates confirmed group members by CPSE. No verified live qualifying group was measured in this audit. No GeM transaction, tender or quantity aggregation. | [analyticscontroller] `getRateContractCandidates`; [mappings] SQL; [analytics] priority selection | ≥2 CPSEs eligible; ≥3 high-priority |
| B6: price benchmarking | `/api/analytics/price-variance` compares stored nominal_price, returning ranges and CPSE summaries. Existing test supplies min=0/max=10 and returns percentage=0. Regular create/CSV ingestion does not populate nominal_price, so price loading is missing. | [analytics] `getPriceVarianceReport`; [mappings] SQL; [analyticstest] `priceVarianceDoesNotDivideByZero`; [ingest] | Test spread=10; percentage=0; no observed production savings |
| B7: accountability | Route roles, reviewer category assignment, ordinary reviewer COI, separate confirmer/publisher and hash-chained audit are implemented. Test coverage differs by control; see below. | [security]; [gov]; [audit]; [securitytest], [govtest], [audittest] | 39 route tests, 3 governance tests, 3 audit tests passed |
| B8: adoption | SAP/ERP header aliases and manual remapping; same CPSE/code re-upload updates the record; CSV/XLSX cross-reference and SAP-column outputs retain plant codes. This is file exchange, not a validated SAP connector. | [wizard] `autoMap`; [ingest] `processCsv`; [export] `streamSapErpTemplate` | SAP CSV has 5 columns |
| B9: demos | Pairwise normalization, hard-negative comparison, and review→publish→validate are the most defensible demonstrations. Exact steps below. | [probes]; [gov]; [codes]; [seed] | Executed pairwise outputs; workflow outputs conditional on DB state |

### B7. Which tests prove which control?

| Control | Existing test evidence | What remains unverified |
|---|---|---|
| Own-CPSE reviewer decision blocked | [GovernanceServiceTest.java][govtest] `testDecideMapping_ConflictOfInterestBlocked` passes; mocked same-CPSE mapping/reviewer throws ConflictOfInterestException | Real HTTP/DB combination and automatic escalation (not implemented) |
| Separate publication role | [SecurityMatrixTest.java][securitytest] `row13_mintRejectsAdmin`, `row13_mintAllowsSenior`; review route `row10_mappingDecisionAllowsReviewer`, `row10_mappingDecisionRejectsAdmin` | Probe controller tests route authorization, not mint logic |
| Same confirmer cannot publish | [GovernanceService.java][gov] `mintGroup` explicitly checks reviewer user IDs | **No dedicated existing test** for same-user publication rejection was found |
| Operator own-CPSE visibility/ingest/export | [MaterialController.java][ingest] `getAllMaterials`, `getMaterial`, `createMaterial`, `processCsv`; [ExportController.java][exportcontroller]; [CodeController.java][codes] `toDetailsDto` | **No dedicated existing test** proving CPSE isolation was found; generic route tests are not CPSE isolation tests |
| Audit changed payload detected | [AuditServiceTest.java][audittest] `testVerifyChainIntegrity_TamperDetection` passes | Mock repository test, not PostgreSQL trigger/concurrent-writer proof |
| Append-only SQL | [V6 migration][v6] `audit_trail_is_append_only` rejects UPDATE/DELETE | No database integration test found for the trigger |

### B9. Three concrete demo scenarios

**Demo 1 — Normalize two descriptions and explain a match (executed).** Log in, open Material Comparison (POST `/api/harmonization/compare`, or direct Flask `/compare` during local development). Set both categories PIPE, empty specifications. Input A `SS PIPE 2 inch SCH40 TP304 ASTM A312`; B `STAINLESS STEEL PIPE 50mm SCH40 TYPE 304 ASTM A312`. Expected NEAR_DUPLICATE, label probability 0.9967, match probability 0.9967, HIGH. Explanation lists five matching fields and no warnings/conflicts. Use the inputs exactly: changing specification also changes text features. [probes] comparison example 3; [infer] `compare_materials`; [security] comparison route.

**Demo 2 — Near-identical text, different size (executed).** Same comparison screen; categories PIPE and empty specifications. A `CS SEAMLESS PIPE 50MM SCH40 ASTM A106 GRB`; B `CS SEAMLESS PIPE 200MM SCH40 ASTM A106 GRB`. Expected VARIANT, label probability 0.9867, match probability 0.0, LOW; conflict `Nominal Size Mm conflict (variant_critical): 50.0 vs 200.0`. LOW means low duplicate probability, not uncertainty about the VARIANT label. Show this as a **pairwise matching** demonstration; do not claim the current full grouping pipeline preserves this distinction. [probes] example 0; [infer] `_confidence_tier`, `_explain`; [harm] signature branch.

**Demo 3 — Separate review, mint and validation (code-traced, not executed against a live DB).** Use a disposable DB with the project's migrations, matching service and `python-matching` profile. Sign in as seeded ONGC operator; upload the exact single-row wizard CSV from A1. Wait for returned job ID to reach COMPLETED at `/api/jobs/{id}`; inspect its proposed mapping. Sign in as `lead.reviewer@numm.gov.in` (seed assigns PIPE); approve that PENDING mapping through `/api/mappings/{mappingId}/approve`. Sign in as the distinct `senior.reviewer@numm.gov.in`; select its PROPOSED group from `/api/groups/publishable`, then POST `/api/groups/{groupId}/mint`. Expect an ACTIVE group and a `NUMM-40-14-07-<DB serial>-<check>` code. GET `/api/codes/{actual returned code}/validate` should return `valid:true`; replace its final check character with a different base-36 character and expect `valid:false`. Export `/api/export/erp-template` as operator to show the original MATNR alongside that code. Use returned IDs/code, never hard-code a serial. If an existing signature group is already ACTIVE, choose a clean demo DB; do not promise a new mint from that state. [v2] reviewer seeds; [wizard]; [ingest]; [job]; [gov]; [codes]; [export].

**Five strongest points**

1. An executed inch/DN-style alias example becomes a high-confidence near duplicate with field-level agreement. [probes]
2. Pairwise ML rejects the executed 50mm/200mm near miss despite high text similarity. [probes]
3. Plant codes remain available in CPSE-scoped mapping exports for ERP reconciliation. [ingest] [export]
4. Confirmed multi-CPSE groups drive rate-contract candidate reports using explicit eligibility rules. [mappings] [analytics]
5. Review decisions and code minting are separated by role and actor checks. [gov] [security]

**Gaps and risks**

- No measured procurement reduction, GeM contract, real CPSE deployment or realized financial benefit is established by this checkout. Analytics are implemented; operational outcomes are unverified. [analytics] [metrics]
- CSV/API price ingestion, quantity-weighted demand, currency/UOM/date-adjusted price benchmarking and GeM integration are **NOT IMPLEMENTED** in the inspected paths. [ingest] [mappings] [export]
- The existing seed helper starts asynchronous ingestion then immediately triggers harmonize-all; it does not wait for ingestion jobs. Its GASKET row is outside the default seeded taxonomy. Do not use its “ready” message as completion proof. [seed] [v2]
- No dedicated existing tests were found for same-user publication, CPSE-scoped data visibility, or PostgreSQL append-only enforcement. [govtest] [securitytest] [audittest]
- UI preflight says which rows are valid but submits all transformed rows; the server performs the actual skipping. [wizard] `generateStandardCsv`, `handleIngest`; [ingest] `processCsv`.

## 3. Innovation and uniqueness of the solution

| Point | What the code does | Evidence (file:function) | Numbers |
|---|---|---|---|
| C1: hybrid vs text | Explicit size/material/grade conflicts provide information that cosine similarity alone cannot enforce. Saved synthetic evaluation reports better duplicate classification. | [features] `attribute_comparison_features`; [train] `baseline_predict`, `main`; [metrics] | Exact metrics below |
| C2: schema roles | Generator labels identity conflicts NOT_A_MATCH, variant conflicts VARIANT; runtime uses them as learned features and explanation severity, not a mandatory label override. | [schema] `CATEGORIES`; [generate] `build_pairs`; [features]; [infer] | Per-category table in A2 |
| C3: explainability | Returns `checks:string[]`, `warnings:string[]`, `conflicts:string[]`, constructed from extraction comparisons. | [inference.py][infer] `_explain`; [probes] | Two exact examples below |
| C4: check character | Local MOD 37,36 implementation and format guard; existing tests plus execution of the actual Java utility. | [iso] `computeCheckChar`, `validate`; [isotest]; [checksumresults] | Sample: 595 substitutions detected; 12 unequal adjacent swaps detected; 0 misses |
| C5: audit chain | SHA-256-linked rows; serialized appends with locked head row; update/delete trigger; paginated recomputation. | [audit] `logEvent`, `computeRowHash`, `verifyChainIntegrity`; [v6]; [tamper] | 64-character genesis/hash; 1000 rows/page |
| C6: self-contained inference | Joblib models, scikit-learn, TF-IDF and RapidFuzz run locally; backend calls configurable Python service. No external AI-provider call found in inspected application source/manifests. | [infer] `load_artifacts`; [client] constructor/REST methods; [requirements], [pom], [package], [api] | 4 loaded joblib artifacts |
| C7: accessibility/language | Skip link, focus styling, table semantics and ARIA regions exist. English resources only; Hindi **NOT IMPLEMENTED**, not merely incomplete placeholders. | [header] `Header`; [basecss]; [wizard]; [i18n] `resources`/init; [parity] | English key check: 42 used / 55 available |
| C8: engineering details | Canonical-ID train/test isolation; train-only TF-IDF; deterministic bounded retrieval; after-commit async processing; schema caching and error reporting. | [train] `canonical_group_split`; [materials]; [ingest] `dispatchAfterCommit`; [job]; [client] | 70/15/remainder ID split; 10-row batches; 10-minute schema cache |
| C9: safe claims | Claim implemented mechanisms and measured synthetic results with scope; avoid national accuracy, guaranteed engineering equivalence, full bilingual compliance, or zero false merges. | [metrics], [schema], [probes], [i18n], [harm] | Recorded hard-negative false-merge rate remains 0.05565529622980251 |

### C1. Exact ablation numbers and observed examples

From [models/training_results.json][metrics], produced by [train.py][train] `main`:

| Metric | Text-only baseline | Hybrid |
|---|---:|---:|
| Binary precision | 0.7993957703927492 | 0.928922908693275 |
| Binary recall | 0.7449324324324325 | 0.9566441441441441 |
| Binary F1 | 0.7712037306907608 | 0.9425797503467407 |
| False-merge rate on hard negatives | 0.22262118491921004 | 0.05565529622980251 |

Baseline word-cosine threshold is 0.3, selected on validation by scanning `np.arange(0.30,0.96,0.01)` and maximizing F1. Hard negatives are ground-truth VARIANT/NOT_A_MATCH, **557** test pairs. Hybrid test macro-F1 is **0.8375555305976915**. Functional equivalence test support is only **6**; VARIANT support **50**; NEEDS_REVIEW support **296**. [metrics] [train]

**Scope caveat:** evaluation binary positives are only EXACT_DUPLICATE/NEAR_DUPLICATE (`is_same_material`), whereas runtime sums in FUNCTIONALLY_EQUIVALENT. Metrics evaluate pair classification, not retrieval, signatures, human decisions or end-to-end merge outcomes. No retraining/full evaluation was run here. [train] [infer] [harm]

These are executed examples from the committed synthetic `pairs.csv`, not claimed held-out results. Record indexes are zero-based, excluding header; specifications are supplied to both comparisons. Baseline says duplicate because cosine≥0.3; current hybrid says VARIANT with match probability 0.0 in all three. [probes] `baseline_failure_examples`; [verify] probe code; [pairs].

| Pair index | Real descriptions/specifications from dataset | Baseline cosine | Hybrid label probability |
|---|---|---:|---:|
| 11784 | `Carbon Steel Pipe, 0.5" Nominal Diameter, SCH40, Seamless` vs `Pipe - Carbon Steel, Size: 15MM, Schedule: SCH80`; both spec `ASTM A312 GrB` | 0.3661187783790124 | VARIANT 0.8432 |
| 11785 | `CS PIPE 15.0MM SCH40`, spec `ASTM A312 GrB` vs `CS  PIPE  15MM  SCH40`, spec `ASTM A312 GrA` | 0.4221867361460424 | VARIANT 0.9763 |
| 11787 | `Cs  Pipe  0.5"  Sch80` vs `Pipe - Carbon Steel,Size: 65MM,Schedule: SCH80`; both spec `ASTM A312 GrB` | 0.5081572843274382 | VARIANT 0.9755 |

The examples also expose synthetic-data limitations: standards are randomly assigned in `build_canonical_materials`; these pairs must not be advertised as authoritative engineering specifications. [generate] [schema]

### C3. Exact explanation examples

For the executed 50mm vs 200mm pair (`_explain`, [infer]; [probes]):

```json
{"checks":["Same material: CS","Same grade: B","Same standard: ASTMA106","Same schedule: SCH40"],"warnings":[],"conflicts":["Nominal Size Mm conflict (variant_critical): 50.0 vs 200.0"]}
```

For the executed missing-material pair, full CS pipe vs `PIPE 50MM SCH40 ASTM A106 GRB` (same sources):

```json
{"checks":["Same nominal size mm: 50.0","Same grade: B","Same standard: ASTMA106","Same schedule: SCH40"],"warnings":["Material not stated on one side -- cannot verify"],"conflicts":[]}
```

Explanations are rule-generated attribute summaries, not SHAP/feature-attribution explanations of why the random forest chose its class. `_explain` receives `predicted_label` but does not use it. Missing critical fields on both sides can yield warnings even though the feature missingness counts only one-sided absence. [infer] [features]

### C4. Check-character evidence

Existing [Iso7064Mod3736Test.java][isotest] methods `singleSubstitution_alwaysDetected` and `adjacentTransposition_alwaysDetected` both passed; the test class has **14 executed invocations, 0 failures/errors/skips**, including parameterized cases. Both mutation tests use just `SAMPLE_BASE=NUMM-40-14-07-000042`. [surefireiso]

The added read-only [ChecksumPresentationProbe.java][checksumprobe] calls the actual utility and records `NUMM-40-14-07-000042-E`, valid=true, **595 tested substitutions/0 undetected**, **12 unequal adjacent swaps/0 undetected**. This is exact sample coverage, not proof that every adjacent swap of every possible code is detected. The test's lowercase “malformed” comment is inaccurate: `validate` uppercases before checking, so a lowercase valid code is accepted. [iso] [checksumresults]

### C5. Audit chain and tamper demonstration

`computeRowHash` computes uppercase hex SHA-256 over UTF-8 of:

```text
prev_hash|user_id|action|entity_type|entity_id|timestamp|old_value|new_value
```

Null predecessor defaults to 64 zeroes; null IDs to 0; null text/time to empty string. `logEvent` truncates timestamps to microseconds, locks the singleton head row through `findByIdForUpdate(1)`, writes the event and updates its head in the transaction. `verifyChainIntegrity` scans audit IDs ascending, 1000/page, checks both previous link and recomputed row hash, returning first broken audit ID/reason or valid/length/head hash. [audit]

Migration V6's `audit_trail_is_append_only` raises an exception before each UPDATE/DELETE. Under `demo` profile only, ADMIN POST `/api/demo/tamper-audit/{auditId}` disables the trigger transactionally, changes old_value (default `TAMPERED_PAYLOAD_UNAUTHORIZED_MUTATION`), and reenables it. A subsequent `/api/mappings/audit/verify` should return `valid:false` for an existing modified row. This destructive demo was **not run** against a user's DB. [v6] [tamper] [security]

**Tamper-evident, not tamper-proof:** privileged DB users can disable triggers/rewrite hashes. Verification does not compare its final hash with the persisted head table or an external trusted anchor, so deleting a tail after disabling protection may escape its scan. The pipe-delimited payload does not escape separators in values. No independent anchoring, signatures or blockchain consensus is implemented. [audit] `computeRowHash`, `verifyChainIntegrity`; [tamper].

### C6–C8. Local inference, accessibility and other concrete details

**External calls audit:** searched application Python, Java and frontend source for provider names (`openai`, `anthropic`, `bedrock`, `vertex`, `huggingface`, `gemini`, `azure`, `boto3`, `google.generative`) and HTTP/network APIs (`https?://`, `fetch`, `axios`, `RestTemplate`, `WebClient`, `requests`, `httpx`); inspected Python requirements, frontend package/lock manifests, Maven POM, Docker/build/hosting configs and backend properties. Application network paths found are frontend→backend and `PythonMatchingClient`→configurable matching-service URL, plus PostgreSQL. `load_artifacts` reads local joblib files. No external AI-provider integration was found. [requirements] [package] [pom] [api] [client] [infer]

Safe wording: **“Inference runs on bundled local models without a required external AI-provider API.”** Do not say “no cloud/network calls anywhere”: configurable hosted service/DB URLs and package/container downloads exist; this was not a runtime packet capture or a full audit of every third-party dependency's internals. [client] [requirements] [pom]

**Accessibility:** `Header` has a skip-to-main link; CSS includes visible focus box-shadow; tables have captions/scopes in the wizard; status/error components have ARIA live regions; common badges pair icons with text. Full WCAG/GIGW conformance is **UNVERIFIED**, not certified. The wizard upload drop-zone is a clickable `div` without keyboard handler/tabIndex and its file input is hidden; export dialog ARIA alone does not prove focus trapping. [header] [basecss] [wizard] [catalog]

**Language:** `i18n.js` exports only `resources.en`, initializes `lng:'en'`/fallback English; HTML has `lang='en'`. Hindi dictionary, language switch and persisted Hindi preference are **NOT IMPLEMENTED**. `check-i18n-parity.mjs` now checks English used keys only: executed result **42 used keys / 55 available keys**. Hard-coded English remains in the wizard. `docs/ACCESSIBILITY.md` claiming English/Hindi dictionaries and persistence is contradicted by source. [i18n] [parity] [wizard] [accessibility]

Other useful implementation choices, each with its practical reason:

- Canonical-ID isolation plus train-only TF-IDF reduces direct train/test identity leakage. [train] `canonical_group_split`, `fit_tfidf`.
- Category-first, material-ID-ordered candidates make retrieval repeatable and bound scoring, although the cap reduces recall. [materials] `findCandidatesByCategory`; [harm] `findCandidateEntities`.
- Async work is dispatched after commit, preventing the worker from processing uncommitted upload rows. [ingest] `dispatchAfterCommit`.
- Ten-item batches and recorded FAILED status bound requests and expose partial failures instead of claiming full success. [job] `processAsync`.
- Partial unique indexes allow incomplete signatures while preventing multiple PENDING/CONFIRMED mappings for one material. [v4].
- Audit head row-locking serializes appends, addressing concurrent writers' predecessor selection. [audit] `logEvent`.

**Five strongest points**

1. Saved synthetic binary F1 improves from 0.7712037306907608 to 0.9425797503467407 with hybrid features. [metrics]
2. Executed dataset pairs show schedule, grade and size conflicts that text-only cosine accepts but hybrid rejects. [probes]
3. Canonical-ID split isolation and train-only vectorizer fitting are explicit in training code. [train]
4. Local model inference returns both class probabilities and concrete attribute checks/warnings/conflicts. [infer]
5. Sequence-backed codes have a tested check character; decisions have a hash-chained, append-only-protected audit record. [code] [iso] [audit] [v6]

**Gaps and risks**

- Avoid “zero false merges,” “production accuracy,” “guaranteed equivalent,” or “all size mismatches prevented”: saved errors and the signature shortcut contradict these. [metrics] [harm]
- Avoid “national deployment,” “automatic GeM procurement,” “direct SAP integration,” “realized savings,” “Hindi support,” “WCAG/GIGW certified,” and “tamper-proof blockchain”: these are not supported by implemented paths or verification. [export] [analytics] [i18n] [audit]
- Treat schema/equivalence rules as curated synthetic assumptions; the source explicitly says they are not authoritative engineering standards. [schema]
- Global novelty relative to competing systems was not researched; the claims here establish implementation specifics, not patentability or market uniqueness.

### Corrections to my analysis file

The named original was unavailable, so these are required corrections for any existing summary making the corresponding claims:

1. Replace “10 categories supported end to end” with **10 extractor categories; 8 seeded/UI categories**. [extract] [wizard] [v2]
2. Replace “complete-spec SHA-256 identity” with **category + identity-critical fields only**, and flag cross-size/grade grouping risk. [code] [schema] [harm]
3. Replace “merge threshold 0.85” with **proposal threshold 0.60; HIGH tier 0.85**. [harm] [infer]
4. Do not promise the seed ONGC/IOCL pipe pair is an ML match: executed label is NOT_A_MATCH, probability 0.34. [probes]
5. Do not claim every missing critical attribute forces NEEDS_REVIEW; executed missing-material example is NEAR_DUPLICATE/0.7806. [probes]
6. Separate pairwise evaluation's two positive labels from runtime's three-label match-probability sum. [train] [infer]
7. Describe admin supersede as an in-place decision override; actual replacement rows occur on re-harmonizing confirmed mappings. [gov] [harm]
8. State HTTP minting is senior-only, despite ADMIN being accepted inside the service. [security] [gov]
9. Separate checksum validity from registry existence, and use the executed check character **E** for sample serial 000042. [iso] [codes] [checksumresults]
10. Describe GeM output as candidate analytics and SAP output as CSV templates; savings are assumptions, not measured procurement outcomes. [analytics] [export]
11. Remove Hindi/persisted-language claims and narrow accessibility/audit claims to implemented controls. [i18n] [accessibility] [audit]
12. Add test-scope limits, first-50 retrieval cap, incomplete schedule/unit parsing, and signature-before-ML risk to the presentation notes. [isotest] [securitytest] [harm] [extract]

<!-- Evidence links generated from this checkout. -->
[probes]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/docs/presentation_verification_results.json:1>
[checksumresults]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/docs/presentation_checksum_results.json:1>
[wizard]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/frontend/src/components/IngestionWizard.jsx:13>
[ingest]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/material-master-backend/src/main/java/com/sih/materialmaster/controller/MaterialController.java:238>
[job]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/material-master-backend/src/main/java/com/sih/materialmaster/service/HarmonizationJobService.java:44>
[extract]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/matching-service/src/preprocessing/attribute_extraction.py:153>
[flask]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/matching-service/app.py:114>
[features]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/matching-service/src/features/feature_engineering.py:26>
[code]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/material-master-backend/src/main/java/com/sih/materialmaster/service/NationalCodeGenerator.java:81>
[harm]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/material-master-backend/src/main/java/com/sih/materialmaster/service/HarmonizationService.java:109>
[materials]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/material-master-backend/src/main/java/com/sih/materialmaster/repository/MaterialRepository.java:33>
[infer]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/matching-service/src/matching/inference.py:103>
[train]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/matching-service/src/matching/train.py:27>
[gov]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/material-master-backend/src/main/java/com/sih/materialmaster/service/GovernanceService.java:47>
[security]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/material-master-backend/src/main/java/com/sih/materialmaster/config/SecurityConfig.java:56>
[iso]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/material-master-backend/src/main/java/com/sih/materialmaster/util/Iso7064Mod3736.java:70>
[export]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/material-master-backend/src/main/java/com/sih/materialmaster/service/ExportService.java:40>
[codes]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/material-master-backend/src/main/java/com/sih/materialmaster/controller/CodeController.java:60>
[analytics]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/material-master-backend/src/main/java/com/sih/materialmaster/service/AnalyticsService.java:42>
[client]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/material-master-backend/src/main/java/com/sih/materialmaster/service/PythonMatchingClient.java:34>
[schema]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/matching-service/src/data_generation/schemas.py:17>
[seed]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/scripts/seed_demo.ps1:32>
[v4]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/material-master-backend/src/main/resources/db/migration/V4__attribute_identity.sql:30>
[generate]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/matching-service/src/data_generation/generate_dataset.py:186>
[metrics]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/matching-service/models/training_results.json:1>
[mappings]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/material-master-backend/src/main/java/com/sih/materialmaster/repository/MaterialMappingRepository.java:14>
[errors]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/material-master-backend/src/main/java/com/sih/materialmaster/controller/ApiExceptionHandler.java:21>
[v1]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/material-master-backend/src/main/resources/db/migration/V1__initial_schema.sql:1>
[v2]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/material-master-backend/src/main/resources/db/migration/V2__seed_data.sql:38>
[exportcontroller]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/material-master-backend/src/main/java/com/sih/materialmaster/controller/ExportController.java:20>
[analyticscontroller]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/material-master-backend/src/main/java/com/sih/materialmaster/controller/AnalyticsController.java:21>
[analyticstest]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/material-master-backend/src/test/java/com/sih/materialmaster/service/AnalyticsServiceTest.java:35>
[securitytest]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/material-master-backend/src/test/java/com/sih/materialmaster/SecurityMatrixTest.java:20>
[govtest]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/material-master-backend/src/test/java/com/sih/materialmaster/service/GovernanceServiceTest.java:134>
[audittest]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/material-master-backend/src/test/java/com/sih/materialmaster/service/AuditServiceTest.java:79>
[audit]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/material-master-backend/src/main/java/com/sih/materialmaster/service/AuditService.java:48>
[v6]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/material-master-backend/src/main/resources/db/migration/V6__audit_chain_head.sql:20>
[tamper]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/material-master-backend/src/main/java/com/sih/materialmaster/controller/DemoTamperController.java:20>
[isotest]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/material-master-backend/src/test/java/com/sih/materialmaster/util/Iso7064Mod3736Test.java:59>
[requirements]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/matching-service/requirements.txt:1>
[pom]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/material-master-backend/pom.xml:1>
[package]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/frontend/package.json:1>
[api]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/frontend/src/services/api.js:1>
[header]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/frontend/src/components/common/Header.jsx:1>
[basecss]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/frontend/src/styles/base.css:21>
[i18n]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/frontend/src/i18n.js:4>
[parity]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/frontend/scripts/check-i18n-parity.mjs:33>
[verify]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/scripts/verify_presentation.py:1>
[pairs]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/matching-service/data/generated/pairs.csv:1>
[surefireiso]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/material-master-backend/target/surefire-reports/com.sih.materialmaster.util.Iso7064Mod3736Test.txt:1>
[checksumprobe]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/scripts/ChecksumPresentationProbe.java:4>
[catalog]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/frontend/src/components/CatalogView.jsx:1>
[accessibility]: <C:/Users/pavan/Documents/SIH26/Material Code Harmonization/docs/ACCESSIBILITY.md:1>
