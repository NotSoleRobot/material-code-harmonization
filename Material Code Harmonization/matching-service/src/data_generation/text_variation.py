"""
Turns a canonical material's structured attributes into realistic, varied CPSE-style
free-text descriptions -- the thing the ML system has to reverse-engineer.

Design choices (each tied to a listed failure mode from the strategy doc):
  - multiple "styles" (terse/verbose/legacy/operations) -> description-length variation
  - abbreviation vs spelled-out                            -> abbreviation variation
  - mm vs inch rendering                                   -> unit variation
  - token reordering                                       -> attribute-order variation
  - random casing/punctuation/whitespace noise             -> formatting variation
  - light typo injection                                   -> spelling-error variation
  - optional omission of a non-critical (or critical) attr -> missing-attribute variation
"""
import random
import re

MM_PER_INCH = 25.4

# Real pipe/valve/flange/gasket sizing uses trade "nominal" sizes (NPS/DN), not literal
# millimetre-to-inch arithmetic -- a DN50 / "2 inch" fitting is not literally 50.8mm, it's
# the trade-standard nominal pairing. Using literal *25.4 math here would silently corrupt
# every mm<->inch round trip (DN80 -> "3.1 inch" -> back to 78.7mm != 80mm), which is
# exactly the kind of unit-handling bug the whole project is trying to catch, not commit.
NOMINAL_MM_TO_INCH = {15: 0.5, 25: 1, 40: 1.5, 50: 2, 65: 2.5, 80: 3, 100: 4, 150: 6, 200: 8}
NOMINAL_INCH_TO_MM = {v: k for k, v in NOMINAL_MM_TO_INCH.items()}

MATERIAL_LONGFORM = {
    "CS": "Carbon Steel", "SS": "Stainless Steel", "GI": "Galvanised Iron",
    "PVC": "PVC", "CI": "Cast Iron", "BRONZE": "Bronze",
    "SS304": "Stainless Steel 304", "SS316": "Stainless Steel 316",
    "CAF": "Compressed Asbestos Fibre", "PTFE": "PTFE", "GRAPHITE": "Graphite",
    "COPPER": "Copper", "ALUMINIUM": "Aluminium",
}

VALVE_TYPE_LONGFORM = {"GATE": "Gate Valve", "GLOBE": "Globe Valve", "BALL": "Ball Valve",
                        "BUTTERFLY": "Butterfly Valve", "CHECK": "Check Valve"}
FLANGE_TYPE_LONGFORM = {"WELDNECK": "Weld Neck Flange", "SLIPON": "Slip-On Flange",
                         "BLIND": "Blind Flange", "SOCKETWELD": "Socket Weld Flange"}
GASKET_TYPE_LONGFORM = {"SPIRALWOUND": "Spiral Wound Gasket", "RING JOINT": "Ring Joint Gasket",
                         "FULL FACE": "Full Face Gasket"}
BEARING_TYPE_LONGFORM = {"DEEP GROOVE BALL": "Deep Groove Ball Bearing",
                          "ROLLER": "Roller Bearing", "TAPERED ROLLER": "Tapered Roller Bearing"}
PUMP_TYPE_LONGFORM = {"CENTRIFUGAL": "Centrifugal Pump", "SUBMERSIBLE": "Submersible Pump",
                       "RECIPROCATING": "Reciprocating Pump"}
FASTENER_TYPE_LONGFORM = {"HEX BOLT": "Hexagonal Head Bolt", "HEX NUT": "Hexagonal Nut",
                           "STUD": "Stud Bolt", "WASHER": "Flat Washer"}
FILTER_TYPE_LONGFORM = {"OIL FILTER": "Oil Filter", "AIR FILTER": "Air Filter",
                         "FUEL FILTER": "Fuel Filter", "STRAINER": "Y-Type Strainer"}

STYLES = ["TERSE", "VERBOSE", "LEGACY", "OPERATIONS"]


def mm_to_inch_str(mm: float) -> str:
    inch = NOMINAL_MM_TO_INCH.get(int(mm))
    if inch is None:
        inch = mm / MM_PER_INCH  # fallback for a size outside the curated nominal table
    if abs(inch - round(inch)) < 1e-6:
        return f'{int(inch)}"'
    return f'{inch:g}"'


def render_size(mm: float, unit_style: str) -> str:
    if unit_style == "inch":
        return mm_to_inch_str(mm)
    if unit_style == "mm_decimal":
        return f"{mm:.1f}MM"
    return f"{int(mm)}MM"


def inject_case_noise(s: str, rng: random.Random) -> str:
    r = rng.random()
    if r < 0.35:
        return s.upper()
    if r < 0.55:
        return s.lower()
    if r < 0.65:
        return s.title()
    return s


def inject_punctuation_noise(s: str, rng: random.Random) -> str:
    s = re.sub(r"\s*,\s*", rng.choice([", ", " , ", ","]), s)
    if rng.random() < 0.3:
        s = s.replace(" - ", rng.choice(["-", " -", "- "]))
    if rng.random() < 0.25:
        s = re.sub(r"\s+", " ", s).strip()
        s = s.replace(" ", rng.choice([" ", "  "]))
    return s


def inject_typo(s: str, rng: random.Random, rate: float = 0.08) -> str:
    """Light character-level noise -- swap/drop a character in a low-risk (non-numeric,
    non-critical-token) spot, simulating manual data-entry error. Kept mild on purpose:
    real legacy ERP text is messy but still mostly legible."""
    if rng.random() > rate:
        return s
    chars = list(s)
    candidates = [i for i, c in enumerate(chars) if c.isalpha()]
    if not candidates:
        return s
    i = rng.choice(candidates)
    op = rng.choice(["swap", "drop", "dup"])
    if op == "drop" and len(chars) > 3:
        del chars[i]
    elif op == "dup":
        chars.insert(i, chars[i])
    elif op == "swap" and i < len(chars) - 1:
        chars[i], chars[i + 1] = chars[i + 1], chars[i]
    return "".join(chars)


def maybe_abbrev(word_long: str, word_short: str, rng: random.Random) -> str:
    return word_short if rng.random() < 0.5 else word_long


class TextVariationEngine:
    def __init__(self, seed: int = 42):
        self.rng = random.Random(seed)

    def render(self, category: str, attrs: dict, style: str | None = None,
               unit_style: str | None = None, omit_attrs: set | None = None,
               include_noise: bool = True) -> tuple[str, str]:
        """Returns (description, specification) strings for one CPSE-style record."""
        style = style or self.rng.choice(STYLES)
        unit_style = unit_style or self.rng.choice(["mm", "inch", "mm_decimal"])
        omit_attrs = omit_attrs or set()
        a = {k: v for k, v in attrs.items() if k not in omit_attrs}

        method = getattr(self, f"_render_{category.lower()}", None)
        if method is None:
            raise ValueError(f"No renderer for category {category}")
        desc, spec = method(a, style, unit_style)

        if include_noise:
            desc = inject_case_noise(desc, self.rng)
            desc = inject_punctuation_noise(desc, self.rng)
            desc = inject_typo(desc, self.rng)
        return desc.strip(), spec.strip()

    # ---- category renderers -------------------------------------------------
    def _render_pipe(self, a, style, unit_style):
        mat = a.get("material")
        mat_long = MATERIAL_LONGFORM.get(mat, mat)
        size = render_size(a["nominal_size_mm"], unit_style) if "nominal_size_mm" in a else ""
        sch = a.get("schedule", "")
        grade = a.get("grade", "")
        std = a.get("standard", "")
        mat_tok = maybe_abbrev(mat_long, mat, self.rng) if mat else ""
        if style == "TERSE":
            desc = f"{mat_tok} PIPE {size} {sch}".strip()
        elif style == "VERBOSE":
            desc = f"{mat_long} Pipe, {size} Nominal Diameter, {sch}, Seamless".strip()
        elif style == "LEGACY":
            desc = f"{mat}. PIPE {size} DIA {sch} TYPE".strip()
        else:  # OPERATIONS
            desc = f"Pipe - {mat_long}, Size: {size}, Schedule: {sch}".strip()
        spec = f"{std} {grade}".strip()
        return desc, spec

    def _render_valve(self, a, style, unit_style):
        vt = VALVE_TYPE_LONGFORM.get(a.get("valve_type"), a.get("valve_type"))
        vt_short = a.get("valve_type", "")
        mat = MATERIAL_LONGFORM.get(a.get("body_material"), a.get("body_material"))
        size = render_size(a["nominal_size_mm"], unit_style) if "nominal_size_mm" in a else ""
        pc = a.get("pressure_class", "")
        std = a.get("standard", "")
        if style == "TERSE":
            desc = f"{vt_short} VALVE {size} {pc}".strip()
        elif style == "VERBOSE":
            desc = f"{vt}, {size} Size, {mat} Body, {pc}".strip()
        elif style == "LEGACY":
            desc = f"VALVE-{vt_short} {size} {pc} {a.get('body_material','')}".strip()
        else:
            desc = f"{vt} - {size}, {a.get('body_material','')} Body, {pc}".strip()
        return desc, std

    def _render_flange(self, a, style, unit_style):
        ft = FLANGE_TYPE_LONGFORM.get(a.get("flange_type"), a.get("flange_type"))
        mat = a.get("material", "")
        size = render_size(a["nominal_size_mm"], unit_style) if "nominal_size_mm" in a else ""
        pc = a.get("pressure_class", "")
        std = a.get("standard", "")
        if style == "TERSE":
            desc = f"{mat} FLANGE {size} {pc}".strip()
        elif style == "VERBOSE":
            desc = f"{ft}, {mat}, {size} Nominal Bore, {pc}".strip()
        elif style == "LEGACY":
            desc = f"FLG {a.get('flange_type','')} {size} {pc} {mat}".strip()
        else:
            desc = f"Flange - {ft}, Size {size}, {pc}, Material {mat}".strip()
        return desc, std

    def _render_gasket(self, a, style, unit_style):
        gt = GASKET_TYPE_LONGFORM.get(a.get("gasket_type"), a.get("gasket_type"))
        mat = MATERIAL_LONGFORM.get(a.get("material"), a.get("material"))
        size = render_size(a["nominal_size_mm"], unit_style) if "nominal_size_mm" in a else ""
        pc = a.get("pressure_class", "")
        std = a.get("standard", "")
        if style == "TERSE":
            desc = f"GASKET {a.get('material','')} {size} {pc}".strip()
        elif style == "VERBOSE":
            desc = f"{gt}, {mat}, {size}, {pc}".strip()
        elif style == "LEGACY":
            desc = f"GSKT-{a.get('material','')} {size} {pc}".strip()
        else:
            desc = f"Gasket - {gt}, {size}, {pc}".strip()
        return desc, std

    def _render_bearing(self, a, style, unit_style):
        bt = BEARING_TYPE_LONGFORM.get(a.get("bearing_type"), a.get("bearing_type"))
        num = a.get("bearing_number", "")
        seal = a.get("seal_type", "")
        bore = a.get("bore_mm")
        bore_s = f"{int(bore)}MM" if bore is not None else ""
        mfr = a.get("manufacturer", "")
        if style == "TERSE":
            desc = f"{mfr} BRG {num}-{seal}".strip()
        elif style == "VERBOSE":
            desc = f"Sealed {bt}, Bore {bore_s}, {mfr} {num} {seal}".strip()
        elif style == "LEGACY":
            desc = f"BEARING {num} {seal} {mfr}".strip()
        else:
            desc = f"Ball Bearing - {num}-{seal}, {mfr}".strip()
        return desc, f"Bore {bore_s}".strip()

    def _render_motor(self, a, style, unit_style):
        kw = a.get("power_kw")
        v = a.get("voltage_v")
        ph = a.get("phase", "")
        rpm = a.get("rpm")
        enc = a.get("enclosure", "")
        if style == "TERSE":
            desc = f"MTR {kw}KW {v}V {ph} {rpm}RPM".strip()
        elif style == "VERBOSE":
            desc = (f"{kw} KW Electric Motor, {v} Volt, "
                     f"{'3 Phase' if ph=='3PH' else '1 Phase'}, {rpm} RPM").strip()
        elif style == "LEGACY":
            desc = f"MOTOR-{kw}KW/{v}V/{ph}".strip()
        else:
            desc = f"Motor - {kw}KW / {v}V / {ph} / {rpm}RPM".strip()
        return desc, enc

    def _render_cable(self, a, style, unit_style):
        cond = MATERIAL_LONGFORM.get(a.get("conductor"), a.get("conductor"))
        cores = a.get("cores")
        csa = a.get("csa_sqmm")
        vg = a.get("voltage_grade_kv")
        std = a.get("standard", "")
        if style == "TERSE":
            desc = f"CABLE {a.get('conductor','')} {cores}Cx{csa}SQMM".strip()
        elif style == "VERBOSE":
            desc = f"{cond} Conductor Cable, {cores} Core, {csa} sq.mm, {vg}KV Grade".strip()
        elif style == "LEGACY":
            desc = f"CBL-{cores}Cx{csa}-{a.get('conductor','')}".strip()
        else:
            desc = f"Cable - {cond}, {cores} Core x {csa} sq.mm, {vg}KV".strip()
        return desc, std

    def _render_pump(self, a, style, unit_style):
        pt = PUMP_TYPE_LONGFORM.get(a.get("pump_type"), a.get("pump_type"))
        hp = a.get("power_hp")
        ph = a.get("phase", "")
        mfr = a.get("manufacturer", "")
        if style == "TERSE":
            desc = f"{a.get('pump_type','')} PUMP {hp}HP".strip()
        elif style == "VERBOSE":
            desc = f"{pt}, {hp} HP Motor, {'3 Phase' if ph=='3PH' else '1 Phase'}, {mfr}".strip()
        elif style == "LEGACY":
            desc = f"PUMP-{a.get('pump_type','')}-{hp}HP".strip()
        else:
            desc = f"{pt} - {hp} H.P., {ph}".strip()
        return desc, mfr

    def _render_fastener(self, a, style, unit_style):
        ft = FASTENER_TYPE_LONGFORM.get(a.get("fastener_type"), a.get("fastener_type"))
        size = a.get("size", "")
        grade = a.get("grade", "")
        coat = a.get("coating", "")
        if style == "TERSE":
            desc = f"{a.get('fastener_type','')} {size}".strip()
        elif style == "VERBOSE":
            desc = f"{ft}, {size} Size, Grade {grade}, {coat}".strip()
        elif style == "LEGACY":
            desc = f"{a.get('fastener_type','').replace(' ', '')}-{size}".strip()
        else:
            desc = f"{ft} - {size}, Gr {grade}".strip()
        return desc, coat

    def _render_filter(self, a, style, unit_style):
        ft = FILTER_TYPE_LONGFORM.get(a.get("filter_type"), a.get("filter_type"))
        micron = a.get("micron_rating")
        size = render_size(a["connection_size_mm"], unit_style) if "connection_size_mm" in a else ""
        mfr = a.get("manufacturer", "")
        if style == "TERSE":
            desc = f"{a.get('filter_type','')} {micron}MIC {size}".strip()
        elif style == "VERBOSE":
            desc = f"{ft}, {micron} Micron Rating, {size} Connection, {mfr}".strip()
        elif style == "LEGACY":
            desc = f"FLTR-{a.get('filter_type','').replace(' ','')}-{micron}M".strip()
        else:
            desc = f"{ft} - {micron} Micron, {size}".strip()
        return desc, mfr
