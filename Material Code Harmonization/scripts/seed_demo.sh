#!/usr/bin/env bash
# ==============================================================================
# NUMM (National Unified Material Master) — SIH 2026 Demo Data Seeding Script
# ==============================================================================
set -e

BACKEND_URL="${BACKEND_URL:-http://localhost:8080}"
echo "--------------------------------------------------"
echo "Seeding NUMM Demonstration Data at $BACKEND_URL"
echo "--------------------------------------------------"

# 1. Login as Admin to get JWT token
echo "1. Authenticating as National Governance Administrator..."
LOGIN_RESP=$(curl -s -X POST "$BACKEND_URL/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@numm.gov.in","password":"admin123"}')

TOKEN=$(echo "$LOGIN_RESP" | grep -o '"accessToken":"[^"]*' | cut -d'"' -f4)

if [ -z "$TOKEN" ]; then
  echo "❌ Login failed. Response: $LOGIN_RESP"
  exit 1
fi

echo "✅ Authenticated. Token acquired."

# 2. Upload ONGC Sample CSV Batch
echo "2. Ingesting ONGC Plant Materials Batch..."
ONGC_CSV="cpse_material_code,description,specification,unit_of_measure,category,cpse_name
ONGC-PP-2001,CS SEAMLESS PIPE 50MM SCH40 ASTM A106 GRB,ASTM A106,MTR,PIPE,ONGC
ONGC-VL-2002,GLOBE VLV 3INCH 300LB WCB RTJ,API 600,NOS,VALVE,ONGC
ONGC-GK-2003,SPIRAL WOUND GASKET 50MM 300#,ASME B16.20,NOS,GASKET,ONGC
ONGC-PP-2004,SS 316L PIPE 1IN SCH10S ASTM A312,ASTM A312,MTR,PIPE,ONGC
ONGC-BR-2005,BALL BEARING 6308 2RS C3,ISO 15,NOS,BEARING,ONGC"

curl -s -X POST "$BACKEND_URL/api/materials/bulk-csv?autoHarmonize=true" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: text/plain" \
  --data-raw "$ONGC_CSV" > /dev/null

echo "✅ ONGC batch ingested."

# 3. Upload IOCL Sample CSV Batch
echo "3. Ingesting IOCL Plant Materials Batch (Matching Candidates)..."
IOCL_CSV="cpse_material_code,description,specification,unit_of_measure,category,cpse_name
IOCL-PP-101,CARBON STEEL PIPE DN50 SCHEDULE 40 GR.B IS1239,IS 1239,MTR,PIPE,IOCL
IOCL-VL-102,GATE VALVE CS CLASS150 2INCH RF API600,API 600,NOS,VALVE,IOCL
IOCL-BR-103,DEEP GROOVE BALL BEARING 6205-2RS1,SKF 6205,NOS,BEARING,IOCL"

curl -s -X POST "$BACKEND_URL/api/materials/bulk-csv?autoHarmonize=true" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: text/plain" \
  --data-raw "$IOCL_CSV" > /dev/null

echo "✅ IOCL batch ingested."

# 4. Trigger Batch Harmonization
echo "4. Running National Batch Harmonization Pipeline..."
curl -s -X POST "$BACKEND_URL/api/harmonization/harmonize-all" \
  -H "Authorization: Bearer $TOKEN" > /dev/null

echo "✅ Harmonization complete."

# 5. Verify Cryptographic Audit Trail
echo "5. Verifying Cryptographic Audit Chain Integrity..."
VERIFY_RESP=$(curl -s -X GET "$BACKEND_URL/api/mappings/audit/verify" \
  -H "Authorization: Bearer $TOKEN")

echo "Audit Verification Result: $VERIFY_RESP"
echo "--------------------------------------------------"
echo "🎉 Demonstration Environment Ready!"
echo "--------------------------------------------------"
