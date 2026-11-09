// docs/30 §5: the ripple grid shaded by slope: steep faces bright, the palette turning with the slope's
// direction so rings carry a colour wheel; calm water is black (level 0), and quiet music dims it.
in vec2 v_uv;
out vec4 o_color;

const int SIM_W = 120;
const int SIM_H = 68;
const float SCALE = 2048.0;
const float GAIN = 3.0;

float hAt(int x, int y) {
  vec2 hl = texelFetch(u_sim, clamp(ivec2(x, y), ivec2(0), ivec2(SIM_W - 1, SIM_H - 1)), 0).rg;
  return (floor(hl.x * 255.0 + 0.5) * 256.0 + floor(hl.y * 255.0 + 0.5) - 32768.0) / SCALE;
}

// Bilinear by hand: filtering the packed bytes would mix high and low halves.
float hBil(vec2 g) {
  vec2 f0 = floor(g);
  vec2 fr = g - f0;
  int x = int(f0.x);
  int y = int(f0.y);
  return mix(mix(hAt(x, y), hAt(x + 1, y), fr.x), mix(hAt(x, y + 1), hAt(x + 1, y + 1), fr.x), fr.y);
}

void main() {
  vec2 g = v_uv * vec2(float(SIM_W), float(SIM_H)) - 0.5;
  float h = hBil(g);
  vec2 grad = vec2(hBil(g + vec2(1.0, 0.0)) - hBil(g - vec2(1.0, 0.0)), hBil(g + vec2(0.0, 1.0)) - hBil(g - vec2(0.0, 1.0))) * 0.5;
  float slope = length(grad);
  float env = clamp(u_groove.z * 4.0 + u_groove.w * 1.5, 0.0, 1.0);
  float r = clamp(slope * GAIN - 0.03 + clamp(h * 0.25, 0.0, 0.3), 0.0, 1.0) * env;
  float p = slope > 1e-4 ? atan(grad.y, grad.x) / TAU + 0.12 * h : 0.12 * h;
  o_color = vec4(r * u_pal.w, fract(p + 2.0), 0.0, 1.0);
}
