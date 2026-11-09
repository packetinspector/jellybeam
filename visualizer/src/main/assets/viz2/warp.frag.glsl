// docs/30 §6 (E1): last frame at the warped position, intensity decayed, palette position drifted. The
// small subtractive term keeps 8-bit rounding from stalling the fade just above black (identity at decay 1).
in vec2 v_uv;
out vec4 o_color;

void main() {
  bool inside = all(greaterThanEqual(v_uv, vec2(0.0))) && all(lessThanEqual(v_uv, vec2(1.0)));
  vec2 s = inside ? texture(u_layer, v_uv).rg : vec2(0.0);
  float d = u_warp[0].w;
  o_color = vec4(max(s.r * d - (1.0 - d) * 0.1, 0.0), fract(s.g + u_warp[2].w), 0.0, 1.0);
}
