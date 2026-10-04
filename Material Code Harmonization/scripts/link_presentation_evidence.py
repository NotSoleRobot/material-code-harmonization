"""Resolve presentation references and snapshot the already-run test reports."""
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path

root = Path(__file__).resolve().parents[1]
report = root/'NUMM_SIH_Presentation_Analysis_Verified.md'
java = 'material-master-backend/src/main/java/com/sih/materialmaster/'
test = 'material-master-backend/src/test/java/com/sih/materialmaster/'
migration = 'material-master-backend/src/main/resources/db/migration/'
refs = {
 'probes':('docs/presentation_verification_results.json',''),
 'checksumresults':('docs/presentation_checksum_results.json',''),
 'wizard':('frontend/src/components/IngestionWizard.jsx','const CANONICAL_FIELDS'),
 'ingest':(java+'controller/MaterialController.java','private BulkUploadResponseDto processCsv'),
 'job':(java+'service/HarmonizationJobService.java','public void processAsync'),
 'extract':('matching-service/src/preprocessing/attribute_extraction.py','CATEGORY_FIELDS ='),
 'flask':('matching-service/app.py','def extract_attributes_endpoint'),
 'features':('matching-service/src/features/feature_engineering.py','FIELD_TO_SCHEMA_KEY ='),
 'code':(java+'service/NationalCodeGenerator.java','public SignatureResult computeAttributeSignature'),
 'harm':(java+'service/HarmonizationService.java','public HarmonizationResultDto harmonizeMaterial(Long materialId, FindMatchesResponse'),
 'materials':(java+'repository/MaterialRepository.java','List<Material> findCandidatesByCategory'),
 'infer':('matching-service/src/matching/inference.py','def compare_materials'),
 'train':('matching-service/src/matching/train.py','def canonical_group_split'),
 'gov':(java+'service/GovernanceService.java','public MaterialMapping decideMapping'),
 'security':(java+'config/SecurityConfig.java','public SecurityFilterChain securityFilterChain'),
 'iso':(java+'util/Iso7064Mod3736.java','public static char computeCheckChar'),
 'export':(java+'service/ExportService.java','public int streamCrossReference'),
 'codes':(java+'controller/CodeController.java','public ResponseEntity<CodeValidationDto> validateCode'),
 'analytics':(java+'service/AnalyticsService.java','public DashboardStatsDto getDashboardStats'),
 'client':(java+'service/PythonMatchingClient.java','public PythonMatchingClient('),
 'schema':('matching-service/src/data_generation/schemas.py','CATEGORIES ='),
 'seed':('scripts/seed_demo.ps1','$OngcCsv ='),
 'v4':(migration+'V4__attribute_identity.sql','CREATE UNIQUE INDEX'),
 'generate':('matching-service/src/data_generation/generate_dataset.py','def build_pairs'),
 'metrics':('matching-service/models/training_results.json',''),
 'mappings':(java+'repository/MaterialMappingRepository.java','public interface MaterialMappingRepository'),
 'errors':(java+'controller/ApiExceptionHandler.java','public class ApiExceptionHandler'),
 'v1':(migration+'V1__initial_schema.sql',''),
 'v2':(migration+'V2__seed_data.sql','-- Reuse legacy'),
 'exportcontroller':(java+'controller/ExportController.java','public class ExportController'),
 'analyticscontroller':(java+'controller/AnalyticsController.java','public class AnalyticsController'),
 'analyticstest':(test+'service/AnalyticsServiceTest.java','void dashboardUsesAggregateCountsAndReviewedVolumeForDeduplicationRate'),
 'securitytest':(test+'SecurityMatrixTest.java','@WebMvcTest'),
 'govtest':(test+'service/GovernanceServiceTest.java','void testDecideMapping_ConflictOfInterestBlocked'),
 'audittest':(test+'service/AuditServiceTest.java','void testVerifyChainIntegrity_TamperDetection'),
 'audit':(java+'service/AuditService.java','public AuditTrail logEvent'),
 'v6':(migration+'V6__audit_chain_head.sql','CREATE OR REPLACE FUNCTION'),
 'tamper':(java+'controller/DemoTamperController.java','public class DemoTamperController'),
 'isotest':(test+'util/Iso7064Mod3736Test.java','void singleSubstitution_alwaysDetected'),
 'requirements':('matching-service/requirements.txt',''),
 'pom':('material-master-backend/pom.xml',''),
 'package':('frontend/package.json',''),
 'api':('frontend/src/services/api.js',''),
 'header':('frontend/src/components/common/Header.jsx',''),
 'basecss':('frontend/src/styles/base.css',':focus-visible'),
 'i18n':('frontend/src/i18n.js','export const resources'),
 'parity':('frontend/scripts/check-i18n-parity.mjs','const english ='),
 'verify':('scripts/verify_presentation.py',''),
 'pairs':('matching-service/data/generated/pairs.csv',''),
 'surefireiso':('material-master-backend/target/surefire-reports/com.sih.materialmaster.util.Iso7064Mod3736Test.txt',''),
 'checksumprobe':('scripts/ChecksumPresentationProbe.java','public static void main'),
 'catalog':('frontend/src/components/CatalogView.jsx',''),
 'accessibility':('docs/ACCESSIBILITY.md',''),
}
content = report.read_text(encoding='utf-8').split('<!-- Evidence links')[0]
links = []
for key, (relative, needle) in refs.items():
 path = root/relative
 assert path.is_file(), path
 lines = path.read_text(encoding='utf-8-sig').splitlines()
 number = next((i for i,line in enumerate(lines,1) if needle in line),None) if needle else 1
 assert number is not None, (path,needle)
 links.append(f'[{key}]: <{path.as_posix()}:{number}>')
used = set(re.findall(r'\]\[([\w]+)\]',content))
assert used <= refs.keys(), used-refs.keys()
report.write_text(content+'<!-- Evidence links generated from this checkout. -->\n'+'\n'.join(links)+'\n',encoding='utf-8')
tests = []
for path in sorted((root/'material-master-backend/target/surefire-reports').glob('TEST-*.xml')):
 element = ET.parse(path).getroot()
 tests.append({k:element.attrib[k] for k in ('name','tests','failures','errors','skipped')})
snapshot = {'date':'2026-09-28','backend_suites':tests,
 'backend_totals':{k:sum(int(t[k]) for t in tests) for k in ('tests','failures','errors','skipped')},
 'matching_pytest_observed':'11 passed in 26.91s',
 'english_dictionary_check_observed':'English UI dictionary OK: 42 used keys, 55 available keys',
 'scope':'Backend services use mocked repositories; SecurityMatrixTest uses MatrixProbeController. No live database workflow was executed.'}
(root/'docs/presentation_test_results.json').write_text(json.dumps(snapshot,indent=2),encoding='utf-8')
print(json.dumps({'links':len(links),'words':len(content.split()),'tests':snapshot['backend_totals']}))
