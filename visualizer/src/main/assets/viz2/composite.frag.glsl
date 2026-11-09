// docs/30 §6 (E1): the layer upscaled bilinearly and coloured through the palette; mode 1 folds the
// picture into a 6-fold mirror (kaleidoscope); a crossfade mixes two palette families.
in vec2 v_uv;
out vec4 o_color;

void main() {
  vec2 uv = u_comp.x > 0.5 ? kaleido(v_uv) : v_uv;
  vec2 s = texture(u_layer, uv).rg;
  float level = s.r * u_pal.y;
  vec3 a = lutColor(u_pal.z, s.g + u_pal.x, level);
  vec3 b = lutColor(u_comp.z, s.g + u_pal.x, level);
  o_color = vec4(mix(a, b, u_comp.w), 1.0);
}
