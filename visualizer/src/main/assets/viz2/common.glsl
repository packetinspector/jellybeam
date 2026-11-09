// docs/30 §6: prepended (after "#version 300 es") to every visualizer shader. The Frame block is bound
// to binding 0 by the renderer (ES 3.00 has no binding qualifier). Pixels are in the current target
// (the low-res layer or the screen, per u_res), origin at its centre, y up.
precision highp float;
precision highp int;

layout(std140) uniform Frame {
  vec4 u_res;      // target width px, height px, 1/width, 1/height
  vec4 u_audio;    // level, bass, mid, treble (0..1)
  vec4 u_time;     // audio time s, kick pulse (flash-limited), travel, dt s
  vec4 u_beats[2]; // 8 kick ages in s, newest first (1e6 = none)
  vec4 u_param[2]; // pattern-specific, from PatternParams
  vec4 u_warp[3];  // zoom, rotate, swirl, decay | tx, ty, rippleAmp, rippleFreq | cx, cy, jitter, gShift
  vec4 u_drum;     // kick pulse (flash-limited), snare pulse, hat pulse, flux
  vec4 u_groove;   // beat phase, tempo/200, loud short, loud long
  vec4 u_pal;      // cycle offset, gain, family A, draw weight
  vec4 u_comp;     // composite mode, mode param, family B, family B mix
  ivec4 u_info;    // headRow, validRows, drawIndex, phrase
};

uniform highp sampler2D u_hist;
uniform highp sampler2D u_wave;
uniform mediump sampler2D u_lut;
uniform mediump sampler2D u_layer;
uniform highp sampler2D u_sim;

const int BANDS = 64;
const int HIST_ROWS = 512;
const float PI = 3.14159265;
const float TAU = 6.28318531;
const float STROKE_HW = 1.25;
const float LUT_LEVELS = 32.0;
const float LUT_FAMILIES = 5.0;

// Band i of the history row k frames old (k < HIST_ROWS); rows past validRows are zero in the texture.
float band(int k, int i) {
  int row = (u_info.x - k + HIST_ROWS) % HIST_ROWS;
  return clamp(texelFetch(u_hist, ivec2(clamp(i, 0, BANDS - 1), row), 0).r, 0.0, 1.0);
}

// Sample i (oldest first) of wave row 0 mono, 1 left, 2 right.
float wave(int row, int i) {
  return clamp(texelFetch(u_wave, ivec2(clamp(i, 0, 511), row), 0).r, -1.0, 1.0);
}

float peak(int i) {
  return clamp(texelFetch(u_wave, ivec2(clamp(i, 0, BANDS - 1), 3), 0).r, 0.0, 1.0);
}

// Kick slot n (0..7) by constant components only, so no dynamic vector indexing is needed.
float beatAge(int n) {
  vec4 q = n < 4 ? u_beats[0] : u_beats[1];
  int m = n % 4;
  return m == 0 ? q.x : (m == 1 ? q.y : (m == 2 ? q.z : q.w));
}

vec2 halfRes() { return u_res.xy * 0.5; }
float minDim() { return min(u_res.x, u_res.y); }
vec2 polar(float r, float a) { return vec2(cos(a), sin(a)) * r; }
float energy(float b0, float b1) { return clamp(max(b0, b1) * 1.5, 0.0, 1.0); }
float loud() { return clamp(u_audio.x * 2.5, 0.0, 1.0); }

int mirrored(int j) {
  int jj = j % (2 * BANDS);
  return jj < BANDS ? jj : 2 * BANDS - 1 - jj;
}

// lowbias32 integer hash: exact on every GPU and in the CPU mirror.
uint hashU(uint x) {
  x ^= x >> 16;
  x *= 0x7feb352du;
  x ^= x >> 15;
  x *= 0x846ca68bu;
  x ^= x >> 16;
  return x;
}

float hash01(uint x) { return float(hashU(x) >> 8) * (1.0 / 16777216.0); }

// docs/30 §4: palette colour at cyclic position p and intensity level 0..1 of family row f.
vec3 lutColor(float f, float p, float level) {
  float y = (f * LUT_LEVELS + 0.5 + clamp(level, 0.0, 1.0) * (LUT_LEVELS - 1.0)) / (LUT_LEVELS * LUT_FAMILIES);
  return texture(u_lut, vec2(fract(p), y)).rgb;
}

struct Vert {
  vec2 pos;
  float d;
  float hw;
};

// Corner c (0..5, two triangles) of the quad around p0->p1: half-width hw plus a 1 px AA fringe,
// extended ext px past each end; d is the signed pixel distance from the centre line.
Vert lineQuad(vec2 p0, vec2 p1, float hw, float ext, int c) {
  vec2 dv = p1 - p0;
  float len = length(dv);
  vec2 dir = len > 0.0001 ? dv / len : vec2(1.0, 0.0);
  vec2 nrm = vec2(-dir.y, dir.x);
  bool atEnd = c == 1 || c == 2 || c == 4;
  float side = (c == 2 || c == 4 || c == 5) ? 1.0 : -1.0;
  float w = hw + 1.0;
  vec2 base = atEnd ? p1 + dir * ext : p0 - dir * ext;
  Vert v;
  v.pos = base + nrm * (side * w);
  v.d = side * w;
  v.hw = hw;
  return v;
}

vec4 clipPos(vec2 px) { return vec4(px * 2.0 * u_res.zw, 0.0, 1.0); }

// docs/30 §6: the 6-fold mirror the composite applies in kaleidoscope mode (the stats pass measures it too).
vec2 kaleido(vec2 uv) {
  float aspect = u_res.x * u_res.w;
  vec2 p = (uv - 0.5) * vec2(aspect, 1.0);
  float r = length(p);
  float wedge = PI / 3.0;
  float a = atan(p.y, p.x) + u_comp.y;
  a = mod(a, wedge);
  a = a > wedge * 0.5 ? wedge - a : a;
  return vec2(0.5) + vec2(cos(a), sin(a)) * r / vec2(aspect, 1.0);
}
