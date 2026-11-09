// docs/30 §5: the beam. u_param[0].x 0 = XY (x = right, y = left, so a left-only signal is vertical),
// 1 = oscilloscope; a fast beam is dim, as on a CRT. Soft in the layer (afterglow), crisp as the overlay.
const int SEGS = 511;

vec2 beamPoint(int s, float mode) {
  vec2 hr = halfRes();
  if (mode < 0.5) return vec2(wave(2, s), wave(1, s)) * minDim() * 0.42;
  return vec2(-hr.x * 0.9 + hr.x * 1.8 * float(s) / float(SEGS), wave(0, s) * hr.y * 0.6);
}

void main() {
  int c = gl_VertexID;
  int s = gl_InstanceID;
  float mode = u_param[0].x;
  vec2 p0 = beamPoint(s, mode);
  vec2 p1 = beamPoint(s + 1, mode);
  float speed = length(p1 - p0) / minDim() * 270.0;
  float t = clamp(loud() * 2.0, 0.0, 1.0) * clamp(1.6 / (1.0 + speed * 0.25), 0.15, 1.0);
  Vert v = lineQuad(p0, p1, 0.9, 0.5, c);
  if (t <= 0.0) v.pos = vec2(0.0);
  emit(v, t, 0.35 * float(s) / float(SEGS) + 0.5 * mode);
}
