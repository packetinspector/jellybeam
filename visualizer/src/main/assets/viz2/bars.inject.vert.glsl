// docs/30 §5: the bars drawn into the layer so they leave rising, fading trails; colour walks across the bars.
void main() {
  int c = gl_VertexID;
  int i = gl_InstanceID;
  vec2 hr = halfRes();
  float slot = hr.x * 1.68 / float(BANDS);
  float x0 = -hr.x * 0.84 + slot * float(i);
  float xl = x0 + slot * 0.2;
  float xr = x0 + slot * 0.8;
  float base = -hr.y * 0.84;
  float b = band(0, i);
  float h = b * hr.y * 1.68;
  float yc = base + h * 0.5;
  float t = b > 0.0 ? 0.45 + 0.4 * b + 0.15 * u_drum.y : 0.0;
  Vert v = lineQuad(vec2(xl, yc), vec2(xr, yc), max(h * 0.5, 0.5), 0.0, c);
  // docs/30 §6: an unlit shape collapses to a point, so MAX blending never writes its palette position.
  if (t <= 0.0) v.pos = vec2(0.0);
  emit(v, t, float(i) / float(BANDS));
}
