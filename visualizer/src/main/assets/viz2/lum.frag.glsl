// docs/30 §4: per 32×18 cell, the mean luminance the composite would show at eight gains (GainCurve.GAINS),
// square-root coded so dark frames keep precision; the CPU averages the cells and picks the gain.
in vec2 v_uv;
layout(location = 0) out vec4 o_low;
layout(location = 1) out vec4 o_high;

const float GAINS[8] = float[8](0.0, 0.25, 0.5, 0.75, 1.0, 1.5, 2.5, 4.0);

float linear(float c) {
  return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4);
}

float lumAt(vec2 s, float level) {
  vec3 c = lutColor(u_pal.z, s.g + u_pal.x, level);
  if (u_comp.w > 0.0) c = mix(c, lutColor(u_comp.z, s.g + u_pal.x, level), u_comp.w);
  return 0.2126 * linear(c.r) + 0.7152 * linear(c.g) + 0.0722 * linear(c.b);
}

void main() {
  // Each cell covers 15×15 layer texels; 4×4 bilinear taps on texel corners spread across it.
  vec2 texel = 1.0 / vec2(textureSize(u_layer, 0));
  vec2 cell = floor(gl_FragCoord.xy) * 15.0 + 2.0;
  float sum[8] = float[8](0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
  for (int j = 0; j < 4; j++) {
    for (int i = 0; i < 4; i++) {
      vec2 uv = (cell + vec2(float(i), float(j)) * 4.0) * texel;
      vec2 s = texture(u_layer, u_comp.x > 0.5 ? kaleido(uv) : uv).rg;
      for (int k = 0; k < 8; k++) sum[k] += lumAt(s, s.r * GAINS[k]);
    }
  }
  o_low = sqrt(vec4(sum[0], sum[1], sum[2], sum[3]) / 16.0);
  o_high = sqrt(vec4(sum[4], sum[5], sum[6], sum[7]) / 16.0);
}
