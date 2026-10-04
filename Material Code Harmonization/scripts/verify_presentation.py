"""Read-only model/extractor probes for the presentation audit; writes evidence JSON."""
import csv
import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT / 'matching-service/src'), str(ROOT / 'matching-service')]
from app import app
from matching.inference import compare_materials, load_artifacts
from features.feature_engineering import normalize_text

samples = [
 ('PIPE', 'CS SEAMLESS PIPE 50MM SCH40 ASTM A106 GRB', 'ASTM A106'),
 ('PIPE', 'CARBON STEEL PIPE DN50 SCHEDULE 40 GR.B IS1239', 'IS 1239'),
 ('PIPE', 'SS 316L PIPE 1IN SCH10S ASTM A312', 'ASTM A312'),
 ('VALVE', 'GATE VALVE CS CLASS150 2INCH RF API600', 'API 600'),
 ('FLANGE', 'WELD-NECK FLANGE SS304 DN50 150# ASME B16.5', ''),
 ('PIPE', 'SS PIPE 2 inch SCH40 TP304 ASTM A312', ''),
 ('PIPE', 'STAINLESS STEEL PIPE 50mm SCH40 TYPE 304 ASTM A312', ''),
 ('BEARING', 'BALL BEARING 6308 2RS C3', 'ISO 15'),
]
client = app.test_client()
extracts = []
for category, description, specification in samples:
 record = dict(category=category, description=description, specification=specification)
 response = client.post('/extract-attributes', json={'materials':[record]}).get_json()['results'][0]
 extracts.append({'input':record, 'output':response})

def compare(a, b):
 result = compare_materials(a, b)
 return {'a':a, 'b':b, 'output':result}

base = dict(category='PIPE', description=samples[0][1], specification='')
pairs = [compare(base, dict(base, description=base['description'].replace('50MM','200MM'))),
         compare(base, dict(base, description=base['description'].replace('CS ', 'SS '))),
         compare(extracts[0]['input'], extracts[1]['input']),
         compare(extracts[5]['input'], extracts[6]['input']),
         compare(base, dict(category='PIPE', description='PIPE 50MM SCH40 ASTM A106 GRB'))]
artifacts = load_artifacts()
examples = []
with (ROOT/'matching-service/data/generated/pairs.csv').open(encoding='utf-8',newline='') as f:
 for index, row in enumerate(csv.DictReader(f)):
  if row['label'] not in ('VARIANT','NOT_A_MATCH'): continue
  a = {k:row[k+'_a'] for k in ('category','description','specification')}
  b = {k:row[k+'_b'] for k in ('category','description','specification')}
  vectors = artifacts['word_vec'].transform([normalize_text(x['description']+' '+x['specification']) for x in (a,b)])
  sim = float((vectors[0] @ vectors[1].T).toarray()[0,0])
  if sim < .3: continue
  result = compare(a,b)
  if result['output']['predicted_relationship'] == row['label'] and result['output']['match_probability'] < .3:
   examples.append({'csv_record_index':index,'truth':row['label'],'baseline_cosine':sim,**result})
  if len(examples)==3: break
payload = {'extraction_examples':extracts,'comparison_examples':pairs,'baseline_failure_examples':examples,
 'signature_pipe_cs_payload':'{"__category":"PIPE","material":"CS"}',
 'signature_pipe_cs':hashlib.sha256(b'{"__category":"PIPE","material":"CS"}').hexdigest().upper(),
 'artifact_classifier_params':artifacts['clf'].get_params(),
 'artifact_sha256':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in (ROOT/'matching-service/models').glob('*.joblib')}}
out = ROOT/'docs/presentation_verification_results.json'
out.write_text(json.dumps(payload,indent=2),encoding='utf-8')
print(out)
print(json.dumps(payload,indent=2))
