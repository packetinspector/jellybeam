// docs/30 §6 (E1): shapes drawn into the feedback layer as (intensity, palette position); MAX blending.
in float v_t;
in float v_d;
in float v_hw;
in float v_p;
out vec4 o_color;

void main() {
  float cov = clamp(v_hw - abs(v_d) + 0.5, 0.0, 1.0);
  float i = v_t * cov * u_pal.w;
  // MAX blends the palette position too: a fringe too dim to show must not recolour what lies under it.
  if (i < 0.5 / 255.0) discard;
  o_color = vec4(i, fract(v_p), 0.0, 1.0);
}
