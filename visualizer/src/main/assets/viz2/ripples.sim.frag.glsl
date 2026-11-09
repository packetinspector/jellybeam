// docs/30 §5: one damped wave-equation step on the 120×68 grid. A cell packs h (RG) and last frame's h
// (BA) as 16-bit values, 32768 = 0, 1/SCALE per step. C2 ≤ 0.5 keeps the scheme stable (CFL); DAMP drains
// it. u_param[0], u_param[1] = drops (cell x, cell y, height, radius cells); a redraw (dt 0) holds the grid.
in vec2 v_uv;
out vec4 o_color;

const int SIM_W = 120;
const int SIM_H = 68;
const float SCALE = 2048.0;
const float C2 = 0.45;
const float DAMP = 0.97;

float dec(vec2 hl) {
  return (floor(hl.x * 255.0 + 0.5) * 256.0 + floor(hl.y * 255.0 + 0.5) - 32768.0) / SCALE;
}

vec2 enc(float h) {
  float q = clamp(floor(h * SCALE + 0.5) + 32768.0, 0.0, 65535.0);
  float hi = floor(q / 256.0);
  return vec2(hi, q - hi * 256.0) / 255.0;
}

float hAt(ivec2 c) {
  return dec(texelFetch(u_sim, clamp(c, ivec2(0), ivec2(SIM_W - 1, SIM_H - 1)), 0).rg);
}

float drop(vec4 d, ivec2 c) {
  if (d.z <= 0.0) return 0.0;
  vec2 q = (vec2(c) + 0.5 - d.xy) / d.w;
  return d.z * exp(-dot(q, q));
}

void main() {
  ivec2 c = ivec2(gl_FragCoord.xy);
  vec4 s = texelFetch(u_sim, c, 0);
  if (u_time.w <= 0.0) {
    o_color = s;
    return;
  }
  float h = dec(s.rg);
  float hp = dec(s.ba);
  float lap = hAt(c + ivec2(1, 0)) + hAt(c - ivec2(1, 0)) + hAt(c + ivec2(0, 1)) + hAt(c - ivec2(0, 1)) - 4.0 * h;
  float hn = (2.0 * h - hp + C2 * lap) * DAMP + drop(u_param[0], c) + drop(u_param[1], c);
  o_color = vec4(enc(hn), enc(h));
}
