// MECHA: a battle-worn mecha on one knee, head bowed, both hands on a sword driven into the
// ground, its half-bared core glowing. Drawn only with letters and symbols. The sword falls from
// the sky and impales the ground, the mecha's pieces fade in, the eyes light green and the core
// ignites with a bloom.
//
// How it is drawn: the mecha and its sword are a 68x68 map of character cells, baked from an
// ASCII reference picture (the "Last Stand" pack's Gemini_Generated_Image_cfrn4ecfrn4ecfrn.jpeg) by
// tools/shader-preview/bake_ascii.py. Each cell holds which ink it uses and
// how bright it is. One map cell is exactly one character cell on screen. The field pass (uPass 0)
// works out each 6x8-pixel cell once: its material, its brightness (the parts the reference shows
// lit orange only light up once the core is on), and how much it glows. The full-size pass
// (uPass 1) draws the cell's glyph and the core's bloom.
//
// Timeline after a cold start (uTime from 0):
//   0.3 - 1.3 s  the sword falls from above the screen and drives into the ground (shake, dust)
//   1.8 - 4.4 s  the mecha's pieces fade in one by one, each scanning in glyph by glyph
//   4.6 s        the eyes flicker on, green
//   5.0 - 6.0 s  the half-exposed core ignites; rays of light spread from it, sparks start to drip
// Then it idles: the core breathes (and swells with the music's bass), sparks fall from the torn
// arm, smoke drifts, and now and then a row of cells glitches. A category switch flares the core.

const float ROWS = 72.0;          // character rows along the short side (cells never under 6x8 render pixels)
const float E = 0.0125;           // one cell in figure units (ground y = 0, the sword at x = 0, pommel ~0.82)
const vec2 CORE = vec2(0.212, 0.455);    // the bared core
const vec2 GLARE = vec2(0.30, 0.455);    // where its light flares, over the torn arm
const float T_FALL = 0.3;         // the sword starts to fall
const float T_IMPACT = 1.3;       // and hits the ground
const float T_BUILD = 1.8;        // first piece starts to fade in
const float T_BUILD_SPAN = 2.0;   // last piece starts this much later
const float T_EYES = 4.6;
const float T_CORE = 5.0;
const float CORE_BLOOM = 0.9;     // the core's soft light over everything
const float PI = 3.14159265;
const float TAU = 6.2831853;

// Materials (packed into the field as id / 15). The first seven are also the map's ink classes.
// M_WARM and M_TINT are armour in the core's light: deep orange, and pale armour only tinted by it.
const int M_NONE = 0, M_WHITE = 1, M_WARM = 2, M_RED = 3, M_GOLD = 4, M_CORE = 5, M_STEEL = 6,
          M_TINT = 7, M_EYE = 8, M_SPARK = 9, M_DEBRIS = 10, M_SMOKE = 11, M_SCAN = 12, M_STREAK = 13, M_HILT = 14;

// The figure: 6 bits a cell (ink class * 8 + brightness 1..7), five cells an int, lowest bits
// first, rows from the bottom up. Row 2 stands on the ground; column 28 is the sword's middle.
const int SWORD_COL = 28, GROUND_ROW = 2, GUARD_ROW = 44;   // from the guard up, the sword is hilt
const ivec2 EYE_CELL = ivec2(38, 53);    // left eye; the right one is 5 cells along
const int BW = 68, BH = 68;
const int B[925] = int[925](
    0, 0, 0, 0, 0, 872415284, 0, 0, 0, 0,
    0, 0, 0, 0, 0, 0, 0, 0, 13631488, 920076288,
    0, 0, 0, 0, 0, 0, 0, 0, 436207616, 0,
    0, 1056677888, 852233469, 1006333300, 1005850428, 3902, 15466496, 0, 989855744, 973328444,
    62, 26, 16252928, 436207616, 26, 2621440, 902827264, 1056698230, 3903, 16236544,
    0, 0, 973078528, 254011, 153878528, 0, 1006724800, 187428863, 6815744, 906182656,
    14376128, 0, 0, 0, 150994944, 170963660, 15708160, 4093, 238551040, 238609358,
    1073738702, 765, 20, 872640565, 3508, 0, 0, 0, 1073533642, 16777215,
    990101312, 0, 221773824, 221565837, 41743, 1056440320, 13893632, 920080439, 0, 0,
    167772160, 0, 0, 4095, 3904, 0, 0, 2621440, 1039708160, 16256907,
    14421312, 228726, 0, 0, 184590976, 11, 0, 0, 60, 0,
    0, 1056964608, 1060368319, 905973568, 903089600, 55, 0, 170393600, 3203082, 241664,
    0, 576, 0, 255386432, 222098382, 1073476558, 16777214, 922968064, 14638464, 0,
    167772160, 221033098, 719, 1005305661, 60, 60, 184549376, 255652751, 255390671, 1005846527,
    62, 922972214, 3572, 0, 234881792, 171221006, 15445707, 16772800, 0, 0,
    255590400, 255652815, 1073542031, 249854, 14155776, 936079415, 0, 184590336, 171504394, 1026863114,
    1022877565, 3967, 0, 0, 255652608, 255652815, 1073476303, 3838, 14626240, 228535,
    184549376, 255111178, 170176526, 990109580, 16772988, 0, 0, 0, 255639552, 221832142,
    16777215, 905969664, 853282290, 55, 3457024, 3470287, 1060684426, 59, 63, 0,
    0, 0, 238613440, 1060950862, 4094, 922972160, 14630336, 0, 255574987, 186908735,
    1073741774, 16777215, 0, 0, 0, 0, 238609359, 1073476494, 0, 922975415,
    3574, 251658240, 249855, 1073533578, 1073741823, 4095, 0, 0, 0, 238813184,
    221303629, 15986623, 853278720, 936603702, 0, 477484957, 220766208, 1073741822, 16777215, 0,
    0, 0, 0, 187749312, 1056752331, 1341, 14364096, 228791, 0, 0,
    1073537728, 1073217535, 4095, 0, 0, 0, 218103808, 170435276, 168030154, 922746907,
    937168370, 55, 0, 187170816, 1073741823, 16777215, 0, 0, 0, 0,
    188219392, 1042808832, 1013973051, 936079360, 14646720, 170394251, 63, 1073524736, 1073741823, 4095,
    0, 0, 0, 0, 41920, 254592, 15722249, 922975415, 170921463, 16556682,
    704, 1073737472, 1006632959, 1055916032, 0, 0, 0, 254279680, 7123594, 1059627067,
    853282686, 937390135, 170435264, 1056968651, 2404223, 1073741504, 4095, 15728640, 0, 0,
    0, 187486976, 1739, 1073738442, 14626240, 201555447, 1059893962, 1073738432, 183762815, 16777149,
    16515072, 0, 59, 0, 238026752, 489468683, 187215872, 938475471, 937168370, 221298743,
    234880957, 1022148351, 1006632959, 262143, 4094, 15466496, 0, 0, 218166080, 1040233098,
    1060942589, 936345535, 14646720, 1073741775, 619495423, 323901732, 1072422911, 1022623807, 1023414080, 3904,
    0, 218103808, 187482831, 184279818, 1073738699, 922971383, 187960823, 1073334043, 801010316, 1073576943,
    1057226688, 1056436224, 1073221568, 0, 0, 238813184, 170435212, 1060106174, 870060031, 937390134,
    436253376, 168034074, 801012711, 1073217519, 1073737791, 1057222719, 4032, 0, 0, 187757440,
    1056486027, 1073738646, 14106048, 201555447, 16510666, 800895807, 1067383791, 1023410175, 15990527, 16777215,
    61, 0, 0, 204260047, 1056961355, 922996799, 937168243, 976007991, 201064448, 798915070,
    1073739239, 1073729534, 1073483518, 16248895, 0, 0, 222035968, 221565708, 1040449535, 902787130,
    215969265, 1073533706, 1073483775, 1073741823, 1073741823, 1073741823, 1073741783, 0, 0, 0,
    238609344, 1073533773, 262079, 936074487, 218107382, 1040184205, 352147200, 1073741823, 1073737727, 1073741694,
    1072525184, 0, 0, 251658240, 221832079, 1056964558, 870059967, 937131317, 167772160, 352580299,
    402485142, 1073741821, 1073471487, 1012135423, 259479, 0, 0, 238874624, 1060426638, 1073528831,
    903298508, 228727, 2663051, 374955965, 1073575381, 1073741823, 1073741759, 485492159, 63, 0,
    0, 238613440, 1073738574, 925945600, 903830899, 2621495, 1056964608, 1062303060, 1073741823, 1073741823,
    368836607, 15470336, 0, 0, 251899904, 221303759, 201588735, 902788047, 14642677, 187171520,
    396358986, 1073575383, 1073741823, 1073741823, 6819712, 3739, 0, 0, 205316096, 1073533774,
    253820, 936865015, 167775735, 302026762, 801012183, 1073673687, 1073741823, 1073732071, 218361536, 0,
    0, 0, 255652736, 253903, 870055936, 937393526, 16293888, 375123968, 392000983, 1073741783,
    1067450367, 1039654975, 261952, 0, 0, 0, 0, 1005846528, 903560700, 228855,
    385344256, 805138391, 392100847, 1073741823, 1073741823, 1061769152, 62, 0, 0, 111232,
    0, 905969664, 937381234, 204473591, 1073730509, 801043923, 1073642479, 1073741823, 1073741823, 16777162,
    0, 184549376, 0, 6926336, 0, 889417728, 1004502517, 221295296, 385709404, 801045487,
    1073741783, 1073741823, 1026539519, 4095, 0, 45888, 0, 6926336, 0, 936856885,
    183221751, 184599307, 801111884, 1063189487, 1073741823, 1073741823, 1073536957, 0, 218103808, 170435535,
    0, 6930432, 919650304, 937385280, 187478783, 208510987, 347961855, 1073741719, 1073741823, 1056964607,
    262142, 0, 255066112, 41743, 0, 116425, 886000780, 201293302, 203948683, 472957579,
    369097214, 1073741821, 1039912959, 1060421564, 63, 0, 255640332, 41677, 988282880, 838940443,
    920603890, 187215863, 187482111, 1026809854, 1064423357, 385875927, 989573140, 16777099, 0, 222035968,
    255652814, 167772875, 212810, 50, 903086080, 668949939, 664697319, 237768511, 1062467422, 385613823,
    1059587284, 4094, 0, 222097408, 251720655, 1006633806, 920087926, 903040310, 937127285, 664796663,
    664779111, 455918284, 1073475597, 385609727, 1056155350, 0, 0, 255651840, 238874639, 2883598,
    0, 167992498, 654565386, 334916071, 173699131, 3453578, 255852479, 1056757455, 258048, 0,
    0, 255652800, 255652815, 844, 0, 1026073920, 351793215, 988395136, 2708928, 511442955,
    255653726, 7664591, 0, 0, 0, 255652815, 188281807, 41600, 1036988416, 16506880,
    469762048, 335021333, 187212438, 1930, 255652812, 1871, 0, 0, 251658240, 255652815,
    184599503, 49802, 1006096384, 221249599, 1073533696, 5242816, 254325450, 237827023, 238875598, 62,
    0, 0, 255590400, 255652815, 170701774, 886255628, 16760576, 221045642, 1073479630, 187424703,
    255652555, 267441103, 1022948303, 0, 0, 0, 3993600, 255652736, 3715788, 1023098034,
    237817851, 1060433613, 1056961855, 170439359, 255652815, 255652751, 250830, 0, 0, 201326592,
    2671564, 238871503, 869008267, 217559090, 255640206, 1073573583, 16769023, 254582784, 255652815, 255390671,
    62, 0, 0, 53248, 255648718, 255652751, 232750464, 3723264, 459858894, 1073741821,
    220770301, 1056490316, 255652539, 16311247, 0, 0, 0, 255328270, 255652815, 3466190,
    167816384, 188007373, 1064084364, 1072951231, 201372362, 973333259, 4094, 3967, 0, 0,
    171704320, 255652746, 255386575, 855847887, 255374090, 255648399, 1060959898, 62334, 204996608, 1056440382,
    1072697340, 0, 0, 0, 255116032, 238875599, 238875599, 3354675, 255127552, 477152207,
    1060958989, 28, 1044177664, 258048, 258048, 0, 0, 0, 255652814, 255652815,
    13631501, 51, 255652815, 205059023, 1044181837, 0, 15987471, 245819, 0, 0,
    0, 0, 238875599, 0, 13372684, 3731456, 255652815, 188007311, 16576512, 251658240,
    15732687, 0, 0, 0, 0, 0, 0, 875823104, 234884288, 0,
    238875599, 11, 15523840, 255123456, 989855806, 0, 0, 0, 0, 0,
    0, 855855821, 0, 251658240, 58319, 0, 0, 8180668, 0, 0,
    0, 0, 0, 0, 171180032, 204852, 0, 221511680, 0, 0,
    201326592, 119754, 0, 0, 0, 0, 0, 0, 13370058, 50,
    0, 0, 0, 0, 238288896, 15, 0, 0, 0, 0,
    0, 204472320, 13110464, 0, 0, 0, 0, 0, 1027138304, 0,
    0, 0, 0, 0, 0, 45888, 3264, 0, 0, 0,
    0, 0, 250767, 0, 0, 0, 0, 0, 204996608, 869220362,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    0, 0, 2941632, 15944887, 0, 0, 0, 0, 0, 0,
    0, 0, 0, 0, 0, 0, 903086080, 52, 0, 0,
    0, 0, 0, 0, 0
);

// 5x7 glyphs, one 5-bit row each, top row first, leftmost dot in bit 4. The first 15 are a ramp
// ordered by lit dots, sparsest first; then a few shapes picked by material.
const int G[140] = int[140](
    0, 0, 0, 0, 0, 12, 12,         //  0 .
    0, 0, 0, 0, 12, 4, 8,          //  1 ,
    0, 0, 0, 31, 0, 0, 0,          //  2 -
    0, 12, 12, 0, 12, 4, 8,        //  3 ;
    0, 12, 12, 0, 12, 12, 0,       //  4 :
    0, 4, 4, 31, 4, 4, 0,          //  5 +
    0, 0, 17, 10, 4, 10, 17,       //  6 x
    0, 0, 31, 0, 31, 0, 0,         //  7 =
    0, 4, 21, 14, 21, 4, 0,        //  8 *
    0, 0, 14, 17, 17, 17, 14,      //  9 o
    24, 25, 2, 4, 8, 19, 3,        // 10 %
    12, 18, 20, 8, 21, 18, 13,     // 11 &
    14, 17, 17, 14, 17, 17, 14,    // 12 8
    10, 10, 31, 10, 31, 10, 10,    // 13 #
    14, 17, 23, 21, 23, 16, 14,    // 14 @
    4, 4, 4, 4, 4, 4, 4,           // 15 |
    0, 0, 0, 0, 0, 0, 31,          // 16 _
    14, 17, 19, 21, 25, 17, 14,    // 17 0
    31, 31, 31, 31, 31, 31, 31,    // 18 block
    10, 10, 10, 10, 10, 10, 10     // 19 ||
);
const int GL_DOT = 0, GL_COMMA = 1, GL_DASH = 2, GL_COLON = 4, GL_PLUS = 5, GL_EQ = 7, GL_STAR = 8,
          GL_EIGHT = 12, GL_HASH = 13, GL_AT = 14, GL_BAR = 15, GL_UNDER = 16, GL_ZERO = 17, GL_BLOCK = 18, GL_BARS = 19;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x),
               mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}

float glyph(vec2 cellUv, int id) {
    vec2 g = cellUv * vec2(6.0, 8.0);
    ivec2 d = ivec2(floor(g));
    if (id < 0 || d.x >= 5 || d.y >= 7) return 0.0;
    int row = G[id * 7 + (6 - d.y)];
    float lit = float((row >> (4 - d.x)) & 1);
    vec2 f = fract(g) - 0.5;
    return lit * (1.0 - smoothstep(0.34, 0.5, max(abs(f.x), abs(f.y))));
}

vec2 cellSize() {
    float cellH = max(8.0, floor(min(uResolution.x, uResolution.y) / ROWS));
    return vec2(max(6.0, floor(cellH * 0.75)), cellH);
}

// The cell the sword stands in. Landscape puts the figure right of centre, clear of the category
// column; portrait centres it, higher up.
vec2 anchorCell(vec2 cell) {
    float aspect = uResolution.x / uResolution.y;
    vec2 px = aspect > 1.3 ? vec2(0.5 * uResolution.x + 0.20 * uResolution.y, 0.05 * uResolution.y)
                           : vec2(0.5 * uResolution.x - 5.5 * cell.x, 0.28 * uResolution.y);
    return floor(px / cell);
}

vec2 toFigure(vec2 px) {
    vec2 cell = cellSize();
    return (px / cell - anchorCell(cell) - vec2(0.5, 0.0)) * E;
}

// A capsule whose radius runs from ra at a to rb at b.
float sdLimb(vec2 p, vec2 a, vec2 b, float ra, float rb) {
    vec2 pa = p - a, ba = b - a;
    float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
    return length(pa - ba * h) - mix(ra, rb, h);
}

// ---- Timeline -------------------------------------------------------------------------------

// How far above its resting place the sword is: it falls with gravity, then stays.
float swordLift(float t) {
    float k = clamp((t - T_FALL) / (T_IMPACT - T_FALL), 0.0, 1.0);
    return 1.45 * (1.0 - k * k);
}

float coreLevel(float t) {
    float on = smoothstep(T_CORE, T_CORE + 1.0, t);
    float breathe = 0.86 + 0.10 * sin(t * 2.3) + 0.05 * sin(t * 5.9 + 1.3);
    return on * (breathe + 0.35 * uAudioBass + 0.15 * uAudioBeat + 0.6 * uTransition);
}

float eyeLevel(float t) {
    float on = smoothstep(T_EYES, T_EYES + 0.12, t);
    // Flickers as it boots, then a rare stutter.
    float boot = t < T_EYES + 0.7 ? step(0.35, hash(vec2(floor(t * 24.0), 3.0))) : 1.0;
    float stutter = 1.0 - 0.7 * step(0.985, hash(vec2(floor(t * 9.0), 8.0)));
    return on * boot * stutter * (1.0 + 0.8 * uTransition);
}

// ---- The figure -----------------------------------------------------------------------------

// The map cell under a point: ink class * 8 + brightness, 0 where there is nothing.
ivec2 mapCell(vec2 p) {
    return ivec2(int(floor(p.x / E + 0.5)) + SWORD_COL, int(floor(p.y / E)) + GROUND_ROW);
}

int mapAt(ivec2 b) {
    if (b.x < 0 || b.y < 0 || b.x >= BW || b.y >= BH) return 0;
    int i = b.y * BW + b.x;
    return (B[i / 5] >> (6 * (i % 5))) & 63;
}

// What is drawn at p: (material, brightness 0..1, scanning in). The sword is there from the
// moment it falls; the mecha's pieces (blocks of 9x8 cells) fade in at random times, and within
// a piece cell by cell. cellHash: this screen cell's hash.
vec3 figure(vec2 p, float t, float cellHash) {
    if (p.y < 0.0) return vec3(0.0);            // under the ground
    float lift = swordLift(t);
    if (lift > 0.0) {
        ivec2 sb = mapCell(p - vec2(0.0, lift));
        int s = mapAt(sb);
        return (s >> 3) == M_STEEL ? vec3(float(sb.y >= GUARD_ROW ? M_HILT : M_STEEL), float(s & 7) / 7.0, 0.0) : vec3(0.0);
    }
    ivec2 b = mapCell(p);
    int v = mapAt(b);
    int cls = v >> 3;
    if (cls == M_NONE) return vec3(0.0);
    float lvl = float(v & 7) / 7.0;
    if (cls == M_STEEL) return vec3(float(b.y >= GUARD_ROW ? M_HILT : M_STEEL), lvl, 0.0);
    float part = hash(floor(vec2(b) / vec2(9.0, 8.0)) + 7.31);
    float since = t - T_BUILD - T_BUILD_SPAN * part - 0.6 * cellHash;
    if (since < 0.0) return vec3(0.0);
    if (b.y == EYE_CELL.y && (b.x == EYE_CELL.x || b.x == EYE_CELL.x + 5)) cls = M_EYE;
    return vec3(float(cls), lvl, since < 0.16 ? 1.0 : 0.0);
}

// ---- Field pass -----------------------------------------------------------------------------

// Pass 0: one texel per cell. r: brightness, g: material / 15, b: share of orange (core) light,
// a: glow for the bloom.
vec4 fieldPass(vec2 c, float t) {
    vec2 cell = cellSize();
    if (any(greaterThanEqual(c * cell, uResolution))) return vec4(0.0);

    // The ground shakes as the sword lands.
    float since = t - T_IMPACT;
    vec2 shake = since > 0.0 ? vec2(sin(t * 71.0), cos(t * 53.0)) * 7.0 * exp(-since * 7.0) : vec2(0.0);
    vec2 p = toFigure((c + 0.5) * cell + shake);
    float e = E;

    float core = coreLevel(t);
    float lit = clamp(core, 0.0, 1.0);          // how far the core's light has come up
    float eye = eyeLevel(t);
    vec3 fig = figure(p, t, hash(c));
    int mat = int(fig.x + 0.5);
    float base = fig.y;
    float level = 0.0, warm = 0.0, glow = 0.0;
    float grain = 0.85 + 0.3 * hash(c + 2.9);
    float nearCore = exp(-length((p - GLARE) * vec2(0.8, 1.0)) / 0.16);

    if (fig.z > 0.5) {
        mat = M_SCAN;                               // a cell scanning in
        level = 0.7 + 0.3 * hash(c + floor(t * 20.0));
    } else if (mat == M_CORE) {
        // Dead plating until it ignites, then the brightest thing on screen.
        level = mix(0.14, clamp(base * (0.75 + 0.3 * core), 0.0, 1.0), lit);
        warm = lit;
        glow = base * core;
    } else if (mat == M_EYE) {
        level = clamp(eye, 0.0, 1.0);
        glow = 0.35 * eye;
        if (eye < 0.1) { mat = M_WHITE; level = 0.2; }
    } else if (mat == M_WARM || mat == M_GOLD || mat == M_TINT) {
        // Armour the reference shows in the core's light: dim and cold until the core is on.
        float hot = base * (0.72 + 0.32 * core);
        level = clamp(mix(base * (mat == M_TINT ? 0.6 : 0.38), hot, lit) * grain, 0.0, 1.0);
        warm = lit;
        glow = 0.3 * lit * base * base * nearCore;
    } else if (mat == M_WHITE || mat == M_RED) {
        level = clamp(base * grain, 0.0, 1.0);
        warm = 0.55 * lit * nearCore;
    } else if (mat == M_STEEL || mat == M_HILT) {
        // The sword gleams as it lands, then catches the core's light down its right edge.
        float flash = since > 0.0 ? 1.5 * exp(-since * 3.0) : 0.0;
        level = clamp(base * (0.8 + 0.2 * grain) + flash, 0.0, 1.0);
        warm = lit * smoothstep(0.0, 0.04, p.x) * exp(-abs(p.y - CORE.y) / 0.3);
    }

    // Sparks dripping from the torn arm, each with a short tail. Drawn over the figure.
    float spark = 0.0;
    float sparksOn = smoothstep(T_CORE, T_CORE + 1.0, t);
    if (sparksOn > 0.0 && p.x > 0.25 && p.x < 0.62 && p.y < 0.5) {
        for (int i = 0; i < 6; i++) {
            float per = 1.3 + 0.4 * hash(vec2(float(i), 1.0));
            float ph = t / per + hash(vec2(float(i), 2.0));
            float k = floor(ph);
            float a = fract(ph) * per;
            vec2 o = vec2(0.36 + 0.05 * hash(vec2(k, float(i))), 0.27 + 0.16 * hash(vec2(float(i), k + 3.0)));
            vec2 v = vec2((hash(vec2(k, float(i) + 9.0)) - 0.25) * 0.14, 0.06 * hash(vec2(k, float(i) + 5.0)));
            vec2 head = o + v * a - vec2(0.0, 0.42 * a * a);
            float b = max(a - 0.07, 0.0);
            vec2 tail = o + v * b - vec2(0.0, 0.42 * b * b);
            float d = sdLimb(p, head, tail + vec2(0.0, 1e-4), e * 0.55, e * 0.2);
            if (d < 0.0 && head.y > 0.0) spark = max(spark, 1.0 - a / per);
        }
    }
    if (spark > 0.0) {
        return vec4(clamp(0.5 + 0.6 * spark, 0.0, 1.0) * sparksOn, float(M_SPARK) / 15.0, 1.0, 0.5 * spark);
    }

    if (mat == M_NONE && core > 0.0) {
        // Light from the core: rows of dashes close in, then rays broken into '+', mostly towards
        // the right as in the reference.
        vec2 q = p - GLARE;
        float r = length(q);
        float u = fract(atan(q.y, q.x) / TAU);
        float n = mix(vnoise(vec2(u * 150.0, t * 0.15)), vnoise(vec2((u - 1.0) * 150.0, t * 0.15)), smoothstep(0.97, 1.0, u));
        float reach = 0.10 + 0.24 * smoothstep(T_CORE + 0.2, T_CORE + 1.6, t) + 0.15 * uTransition;
        float side = 0.10 + 0.90 * smoothstep(-0.4, 0.8, cos(u * TAU - 0.15));
        float dots = 0.45 + 0.55 * step(0.4, fract(r * 45.0 - t * 0.7 + n * 2.0));
        float ray = (pow(n, 5.0) * 5.0 + 1.2 * exp(-r / 0.10)) * exp(-r / reach) * side * dots * core;
        if (ray > 0.07) {
            mat = M_SPARK;
            level = clamp(ray, 0.0, 1.0);
            warm = 1.0;
            glow = 0.45 * level;
        }
    }

    if (mat == M_NONE) {
        float gy = 0.01 + 0.025 * vnoise(vec2(p.x * 13.0, 0.5));
        float hc = hash(c + 3.7);
        vec2 dc = p;
        if (p.y < gy && p.y > gy - 0.14) {
            // Rubble, lit by the core and by the landing.
            float burst = since > 0.0 ? exp(-since * 2.5) * exp(-length(dc) / 0.12) : 0.0;
            float fromCore = 0.6 * core * exp(-length(p - vec2(GLARE.x, 0.0)) / 0.3);
            float glowLit = 0.1 + fromCore + 1.5 * burst;
            if (hc < 0.55 + 0.3 * smoothstep(gy - 0.05, gy, p.y)) {
                mat = M_DEBRIS;
                level = clamp(glowLit * (0.5 + 0.6 * hash(c + 1.3)), 0.0, 1.0);
                warm = (fromCore + 1.5 * burst) / glowLit;
            }
        } else if (since > 0.0 && since < 1.6) {
            // Clods thrown up by the landing.
            for (int i = 0; i < 9; i++) {
                float fi = float(i);
                vec2 v = vec2((hash(vec2(fi, 11.0)) - 0.5) * 0.7, 0.25 + 0.45 * hash(vec2(fi, 12.0)));
                vec2 at = vec2(0.0, 0.01) + v * since - vec2(0.0, 0.9 * since * since);
                if (at.y > 0.0 && length(p - at) < e * 0.8) {
                    mat = M_DEBRIS;
                    level = 0.8 * (1.0 - since / 1.6);
                    warm = 0.4;
                }
            }
            // A ring of dust running out along the ground.
            float front = 0.55 * pow(since, 0.6);
            float ring = exp(-pow((abs(dc.x) - front) / 0.03, 2.0)) * smoothstep(0.12, 0.0, p.y) * (1.0 - since / 1.6);
            if (mat == M_NONE && hc < ring) {
                mat = M_SMOKE;
                level = 0.5 * ring;
            }
        }

        // Speed lines above the falling sword.
        float lift = swordLift(t);
        if (mat == M_NONE && lift > 0.0 && t > T_FALL && abs(p.x) < 0.014 + 0.02 * hc
            && p.y > lift + 0.83 && p.y < lift + 0.83 + 0.45 * (1.45 - lift)) {
            mat = M_STREAK;
            level = 0.45 * (1.0 - (p.y - lift - 0.83) / 0.6);
        }
    }

    if (mat == M_NONE) {
        // Smoke drifting low and at the sides, and a little floating dust.
        float sm = 0.65 * vnoise(p * vec2(3.5, 5.0) + vec2(t * 0.03, -t * 0.012))
                 + 0.35 * vnoise(p * vec2(9.0, 12.0) + vec2(-t * 0.05, t * 0.02) + 4.1);
        float where = smoothstep(0.55, 0.05, p.y) * (0.45 + 0.55 * smoothstep(0.2, 0.6, abs(p.x - 0.07)));
        float dens = (sm - 0.38) * 2.8 * where;
        float hc = hash(c + 5.1);
        if (hc < dens) {
            mat = M_SMOKE;
            level = 0.16 + 0.3 * sm;
            warm = 0.6 * core * exp(-length(p - GLARE) / 0.35);
        } else if (hc > 0.994) {
            mat = M_SMOKE;
            level = 0.18;
        }
    }

    return vec4(level, float(mat) / 15.0, clamp(warm, 0.0, 1.0), clamp(glow, 0.0, 1.0));
}

// ---- Full-size pass -------------------------------------------------------------------------

vec3 inkFor(int mat) {
    return mat == M_RED ? vec3(0.95, 0.25, 0.16)
         : mat == M_GOLD ? vec3(1.0, 0.78, 0.30)
         : mat == M_HILT ? vec3(0.93, 0.80, 0.55)
         : mat == M_STEEL ? vec3(0.82, 0.88, 0.94)
         : mat == M_DEBRIS ? vec3(0.62, 0.50, 0.42)
         : mat == M_SMOKE ? vec3(0.42, 0.40, 0.40)
         : mat == M_SCAN ? vec3(0.45, 0.95, 1.0)
         : vec3(0.62, 0.84, 1.0);
}

const vec3 HOT = vec3(1.0, 0.50, 0.16);
const vec3 GREEN = vec3(0.30, 1.0, 0.42);

// Pass 1: each pixel is one dot of its cell's glyph.
void main() {
    float t = uTime;
    if (uPass == 0) {
        fragColor = fieldPass(floor(gl_FragCoord.xy), t);
        return;
    }
    vec2 frag = gl_FragCoord.xy;
    vec2 cell = cellSize();
    vec2 c = floor(frag / cell);
    vec2 cuv = fract(frag / cell);

    // Now and then a band of rows slips sideways for a moment: a damaged display.
    float slip = floor(t * 1.7);
    if (t > T_CORE + 2.0 && hash(vec2(slip, 2.0)) > 0.9 && fract(t * 1.7) < 0.12) {
        float band = floor(hash(vec2(slip, 3.0)) * (uResolution.y / cell.y));
        if (abs(c.y - band) < 1.5) c.x = max(c.x - 1.0 - floor(hash(vec2(slip, 4.0)) * 3.0), 0.0);
    }

    vec4 f = texelFetch(uField, ivec2(c), 0);
    float level = f.r;
    int mat = int(f.g * 15.0 + 0.5);
    float warm = f.b;
    float h = hash(c);

    int id = -1;
    vec3 ink = vec3(0.0);
    if (mat == M_SCAN) {
        id = 6 + int(hash(c + floor(t * 20.0)) * 9.0);
        ink = inkFor(mat) * level;
    } else if (mat == M_CORE) {
        id = level > 0.5 ? GL_BLOCK : level > 0.25 ? GL_HASH : GL_COLON;
        ink = mix(HOT, vec3(1.0, 0.88, 0.45), level) * (0.3 + 1.0 * level);
    } else if (mat == M_EYE) {
        id = GL_EQ;
        ink = GREEN * (0.4 + 1.1 * level);
    } else if (mat == M_SPARK) {
        id = level > 0.8 ? GL_EQ : level > 0.3 ? GL_PLUS : GL_DOT;
        ink = mix(vec3(1.0, 0.62, 0.22), vec3(1.0, 0.88, 0.5), level) * (0.45 + 0.9 * level);
    } else if (mat == M_STEEL) {
        id = level > 0.45 ? GL_BARS : level > 0.2 ? GL_BAR : GL_COLON;
        ink = mix(inkFor(mat), HOT, warm) * (0.3 + 0.85 * level);
    } else if (mat == M_STREAK) {
        id = GL_BAR;
        ink = inkFor(mat) * level;
    } else if (mat == M_DEBRIS) {
        int pick = int(h * 6.0);
        id = pick == 0 ? GL_UNDER : pick == 1 ? GL_EQ : pick == 2 ? GL_DASH : pick == 3 ? GL_HASH : pick == 4 ? GL_COMMA : GL_DOT;
        ink = mix(inkFor(mat), HOT, warm) * (0.15 + 0.9 * level);
    } else if (mat == M_SMOKE) {
        id = h < 0.6 ? GL_DOT : h < 0.85 ? GL_COMMA : GL_COLON;
        ink = mix(inkFor(mat), HOT, warm) * level;
    } else if (mat != M_NONE && level > 0.03) {
        // Armour, as in the reference: packed with heavy glyphs (@ 0 8), the colour carrying the
        // brightness; only the dim cells thin out to lighter ones.
        if (level > 0.3) {
            int pick = int(hash(c + 9.0) * 8.0);
            id = pick < 4 ? GL_AT : pick < 6 ? GL_ZERO : pick < 7 ? GL_EIGHT : GL_HASH;
        } else {
            id = int(clamp(floor(level / 0.3 * 7.0 + 5.0 + 2.0 * (h - 0.5)), 4.0, 11.0));
        }
        // Cold armour runs from slate blue in shadow to near white; lit gold pales as it brightens.
        vec3 base = mat == M_WHITE || mat == M_WARM || mat == M_TINT ? mix(vec3(0.34, 0.52, 0.78), vec3(0.80, 0.95, 1.0), level)
                                                    : inkFor(mat);
        vec3 hot = mat == M_GOLD ? mix(vec3(1.0, 0.66, 0.20), vec3(1.0, 0.90, 0.55), level)
                 : mat == M_RED ? vec3(1.0, 0.32, 0.14) : mat == M_WARM ? vec3(1.0, 0.42, 0.16)
                 : mat == M_TINT ? mix(vec3(0.95, 0.62, 0.45), vec3(1.0, 0.90, 0.78), level) : HOT;
        ink = mix(base, hot, warm * (mat == M_WARM ? 0.85 : 1.0)) * (0.16 + 0.95 * level);
    }

    vec3 col = mix(uColorBottom.rgb, uColorTop.rgb, frag.y / uResolution.y) * 0.08;
    float on = glyph(cuv, id);
    col += on * ink;

    // The core's light: a bloom of the glowing cells (five bilinear taps, green where the eyes
    // are), and a smooth glow round the core so it reads as light and not only as type.
    vec2 size = vec2(textureSize(uField, 0));
    vec2 st = frag / cell / size;
    vec2 o = 0.9 / size;
    vec4 b0 = texture(uField, st), b1 = texture(uField, st + o), b2 = texture(uField, st - o),
         b3 = texture(uField, st + vec2(o.x, -o.y)), b4 = texture(uField, st + vec2(-o.x, o.y));
    float bloomA = 0.4 * b0.a + 0.15 * (b1.a + b2.a + b3.a + b4.a);
    float bloomW = 0.4 * b0.a * b0.b + 0.15 * (b1.a * b1.b + b2.a * b2.b + b3.a * b3.b + b4.a * b4.b);
    vec3 bloomCol = mix(GREEN, HOT, bloomW / max(bloomA, 1e-4));
    col += bloomCol * bloomA * 0.55 * (1.0 - 0.4 * on);

    // A tight glow on the core itself and a wide flare over the torn arm, stretched sideways.
    vec2 p = toFigure(frag);
    float core = coreLevel(t);
    vec2 dc = (p - CORE) * vec2(1.0, 1.3);
    vec2 dg = (p - GLARE) * vec2(0.7, 1.5);
    col += HOT * core * CORE_BLOOM * (0.0007 / (dot(dc, dc) + 0.0012) + 0.0004 / (dot(dg, dg) + 0.0016)
                                      + 0.10 * exp(-length(dg) / 0.13));
    float eye = eyeLevel(t);
    vec2 de = (p - (vec2(EYE_CELL - ivec2(SWORD_COL, GROUND_ROW)) + vec2(2.5, 0.5)) * E) * vec2(0.6, 1.4);
    col += GREEN * eye * 0.00002 / (dot(de, de) + 0.00012);

    // Scanlines and the tube's vignette.
    col *= 0.93 + 0.07 * cos(frag.y * TAU / 3.0);
    vec2 vg = frag / uResolution - 0.5;
    col *= 1.0 - dot(vg, vg) * 0.7;

    // Soft shoulder, so the core rolls off to gold instead of clipping to a white blob.
    vec3 over = max(col - 0.65, 0.0);
    col = min(col, 0.65) + 0.32 * (1.0 - exp(-over / 0.32));
    col += (hash(frag + fract(t)) - 0.5) / 255.0;
    fragColor = vec4(col, 1.0);
}
