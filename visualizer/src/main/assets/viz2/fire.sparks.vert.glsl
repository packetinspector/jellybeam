// docs/30 §5: 6 sparks per recent kick leap from the flames and fall back, cooling over LIFE s; seeded by
// the kick's birth time so each keeps its path.
const int PER_KICK = 6;
const float LIFE = 1.0;
const float MARGIN = 0.04;

void main() {
  int c = gl_VertexID;
  int j = gl_InstanceID;
  int m = j % PER_KICK;
  float age = beatAge(j / PER_KICK);
  uint seed = uint(floor(max(u_time.x - age, 0.0) * 30.0 + 0.5)) * 8u + uint(m);
  vec2 hr = halfRes();
  float x = -hr.x + u_res.x * (MARGIN + (1.0 - 2.0 * MARGIN) * hash01(seed));
  float vy = u_res.y * (0.55 + 0.5 * hash01(seed ^ 0x5bd1e995u));
  float vx = u_res.y * 0.12 * (hash01(seed ^ 0x27d4eb2fu) - 0.5);
  float a = min(age, LIFE);
  vec2 pos = vec2(x + vx * a, -hr.y + u_res.y * 0.12 + vy * a - 0.45 * u_res.y * a * a);
  Vert v = lineQuad(pos, pos, 0.9, 0.9, c);
  float t = age < LIFE ? 1.0 - age / LIFE : 0.0;
  if (t <= 0.0) v.pos = vec2(0.0);
  emit(v, t, 0.1 * hash01(seed ^ 0x165667b1u));
}
