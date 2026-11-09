// docs/30 §5: band i burns at x = left margin + (i + 0.5) / 64 of the usable width, height and heat by level.
const float MARGIN = 0.04;

void main() {
  int c = gl_VertexID;
  int i = gl_InstanceID;
  vec2 hr = halfRes();
  float slot = u_res.x * (1.0 - 2.0 * MARGIN) / float(BANDS);
  float x = -hr.x + u_res.x * MARGIN + slot * (float(i) + 0.5);
  float b = band(0, i);
  float y0 = -hr.y;
  Vert v = lineQuad(vec2(x, y0), vec2(x, y0 + 2.0 + b * u_res.y * 0.30), slot * 0.5, 0.0, c);
  float t = b > 0.0 ? clamp(0.3 + 0.7 * b + 0.15 * u_drum.y, 0.0, 1.0) : 0.0;
  if (t <= 0.0) v.pos = vec2(0.0);
  emit(v, t, 0.06 * float(i) / float(BANDS));
}
