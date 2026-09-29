"""
Category-aware structured attribute extraction from free-text material descriptions.

Builds directly on the original attribute_extraction.py (dimension/standard/grade/material
regex functions) but organizes extraction per material category, and adds unit
normalization so "2 inch" and "50.8mm" resolve to the same normalized size.
"""
import re

MM_PER_INCH = 25.4

# Must match the same trade-nominal table used to *generate* sizes (text_variation.py) --
# otherwise "2 inch" and "50mm" would not round-trip to the same normalized value even
# though they refer to the same nominal pipe/valve/flange size in real industry usage.
NOMINAL_INCH_TO_MM = {0.5: 15, 1: 25, 1.5: 40, 2: 50, 2.5: 65, 3: 80, 4: 100, 6: 150, 8: 200}

STANDARD_PATTERNS = [
    r'\b(?:ASTM|ASME|IS|DIN|ISO|BS|SAE|AISI|IEC|API)\s*[\w\d.-]+',
    r'\bIS\s?\d+\b', r'\bASTM\s?[A-Z]?\d+\b', r'\bDIN\s?\d+\b',
    r'\bAPI\s?\d+\b', r'\bASME\s?B?\d+(?:\.\d+)?\b', r'\bISO\s?\d+\b', r'\bIEC\s?\d+\b',
]

GRADE_PATTERNS = [
    # (pattern, prefix_to_prepend_to_captured_value)
    # NOTE on the negative lookahead: "\bGr\.?\s?[A-Z0-9.]+\b" alone, under re.IGNORECASE,
    # would greedily match the letters of the full word "Grade" itself (since a-z matches
    # the [A-Z0-9.] class case-insensitively) and never reach the actual value -- so
    # "Grade 4.6" needs its own pattern, and the abbreviation pattern must explicitly NOT
    # match when "Gr" is actually the start of the word "Grade".
    (r'\bGrade\s+([A-Z0-9.]+)\b', ''),
    (r'\bGr(?!ade)\.?\s?([A-Z0-9.]+)\b', ''),
    # "Type 304" and "TP304" mean the same grade -- normalize both to "TP304" so they
    # compare equal downstream instead of looking like two different values.
    (r'\bType\s?(\d{3})\b', 'TP'),
    (r'\bTP\.?\s?(\d+)\b', 'TP'),
    (r'\b(Class\s?\d+)\b', ''),
]


def extract_grade(text: str) -> str | None:
    for pattern, prefix in GRADE_PATTERNS:
        m = re.search(pattern, text, re.IGNORECASE)
        if m:
            val = m.group(1) if m.groups() else m.group(0)
            return (prefix + val).upper().replace(' ', '')
    return None

MATERIAL_TYPE_PATTERNS = [
    # NOTE: "Type 304" / "Type 316" deliberately NOT matched here -- that phrasing states
    # the GRADE ("type 304 stainless"), not a distinct material family, and belongs in
    # extract_grade instead. Conflating the two caused a real false NOT_A_MATCH: "SS PIPE
    # ... TP304" vs "STAINLESS STEEL PIPE ... TYPE 304" (the same material) was flagged as
    # a material conflict (SS vs SS304) because "TYPE 304" was wrongly read as the material.
    (r'\b(?:S\.?S\.?304|SS304|Stainless\s?Steel\s?304)\b', 'SS304'),
    (r'\b(?:S\.?S\.?316|SS316|Stainless\s?Steel\s?316)\b', 'SS316'),
    (r'\b(?:S\.?S\.?|Stainless\s?Steel)\b', 'SS'),
    (r'\b(?:C\.?S\.?|Carbon\s?Steel)\b', 'CS'),
    (r'\b(?:M\.?S\.?|Mild\s?Steel)\b', 'MS'),
    (r'\b(?:C\.?I\.?|Cast\s?Iron)\b', 'CI'),
    (r'\b(?:G\.?I\.?|Galvani[sz]ed\s?Iron)\b', 'GI'),
    (r'\bPVC\b', 'PVC'),
    (r'\bHDPE\b', 'HDPE'),
    (r'\bBrass\b', 'BRASS'),
    (r'\bBronze\b', 'BRONZE'),
    (r'\bCopper\b', 'COPPER'),
    (r'\bAluminium\b', 'ALUMINIUM'),
    (r'\bAluminum\b', 'ALUMINIUM'),
    (r'\b(?:CAF|Compressed\s?Asbestos\s?Fibre)\b', 'CAF'),
    (r'\bPTFE\b', 'PTFE'),
    (r'\bGraphite\b', 'GRAPHITE'),
]


def normalize_size_mm(text: str) -> float | None:
    """Extracts a nominal size and normalizes it to millimetres, wherever an inch/mm/DN
    token is found. Returns None if nothing matches -- treated as 'unknown', not zero."""
    m = re.search(r'(\d+(?:\.\d+)?)\s?(?:inch|")', text, re.IGNORECASE)
    if m:
        inch_val = float(m.group(1))
        if inch_val in NOMINAL_INCH_TO_MM:
            return float(NOMINAL_INCH_TO_MM[inch_val])
        return round(inch_val * MM_PER_INCH, 1)  # fallback for a non-nominal inch value
    m = re.search(r'\bDN\s?(\d+(?:\.\d+)?)\b', text, re.IGNORECASE)
    if m:
        return round(float(m.group(1)), 1)
    m = re.search(r'(\d+(?:\.\d+)?)\s?mm\b', text, re.IGNORECASE)
    if m:
        return round(float(m.group(1)), 1)
    m = re.search(r'\b(?:NB|OD|ID)\s*(\d+(?:\.\d+)?)\s*(MM|CM|M|INCH|IN)?\b', text, re.IGNORECASE)
    if m:
        value = float(m.group(1))
        unit = (m.group(2) or "MM").upper()
        return round(value * {"MM": 1, "CM": 10, "M": 1000, "IN": MM_PER_INCH, "INCH": MM_PER_INCH}[unit], 1)
    m = re.search(r'\b(\d+(?:\.\d+)?)\s*(CM|M|IN)\b', text, re.IGNORECASE)
    if m:
        value = float(m.group(1))
        unit = m.group(2).upper()
        return round(value * {"CM": 10, "M": 1000, "IN": MM_PER_INCH}[unit], 1)
    return None


def extract_standard_code(text: str) -> str | None:
    for pattern in STANDARD_PATTERNS:
        m = re.search(pattern, text, re.IGNORECASE)
        if m:
            return m.group(0).upper().replace(' ', '')
    return None


def extract_material_type(text: str) -> str | None:
    for pattern, label in MATERIAL_TYPE_PATTERNS:
        if re.search(pattern, text, re.IGNORECASE):
            return label
    return None


def extract_bolt_size(text: str) -> str | None:
    m = re.search(r'\bM\s?(\d{1,2})\b', text)
    return f"M{m.group(1)}" if m else None


def extract_number_near(text: str, keyword_pattern: str) -> float | None:
    """Finds a number immediately preceding/following a unit keyword, e.g. '15KW',
    '415V', '1440RPM', '5HP'. Used for MOTOR/PUMP/CABLE numeric attributes."""
    m = re.search(r'(\d+(?:\.\d+)?)\s?' + keyword_pattern, text, re.IGNORECASE)
    if m:
        return float(m.group(1))
    return None


def extract_phase(text: str) -> str | None:
    if re.search(r'\b3\s?[-]?\s?ph(?:ase)?\b', text, re.IGNORECASE):
        return "3PH"
    if re.search(r'\b1\s?[-]?\s?ph(?:ase)?\b', text, re.IGNORECASE):
        return "1PH"
    return None


def extract_bearing_number(text: str) -> str | None:
    m = re.search(r'\b(6\d{3})\b', text)
    return m.group(1) if m else None


def extract_seal_type(text: str) -> str | None:
    if re.search(r'\b2RS\b', text, re.IGNORECASE):
        return "2RS"
    if re.search(r'\bZZ\b', text):
        return "ZZ"
    if re.search(r'\bOPEN\b', text, re.IGNORECASE):
        return "OPEN"
    return None


def extract_pressure_class(text: str) -> str | None:
    m = re.search(r'\bCLASS\s?(\d+)\b', text, re.IGNORECASE)
    if m:
        return f"CLASS{m.group(1)}"
    m = re.search(r'\bPN\s?(\d+)\b', text, re.IGNORECASE)
    if m:
        return f"PN{m.group(1)}"
    m = re.search(r'\b(\d+)\s?#', text)  # US/API convention: "150#" == "Class 150"
    if m:
        return f"CLASS{m.group(1)}"
    return None


CATEGORY_FIELDS = {
    "PIPE": ["material_type", "size_mm", "grade", "standard", "schedule"],
    "VALVE": ["valve_type", "material_type", "size_mm", "pressure_class", "standard"],
    "FLANGE": ["flange_type", "material_type", "size_mm", "pressure_class", "standard"],
    "GASKET": ["gasket_type", "material_type", "size_mm", "pressure_class", "standard"],
    "BEARING": ["bearing_type", "bearing_number", "bore_mm", "seal_type"],
    "MOTOR": ["power_kw", "voltage_v", "phase", "rpm"],
    "CABLE": ["material_type", "cores", "csa_sqmm", "voltage_grade_kv", "standard"],
    "PUMP": ["pump_type", "power_hp", "phase"],
    "FASTENER": ["fastener_type", "bolt_size", "grade"],
    "FILTER": ["filter_type", "micron_rating", "size_mm"],
}

# "Type" vocabularies for the identity-critical categorical attribute of each category.
# These were the single biggest gap in the first extraction pass: a category's IDENTITY-
# CRITICAL attribute (the one whose mismatch must force NOT_A_MATCH -- Gate vs Globe valve,
# Centrifugal vs Submersible pump, Hex Bolt vs Washer) was only ever being compared via
# text similarity, not structurally -- exactly the failure mode the project exists to fix.
TYPE_VOCAB = {
    "valve_type": {"GATE": ["GATE"], "GLOBE": ["GLOBE"], "BALL": ["BALL"],
                   "BUTTERFLY": ["BUTTERFLY"], "CHECK": ["CHECK"]},
    "flange_type": {"WELDNECK": ["WELD NECK", "WELDNECK", "WELD-NECK"],
                     "SLIPON": ["SLIP-ON", "SLIP ON", "SLIPON"],
                     "BLIND": ["BLIND"],
                     "SOCKETWELD": ["SOCKET WELD", "SOCKETWELD", "SOCKET-WELD"]},
    "gasket_type": {"SPIRALWOUND": ["SPIRAL WOUND", "SPIRALWOUND"],
                     "RING JOINT": ["RING JOINT"], "FULL FACE": ["FULL FACE"]},
    "bearing_type": {"TAPERED ROLLER": ["TAPERED ROLLER"],  # check before plain ROLLER
                      "DEEP GROOVE BALL": ["DEEP GROOVE"], "ROLLER": ["ROLLER"]},
    "pump_type": {"CENTRIFUGAL": ["CENTRIFUGAL"], "SUBMERSIBLE": ["SUBMERSIBLE"],
                   "RECIPROCATING": ["RECIPROCATING"]},
    "fastener_type": {"HEX BOLT": ["HEXAGONAL HEAD BOLT", "HEX BOLT", "HEXAGONAL BOLT"],
                       "HEX NUT": ["HEXAGONAL NUT", "HEX NUT"],
                       "STUD": ["STUD BOLT", "STUD"],
                       "WASHER": ["FLAT WASHER", "WASHER"]},
    "filter_type": {"OIL FILTER": ["OIL FILTER"], "AIR FILTER": ["AIR FILTER"],
                     "FUEL FILTER": ["FUEL FILTER"],
                     "STRAINER": ["Y-TYPE STRAINER", "STRAINER"]},
}


def extract_type(text: str, field: str) -> str | None:
    vocab = TYPE_VOCAB.get(field, {})
    # Collapse whitespace runs first -- formatting noise (double spaces, odd punctuation
    # spacing) would otherwise break a literal multi-word keyword match like "TAPERED ROLLER".
    text_u = re.sub(r"\s+", " ", text.upper())
    for code, keywords in vocab.items():
        for kw in keywords:
            if kw in text_u:
                return code
    return None


def extract_schedule(text: str) -> str | None:
    m = re.search(r'\bSCH\s?(\d+)\b', text, re.IGNORECASE)
    return f"SCH{m.group(1)}" if m else None


def extract_model_identifier(text: str) -> str | None:
    match = re.search(r'\b[A-Z0-9]{3,}(?:-[A-Z0-9]+)+\b', text, re.IGNORECASE)
    return match.group(0).upper() if match else None


def extract_generic_attributes(text: str) -> dict:
    """Stable fallback schema for material families unknown to the trained taxonomy."""
    return {
        "material": extract_material_type(text),
        "grade": extract_grade(text),
        "nominal_size_mm": normalize_size_mm(text),
        "standard": extract_standard_code(text),
        "model": extract_model_identifier(text),
    }


def extract_attributes(description: str, specification: str, category: str) -> dict:
    """Category-aware structured-attribute extraction. Returns a dict with only the
    fields relevant to `category`; a missing field is represented as None, not 0 or "",
    so downstream comparison logic can tell 'absent' apart from 'zero'."""
    text = f"{description} {specification or ''}"
    fields = CATEGORY_FIELDS.get(category, [])
    if not fields:
        return extract_generic_attributes(text)
    out = {}
    for f in fields:
        if f == "material_type":
            out[f] = extract_material_type(text)
        elif f == "size_mm":
            out[f] = normalize_size_mm(text)
        elif f == "grade":
            out[f] = extract_grade(text)
        elif f == "standard":
            out[f] = extract_standard_code(text)
        elif f == "schedule":
            out[f] = extract_schedule(text)
        elif f == "pressure_class":
            out[f] = extract_pressure_class(text)
        elif f == "bearing_number":
            out[f] = extract_bearing_number(text)
        elif f == "bore_mm":
            out[f] = normalize_size_mm(text)
        elif f == "seal_type":
            out[f] = extract_seal_type(text)
        elif f == "power_kw":
            out[f] = extract_number_near(text, r'KW')
        elif f == "power_hp":
            out[f] = extract_number_near(text, r'H\.?P\.?')
        elif f == "voltage_v":
            v = extract_number_near(text, r'V\b')
            out[f] = v
        elif f == "phase":
            out[f] = extract_phase(text)
        elif f == "rpm":
            out[f] = extract_number_near(text, r'RPM')
        elif f == "cores":
            m = re.search(r'(\d+)\s?C(?:ore)?', text, re.IGNORECASE)
            out[f] = float(m.group(1)) if m else None
        elif f == "csa_sqmm":
            m = re.search(r'(\d+(?:\.\d+)?)\s?sq\.?\s?mm', text, re.IGNORECASE)
            out[f] = float(m.group(1)) if m else None
        elif f == "voltage_grade_kv":
            m = re.search(r'(\d+(?:\.\d+)?)\s?KV', text, re.IGNORECASE)
            out[f] = float(m.group(1)) if m else None
        elif f == "bolt_size":
            out[f] = extract_bolt_size(text)
        elif f == "micron_rating":
            m = re.search(r'(\d+)\s?MIC(?:RON)?', text, re.IGNORECASE)
            out[f] = float(m.group(1)) if m else None
        elif f in TYPE_VOCAB:
            out[f] = extract_type(text, f)
        else:
            out[f] = None
    return out
