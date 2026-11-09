// docs/30 §5: 40 history rows in perspective, farthest first so nearer rows draw over them.
const int ROWS = 40;
const int ROW_STEP = 3;

vec2 wfPoint(int k, int i, float b) {
  vec2 hr = halfRes();
  float z = 1.0 + float(k) * 0.09;
  float yh = hr.y * 0.55;
  float x = (float(i) / float(BANDS - 1) * 2.0 - 1.0) * hr.x * 0.9 / z;
  float y = yh + (-0.85 * hr.y - yh) / z + b * hr.y * 0.5 / z;
  return vec2(x, y);
}

void main() {
  int c = gl_VertexID;
  int j = gl_InstanceID;
  int k = ROWS - 1 - j / (BANDS - 1);
  int s = j % (BANDS - 1);
  float b0 = band(k * ROW_STEP, s);
  float b1 = band(k * ROW_STEP, s + 1);
  float bm = max(b0, b1);
  float t = bm > 0.0 ? (0.3 + 0.7 * bm) * (1.0 - 0.75 * float(k) / float(ROWS)) : 0.0;
  Vert v = lineQuad(wfPoint(k, s, b0), wfPoint(k, s + 1, b1), 1.1, 0.6, c);
  if (t <= 0.0) v.pos = vec2(0.0);
  emit(v, t, 0.5 * float(s) / float(BANDS) + float(k) * 0.01);
}
