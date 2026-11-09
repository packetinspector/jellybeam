// docs/30 §6 (E1): the crisp native overlay; interior pixels are exact palette colours, edges blend by coverage.
in float v_t;
in float v_d;
in float v_hw;
in float v_p;
out vec4 o_color;

void main() {
  float cov = clamp(v_hw - abs(v_d) + 0.5, 0.0, 1.0);
  vec3 c = lutColor(u_pal.z, v_p + u_pal.x, v_t * u_pal.y);
  o_color = vec4(c, cov * u_pal.w);
}
