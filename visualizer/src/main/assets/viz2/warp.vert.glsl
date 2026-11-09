// docs/30 §6 (E1): a 32×24 grid over the layer; each vertex looks up where its content came from last
// frame (zoom, rotate, swirl, translate, ripple, jitter about the centre), so motion is per vertex, not per pixel.
out vec2 v_uv;

const int GX = 32;
const int GY = 24;

void main() {
  int cell = gl_InstanceID;
  int c = gl_VertexID;
  int cx = cell % GX + ((c == 1 || c == 2 || c == 4) ? 1 : 0);
  int cy = cell / GX + ((c == 2 || c == 4 || c == 5) ? 1 : 0);
  vec2 uv = vec2(float(cx) / float(GX), float(cy) / float(GY));
  vec2 centre = u_warp[2].xy;
  float aspect = u_res.x * u_res.w;
  vec2 p = (uv - centre) * vec2(aspect, 1.0);
  float r = length(p);
  // atan(0, 0) is undefined in ES 3.00, and the grid has a vertex exactly at the centre.
  float a = r > 0.0 ? atan(p.y, p.x) : 0.0;
  a -= u_warp[0].y + u_warp[0].z * max(0.0, 1.0 - r * 1.6);
  r = r / max(u_warp[0].x, 0.01);
  r += u_warp[1].z * sin(r * u_warp[1].w - u_time.x * 3.0);
  vec2 src = centre + vec2(cos(a), sin(a)) * r / vec2(aspect, 1.0) - u_warp[1].xy;
  uint seed = uint(cx + cy * 97) + uint(u_time.x * 60.0) * 7919u;
  src += (vec2(hash01(seed), hash01(seed ^ 0x9e3779b9u)) - 0.5) * u_warp[2].z;
  v_uv = src;
  gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
}
