// docs/30 §5: the front row as a soft line in the layer; the warp shrinks it toward the horizon as a glow trail.
vec2 wfPoint(int i, float b) {
  vec2 hr = halfRes();
  float x = (float(i) / float(BANDS - 1) * 2.0 - 1.0) * hr.x * 0.9;
  return vec2(x, -0.85 * hr.y + b * hr.y * 0.5);
}

void main() {
  int c = gl_VertexID;
  int s = gl_InstanceID;
  float b0 = band(0, s);
  float b1 = band(0, s + 1);
  float bm = max(b0, b1);
  float t = bm > 0.0 ? 0.3 + 0.7 * bm : 0.0;
  Vert v = lineQuad(wfPoint(s, b0), wfPoint(s + 1, b1), 1.6, 0.6, c);
  if (t <= 0.0) v.pos = vec2(0.0);
  emit(v, t, 0.5 * float(s) / float(BANDS));
}
