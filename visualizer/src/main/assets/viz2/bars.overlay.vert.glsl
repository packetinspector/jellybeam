// docs/30 §5: crisp bars and 4 px peak caps at screen resolution over their trails.
void main() {
  int c = gl_VertexID;
  int i = gl_InstanceID % BANDS;
  vec2 hr = halfRes();
  float slot = hr.x * 1.68 / float(BANDS);
  float x0 = -hr.x * 0.84 + slot * float(i);
  float xl = floor(x0 + slot * 0.2 + 0.5);
  float xr = floor(x0 + slot * 0.8 + 0.5);
  float base = -hr.y * 0.84;
  float maxH = hr.y * 1.68;
  float b = band(0, i);
  float h = max(b * maxH, 2.0);
  float p = float(i) / float(BANDS);
  Vert v;
  float t;
  if (gl_InstanceID >= BANDS) {
    float pk = peak(i);
    float y = base + max(pk * maxH, h) + 6.0;
    v = lineQuad(vec2(xl, y), vec2(xr, y), 2.0, 0.0, c);
    t = pk > 0.0 ? 1.0 : 0.0;
  } else {
    float yc = base + h * 0.5;
    v = lineQuad(vec2(xl, yc), vec2(xr, yc), h * 0.5, 0.0, c);
    t = b > 0.0 ? 0.3 + 0.6 * b : 0.0;
  }
  if (t <= 0.0) v.pos = vec2(0.0);
  emit(v, t, p);
}
