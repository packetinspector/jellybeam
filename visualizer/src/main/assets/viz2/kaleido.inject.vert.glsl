// docs/30 §5: a waveform ring plus band strokes in the 0–30° wedge the 6-fold composite mirrors; the warp
// turns and zooms them into a moving mandala.
const int RING = 128;

void main() {
  int c = gl_VertexID;
  int j = gl_InstanceID;
  Vert v;
  float t;
  float p;
  if (j < RING) {
    float a0 = TAU * float(j) / float(RING);
    float a1 = TAU * float(j + 1) / float(RING);
    float w0 = wave(0, j * 4);
    float w1 = wave(0, ((j + 1) % RING) * 4);
    v = lineQuad(polar(minDim() * (0.22 + 0.12 * w0), a0), polar(minDim() * (0.22 + 0.12 * w1), a1), 1.0, 0.5, c);
    t = clamp(loud() * 1.5, 0.0, 1.0) * (0.55 + 0.45 * abs(w0));
    p = float(j) / float(RING);
  } else {
    int i = j - RING;
    float b = band(0, i);
    float r = minDim() * (0.06 + 0.42 * float(i) / float(BANDS));
    float a = PI / 12.0 + (b - 0.5) * 0.35;
    v = lineQuad(polar(r, a), polar(r + minDim() * (0.01 + 0.05 * b), a), 0.9, 0.0, c);
    t = b > 0.0 ? 0.4 + 0.6 * b : 0.0;
    p = float(i) / float(BANDS) + 0.33;
  }
  if (t <= 0.0) v.pos = vec2(0.0);
  emit(v, t, p);
}
