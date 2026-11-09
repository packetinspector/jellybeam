// docs/30 §5: crisp tangential caps at each spoke's peak, at screen resolution.
const int SPOKES = 128;

void main() {
  int c = gl_VertexID;
  int j = gl_InstanceID;
  int i = mirrored(j);
  float b = band(0, i);
  float pk = max(peak(i), b);
  float a = TAU * (float(j) + 0.5) / float(SPOKES);
  float r0 = minDim() * (0.16 + 0.03 * u_drum.x);
  float r = r0 + pk * minDim() * 0.30 + minDim() * 0.012;
  vec2 tang = vec2(-sin(a), cos(a)) * (r * PI / float(SPOKES) * 0.6);
  vec2 m = polar(r, a);
  Vert v = lineQuad(m - tang, m + tang, 1.5, 0.0, c);
  float t = pk > 0.0 ? 1.0 : 0.0;
  if (t <= 0.0) v.pos = vec2(0.0);
  emit(v, t, float(j) / float(SPOKES));
}
