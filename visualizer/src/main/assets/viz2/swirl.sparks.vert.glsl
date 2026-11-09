// docs/30 §5: 8 sparks per recent kick fly out from the ring and fade over LIFE s. A spark's seed is its
// kick's birth time, so it keeps its path while newer kicks shift the age slots.
const int PER_KICK = 8;
const float LIFE = 1.2;

void main() {
  int c = gl_VertexID;
  int j = gl_InstanceID;
  int m = j % PER_KICK;
  float age = beatAge(j / PER_KICK);
  uint seed = uint(floor(max(u_time.x - age, 0.0) * 30.0 + 0.5)) * 8u + uint(m);
  float a = TAU * hash01(seed);
  float speed = minDim() * (0.25 + 0.45 * hash01(seed ^ 0x5bd1e995u));
  float r = minDim() * 0.17 + speed * age * (1.0 - 0.3 * min(age, LIFE));
  vec2 pos = polar(r, a);
  Vert v = lineQuad(pos, pos, 1.1, 1.1, c);
  float t = age < LIFE ? 0.95 * (1.0 - age / LIFE) : 0.0;
  if (t <= 0.0) v.pos = vec2(0.0);
  emit(v, t, hash01(seed ^ 0x27d4eb2fu));
}
