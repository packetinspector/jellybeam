// docs/30 §6: prepended to every inject and overlay vertex shader after common.glsl.
out float v_t;
out float v_d;
out float v_hw;
out float v_p;

// Emits a corner with intensity t (0..1) and palette position p (cyclic).
void emit(Vert v, float t, float p) {
  gl_Position = clipPos(v.pos);
  v_t = clamp(t, 0.0, 1.0);
  v_d = v.d;
  v_hw = v.hw;
  v_p = p;
}
