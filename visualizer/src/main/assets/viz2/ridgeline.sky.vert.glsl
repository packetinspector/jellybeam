// docs/30 §5: the newest spectrum along the farthest ridge, in the layer; the warp lifts it into an aurora
// that shows only above the ridges, since their fills cover the layer below.
const int ROWS = 64;
const int SEGS = 63;

vec2 skyPoint(int i) {
  vec2 hr = halfRes();
  float z = 1.0 + float(ROWS - 1) * 0.06;
  float yh = hr.y * 0.55;
  float xn = float(i) / float(SEGS);
  float x = (xn * 2.0 - 1.0) * hr.x * 0.95 * (0.55 + 0.45 / z);
  float taper = 0.35 + 0.65 * sin(PI * xn);
  return vec2(x, yh + (-0.9 * hr.y - yh) / z + band(0, i) * hr.y * 0.55 * taper / z);
}

void main() {
  int c = gl_VertexID;
  int s = gl_InstanceID;
  float bm = max(band(0, s), band(0, s + 1));
  float t = bm > 0.0 ? 0.3 + 0.7 * bm : 0.0;
  Vert v = lineQuad(skyPoint(s), skyPoint(s + 1), 1.4, 0.6, c);
  if (t <= 0.0) v.pos = vec2(0.0);
  emit(v, t, 0.5 + 0.3 * float(s) / float(SEGS));
}
