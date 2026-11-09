// docs/30 §5: 128 spokes (64 bands mirrored) round a kick-pulsed ring; the swirl warp curls their trails.
const int SPOKES = 128;

void main() {
  int c = gl_VertexID;
  int j = gl_InstanceID;
  float b = band(0, mirrored(j));
  float a = TAU * (float(j) + 0.5) / float(SPOKES);
  float r0 = minDim() * (0.16 + 0.03 * u_drum.x);
  float r1 = r0 + b * minDim() * 0.30;
  float hw = max(r0 * PI / float(SPOKES) * 0.7, 0.75);
  Vert v = lineQuad(polar(r0, a), polar(r1, a), hw, 0.0, c);
  float t = b > 0.0 ? clamp(0.35 + 0.55 * b + 0.15 * u_drum.y, 0.0, 1.0) : 0.0;
  if (t <= 0.0) v.pos = vec2(0.0);
  emit(v, t, float(j) / float(SPOKES));
}
