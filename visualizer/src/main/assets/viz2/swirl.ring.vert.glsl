// docs/30 §5: the waveform as a kick-pulsed ring, the palette once round it; soft in the layer, crisp as the overlay.
const int RING = 128;

vec2 ringPoint(int j) {
  float w = wave(0, (j % RING) * 4);
  float r = minDim() * (0.17 + 0.10 * w) * (1.0 + 0.25 * u_drum.x);
  return polar(r, TAU * float(j) / float(RING));
}

void main() {
  int c = gl_VertexID;
  int j = gl_InstanceID;
  float w = wave(0, j * 4);
  float t = clamp(loud() * 1.5, 0.0, 1.0) * (0.6 + 0.4 * abs(w));
  Vert v = lineQuad(ringPoint(j), ringPoint(j + 1), max(1.0, minDim() / 720.0), 0.5, c);
  if (t <= 0.0) v.pos = vec2(0.0);
  emit(v, t, float(j) / float(RING));
}
