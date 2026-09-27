"""
Category schemas for the SIH26099 synthetic material world.

Each category defines:
  - attrs: ordered dict of attribute_name -> list of possible values (the domain)
  - identity_critical: attributes whose mismatch means "different material" (NOT_A_MATCH)
  - variant_critical: attributes whose mismatch means "same family, different variant" (VARIANT)
  - non_critical: attributes that add detail but don't by themselves decide equivalence
    (manufacturer, standard-body wording, etc.)

This is a deliberately curated, illustrative attribute model for an oil & gas / heavy
industry MRO material set (SIH26099 is owned by the Ministry of Petroleum & Natural Gas).
It is NOT an authoritative engineering standard -- it's a synthetic but structurally
realistic approximation used to generate labeled training/test data.
"""

CATEGORIES = {
    "PIPE": {
        "attrs": {
            "material": ["CS", "SS", "GI", "PVC"],
            "grade": ["GrB", "TP304", "TP316", "GrA", "PVC-U"],
            "nominal_size_mm": [15, 25, 40, 50, 65, 80, 100, 150, 200],
            "schedule": ["SCH40", "SCH80", "SCH160"],
        },
        "identity_critical": ["material"],
        "variant_critical": ["nominal_size_mm", "schedule", "grade"],
        "non_critical": ["standard"],
        "standard_domain": ["ASTM A312", "ASTM A106", "IS 1239", "IS 3589", "API 5L"],
    },
    "VALVE": {
        "attrs": {
            "valve_type": ["GATE", "GLOBE", "BALL", "BUTTERFLY", "CHECK"],
            "body_material": ["CI", "CS", "SS", "BRONZE"],
            "nominal_size_mm": [25, 40, 50, 80, 100, 150, 200],
            "pressure_class": ["CLASS150", "CLASS300", "CLASS600", "PN10", "PN16"],
        },
        "identity_critical": ["valve_type", "body_material"],
        "variant_critical": ["nominal_size_mm", "pressure_class"],
        "non_critical": ["standard"],
        "standard_domain": ["API 600", "ASME B16.34", "IS 780"],
    },
    "FLANGE": {
        "attrs": {
            "flange_type": ["WELDNECK", "SLIPON", "BLIND", "SOCKETWELD"],
            "material": ["CS", "SS304", "SS316"],
            "nominal_size_mm": [25, 50, 80, 100, 150, 200],
            "pressure_class": ["CLASS150", "CLASS300", "CLASS600"],
        },
        "identity_critical": ["flange_type", "material"],
        "variant_critical": ["nominal_size_mm", "pressure_class"],
        "non_critical": ["standard"],
        "standard_domain": ["ASME B16.5", "IS 6392"],
    },
    "GASKET": {
        "attrs": {
            "gasket_type": ["SPIRALWOUND", "RING JOINT", "FULL FACE"],
            "material": ["CAF", "PTFE", "SS304", "GRAPHITE"],
            "nominal_size_mm": [25, 50, 80, 100, 150],
            "pressure_class": ["CLASS150", "CLASS300"],
        },
        "identity_critical": ["gasket_type", "material"],
        "variant_critical": ["nominal_size_mm", "pressure_class"],
        "non_critical": ["standard"],
        "standard_domain": ["ASME B16.20", "ASME B16.21"],
    },
    "BEARING": {
        "attrs": {
            "bearing_type": ["DEEP GROOVE BALL", "ROLLER", "TAPERED ROLLER"],
            "seal_type": ["2RS", "ZZ", "OPEN"],
            "bore_mm": [15, 17, 20, 25, 30, 35, 40],
            "bearing_number": ["6202", "6203", "6204", "6205", "6206", "6305"],
        },
        "identity_critical": ["bearing_type", "bearing_number"],
        "variant_critical": ["bore_mm", "seal_type"],
        "non_critical": ["manufacturer"],
        "standard_domain": ["DIN 625", "ISO 15"],
    },
    "MOTOR": {
        "attrs": {
            "power_kw": [3.7, 5.5, 7.5, 11, 15, 18.5, 22, 30],
            "voltage_v": [230, 415],
            "phase": ["1PH", "3PH"],
            "rpm": [960, 1440, 2880],
        },
        "identity_critical": ["voltage_v", "phase"],
        "variant_critical": ["power_kw", "rpm"],
        "non_critical": ["enclosure", "manufacturer"],
        "standard_domain": ["IS 325", "IEC 60034"],
    },
    "CABLE": {
        "attrs": {
            "conductor": ["COPPER", "ALUMINIUM"],
            "cores": [1, 2, 3, 4],
            "csa_sqmm": [1.5, 2.5, 4, 6, 10, 16, 25, 35],
            "voltage_grade_kv": [1.1, 3.3, 11],
        },
        "identity_critical": ["conductor"],
        "variant_critical": ["cores", "csa_sqmm", "voltage_grade_kv"],
        "non_critical": ["standard", "armour"],
        "standard_domain": ["IS 1554", "IS 7098"],
    },
    "PUMP": {
        "attrs": {
            "pump_type": ["CENTRIFUGAL", "SUBMERSIBLE", "RECIPROCATING"],
            "power_hp": [3, 5, 7.5, 10, 15, 20],
            "phase": ["1PH", "3PH"],
        },
        "identity_critical": ["pump_type"],
        "variant_critical": ["power_hp", "phase"],
        "non_critical": ["manufacturer"],
        "standard_domain": ["IS 5120", "API 610"],
    },
    "FASTENER": {
        "attrs": {
            "fastener_type": ["HEX BOLT", "HEX NUT", "STUD", "WASHER"],
            "size": ["M8", "M10", "M12", "M16", "M20"],
            "grade": ["4.6", "8.8", "10.9"],
            "coating": ["ZINC PLATED", "PLAIN", "GALVANIZED"],
        },
        "identity_critical": ["fastener_type"],
        "variant_critical": ["size", "grade"],
        "non_critical": ["coating", "standard"],
        "standard_domain": ["IS 1364", "ASTM A193"],
    },
    "FILTER": {
        "attrs": {
            "filter_type": ["OIL FILTER", "AIR FILTER", "FUEL FILTER", "STRAINER"],
            "micron_rating": [5, 10, 25, 50],
            "connection_size_mm": [25, 40, 50, 80],
        },
        "identity_critical": ["filter_type"],
        "variant_critical": ["micron_rating", "connection_size_mm"],
        "non_critical": ["manufacturer"],
        "standard_domain": ["ISO 16889"],
    },
}

# CPSEs used to simulate cross-organizational description style. Deliberately spans the
# petroleum sector (the owning ministry) plus other heavy-industry CPSEs (matches the PS
# background text, which references Power/Steel/Mining/Heavy Engineering too).
CPSES = ["ONGC", "IOCL", "BPCL", "HPCL", "GAIL", "OIL", "SAIL", "NTPC", "BHEL", "CIL"]

# A small, explicitly curated, illustrative cross-standard equivalence table used ONLY to
# generate FUNCTIONALLY_EQUIVALENT demo pairs. This is a simplified stand-in for the kind
# of standards-equivalence knowledge a real deployment would need to source from an
# authoritative engineering body -- it is not presented as engineering fact.
EQUIVALENCE_RULES = [
    # (category, attr_overrides_a, attr_overrides_b) -- both sides share all other attrs
    {
        "category": "PIPE",
        "a": {"material": "CS", "grade": "GrB", "standard": "ASTM A106"},
        "b": {"material": "CS", "grade": "GrB", "standard": "IS 1239"},
        "note": "ASTM A106 GrB and IS 1239 GrB seamless carbon steel pipe are treated as "
                "illustratively equivalent for this demo dataset (both common carbon-steel "
                "seamless pipe specs) -- a real deployment must source this from an "
                "authoritative standards-equivalence reference, not assume it.",
    },
    {
        "category": "VALVE",
        "a": {"valve_type": "GATE", "body_material": "CS", "standard": "API 600"},
        "b": {"valve_type": "GATE", "body_material": "CS", "standard": "IS 780"},
        "note": "Illustrative demo equivalence only.",
    },
    {
        "category": "FLANGE",
        "a": {"flange_type": "WELDNECK", "material": "CS", "standard": "ASME B16.5"},
        "b": {"flange_type": "WELDNECK", "material": "CS", "standard": "IS 6392"},
        "note": "Illustrative demo equivalence only.",
    },
    {
        "category": "CABLE",
        "a": {"conductor": "COPPER", "standard": "IS 1554"},
        "b": {"conductor": "COPPER", "standard": "IS 7098"},
        "note": "Illustrative demo equivalence only -- both common Indian copper power-cable "
                "standards; a real deployment must verify voltage-grade/application scope "
                "before treating them as interchangeable.",
    },
]
