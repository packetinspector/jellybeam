// docs/30 §5: 1024 points on a sphere, torus or double helix (u_param[0].x), radius by spectrum, turned by
// u_time.z; soft dots in the layer leave trails, crisp dots in the overlay.
const int POINTS = 1024;
const float PERSP = 0.22;
const float SCALE = 0.32;

vec3 shapePoint(int i, int shape) {
  float fi = float(i);
  if (shape == 0) {
    float y = 1.0 - 2.0 * (fi + 0.5) / float(POINTS);
    float rr = sqrt(max(0.0, 1.0 - y * y));
    float ph = fi * 2.39996323;
    return vec3(rr * cos(ph), y, rr * sin(ph));
  }
  if (shape == 1) {
    float al = TAU * float(i % 32) / 32.0;
    float be = TAU * float(i / 32) / 32.0;
    float ring = 0.68 + 0.32 * cos(al);
    return vec3(ring * cos(be), 0.32 * sin(al), ring * sin(be));
  }
  float tt = float(i / 2) / float(POINTS / 2 - 1);
  float a = tt * TAU * 3.0 + float(i % 2) * PI;
  return vec3(0.55 * cos(a), tt * 1.8 - 0.9, 0.55 * sin(a));
}

void main() {
  int c = gl_VertexID;
  int i = gl_InstanceID;
  float v01 = (float(i) + 0.5) / float(POINTS);
  float b = band(0, int(v01 * float(BANDS)));
  vec3 q = shapePoint(i, int(u_param[0].x + 0.5)) * ((0.7 + 0.3 * b) * (1.0 + 0.1 * u_drum.x));
  float yaw = u_time.z;
  float pitch = 0.35 + 0.45 * sin(u_time.z * 0.37);
  float x1 = q.x * cos(yaw) - q.z * sin(yaw);
  float z1 = q.x * sin(yaw) + q.z * cos(yaw);
  float y2 = q.y * cos(pitch) - z1 * sin(pitch);
  float z2 = q.y * sin(pitch) + z1 * cos(pitch);
  vec2 pos = vec2(x1, y2) * (1.0 / (1.0 - z2 * PERSP)) * minDim() * SCALE;
  float t = clamp(u_groove.z * 3.0, 0.0, 1.0) * (0.35 + 0.65 * b) * (0.5 + 0.5 * clamp((z2 + 1.1) / 2.2, 0.0, 1.0));
  float hw = max(0.9, minDim() / 600.0);
  Vert v = lineQuad(pos, pos, hw, hw, c);
  if (t <= 0.0) v.pos = vec2(0.0);
  emit(v, t, v01 * 0.6 + 0.2 * z2);
}
