// docs/30 §5: 64 history rows as stacked perspective ridges with hidden lines removed. Rows draw farthest
// first; each row paints its fills (level 0 black, opaque, from the line down to its baseline) before its line, so
// a nearer row hides every farther line below its own.
const int ROWS = 64;
const int SEGS = 63;
const int ROW_STEP = 2;
const float FILL_DROP = 2.0;

float rowZ(int k) { return 1.0 + float(k) * 0.06; }

float baseY(int k) {
  float yh = halfRes().y * 0.55;
  return yh + (-0.9 * halfRes().y - yh) / rowZ(k);
}

vec2 ridgePoint(int k, int i) {
  vec2 hr = halfRes();
  float z = rowZ(k);
  float xn = float(i) / float(SEGS);
  float x = (xn * 2.0 - 1.0) * hr.x * 0.95 * (0.55 + 0.45 / z);
  float taper = 0.35 + 0.65 * sin(PI * xn);
  return vec2(x, baseY(k) + band(k * ROW_STEP, i) * hr.y * 0.55 * taper / z);
}

void main() {
  int c = gl_VertexID;
  int j = gl_InstanceID;
  int k = ROWS - 1 - j / (2 * SEGS);
  int rem = j % (2 * SEGS);
  int s = rem % SEGS;
  vec2 p0 = ridgePoint(k, s);
  vec2 p1 = ridgePoint(k, s + 1);
  if (rem < SEGS) {
    float yb = baseY(k) - FILL_DROP;
    Vert f;
    f.pos = (c == 0 || c == 3) ? vec2(p0.x, yb) : (c == 1 ? vec2(p1.x, yb) : ((c == 2 || c == 4) ? p1 : p0));
    f.d = 0.0;
    f.hw = 4.0;
    emit(f, 0.0, 0.0);
  } else {
    float bm = max(band(k * ROW_STEP, s), band(k * ROW_STEP, s + 1));
    float t = bm > 0.0 ? (0.35 + 0.65 * bm) * (1.0 - 0.55 * float(k) / float(ROWS - 1)) : 0.0;
    Vert v = lineQuad(p0, p1, 1.1, 0.6, c);
    if (t <= 0.0) v.pos = vec2(0.0);
    emit(v, t, 0.55 * float(k) / float(ROWS) + 0.3 * float(s) / float(SEGS));
  }
}
