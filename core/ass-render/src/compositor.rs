//! Blends the renderer's coverage bitmaps into one reused RGBA (premultiplied) canvas and reports the
//! dirty rectangle, so a frame costs only the area that changed and never a fresh allocation.

/// Half-open pixel rectangle; empty when `x0 >= x1 || y0 >= y1`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct Rect {
    pub x0: i32,
    pub y0: i32,
    pub x1: i32,
    pub y1: i32,
}

impl Rect {
    pub fn new(x0: i32, y0: i32, x1: i32, y1: i32) -> Self {
        Self { x0, y0, x1, y1 }
    }

    pub fn is_empty(&self) -> bool {
        self.x0 >= self.x1 || self.y0 >= self.y1
    }

    pub fn union(self, o: Rect) -> Rect {
        if self.is_empty() {
            return o;
        }
        if o.is_empty() {
            return self;
        }
        Rect::new(
            self.x0.min(o.x0),
            self.y0.min(o.y0),
            self.x1.max(o.x1),
            self.y1.max(o.y1),
        )
    }

    pub fn clip(self, w: i32, h: i32) -> Rect {
        let r = Rect::new(
            self.x0.max(0),
            self.y0.max(0),
            self.x1.min(w),
            self.y1.min(h),
        );
        if r.is_empty() {
            Rect::default()
        } else {
            r
        }
    }
}

/// One rendered image: coverage bitmap plus a single 0xRRGGBBTT color (TT = transparency).
#[derive(Clone, Copy)]
pub struct Coverage<'a> {
    pub w: i32,
    pub h: i32,
    pub stride: usize,
    pub bitmap: &'a [u8],
    pub color: u32,
    pub x: i32,
    pub y: i32,
}

pub struct Canvas {
    w: i32,
    h: i32,
    /// RGBA8888, premultiplied, row-major, `w * 4` bytes per row.
    px: Vec<u8>,
    /// What the last frame touched; it must be cleared before the next one draws.
    last: Rect,
    /// The last frame's images, in draw order, to find what changed.
    shown: Vec<Shown>,
}

/// One drawn image's identity: substation hands back the same cached bitmap for an unchanged image,
/// which is also how libass's own change detection compares frames. Sound because substation keeps
/// the previous frame's images alive until the next render, so an address can't be reused between
/// two compared frames; anything that drops them (a new renderer, a resize) clears `Canvas::shown`.
#[derive(Clone, Copy, PartialEq, Eq, Hash)]
struct Shown {
    bitmap: usize,
    len: usize,
    w: i32,
    h: i32,
    stride: usize,
    color: u32,
    x: i32,
    y: i32,
}

impl Shown {
    fn of(i: &Coverage<'_>) -> Self {
        Self {
            bitmap: i.bitmap.as_ptr() as usize,
            len: i.bitmap.len(),
            w: i.w,
            h: i.h,
            stride: i.stride,
            color: i.color,
            x: i.x,
            y: i.y,
        }
    }

    fn rect(&self) -> Rect {
        Rect::new(self.x, self.y, self.x + self.w, self.y + self.h)
    }
}

/// Past this many separate changed regions, one rectangle around all of them is cheaper to track.
const MAX_REGIONS: usize = 16;

impl Canvas {
    pub fn new(w: i32, h: i32) -> Self {
        let (w, h) = (w.max(0), h.max(0));
        Self {
            w,
            h,
            px: vec![0; (w * h * 4) as usize],
            last: Rect::default(),
            shown: Vec::new(),
        }
    }

    pub fn width(&self) -> i32 {
        self.w
    }

    pub fn height(&self) -> i32 {
        self.h
    }

    pub fn pixels(&self) -> &[u8] {
        &self.px
    }

    /// Clears the canvas; returns the area a sink must repaint to erase the last frame.
    pub fn clear(&mut self) -> Rect {
        let dirty = self.last;
        self.fill_clear(dirty);
        self.last = Rect::default();
        self.shown.clear();
        dirty
    }

    /// Replaces the canvas content with `images`, repainting only the regions where an image
    /// appeared, moved or went away; returns the rectangle around them.
    pub fn draw(&mut self, images: &[Coverage<'_>]) -> Rect {
        let now: Vec<Shown> = images.iter().map(Shown::of).collect();
        let regions = changed_regions(&self.shown, &now, self.w, self.h);
        let mut dirty = Rect::default();
        for &r in &regions {
            self.fill_clear(r);
            for img in images {
                self.blend(img, r);
            }
            dirty = dirty.union(r);
        }
        self.last = now
            .iter()
            .fold(Rect::default(), |acc, i| acc.union(i.rect()))
            .clip(self.w, self.h);
        self.shown = now;
        dirty
    }

    fn fill_clear(&mut self, r: Rect) {
        let r = r.clip(self.w, self.h);
        if r.is_empty() {
            return;
        }
        let row = (self.w * 4) as usize;
        for y in r.y0..r.y1 {
            let start = y as usize * row + r.x0 as usize * 4;
            self.px[start..start + (r.x1 - r.x0) as usize * 4].fill(0);
        }
    }

    /// Source-over of one image, limited to `within`; bit-exact with per-channel `div255` math.
    fn blend(&mut self, img: &Coverage<'_>, within: Rect) {
        let opacity = 255 - (img.color & 0xFF);
        if opacity == 0 {
            return;
        }
        let area = Rect::new(img.x, img.y, img.x + img.w, img.y + img.h).clip(self.w, self.h);
        let area = Rect::new(
            area.x0.max(within.x0),
            area.y0.max(within.y0),
            area.x1.min(within.x1),
            area.y1.min(within.y1),
        );
        if area.is_empty() {
            return;
        }
        let color = [
            ((img.color >> 24) & 0xFF) as u16,
            ((img.color >> 16) & 0xFF) as u16,
            ((img.color >> 8) & 0xFF) as u16,
            255,
        ];
        let opacity = opacity as u16;
        let row = (self.w * 4) as usize;
        let (sx, span) = ((area.x0 - img.x) as usize, (area.x1 - area.x0) as usize);
        for y in area.y0..area.y1 {
            let s = (y - img.y) as usize * img.stride + sx;
            let d = y as usize * row + area.x0 as usize * 4;
            // Slicing once per row keeps the per-pixel loop free of bounds checks.
            let (Some(src), Some(dst)) = (
                img.bitmap.get(s..s + span),
                self.px.get_mut(d..d + span * 4),
            ) else {
                continue;
            };
            // Glyph boxes are mostly empty: eight uncovered pixels are skipped with one compare.
            let (src8, src_tail) = src.as_chunks::<8>();
            let (dst8, dst_tail) = dst.as_chunks_mut::<32>();
            for (cov8, px8) in src8.iter().zip(dst8) {
                if u64::from_ne_bytes(*cov8) != 0 {
                    over_run(cov8, px8, color, opacity);
                }
            }
            over_run(src_tail, dst_tail, color, opacity);
        }
    }
}

/// Source-over of `cov.len()` pixels of one image into `px` (4 bytes each). Branch-free 16-bit
/// lanes so the compiler vectorizes it (NEON loads four channels at once); coverage 0 leaves a
/// pixel exactly as it was.
fn over_run(cov: &[u8], px: &mut [u8], color: [u16; 4], opacity: u16) {
    for (&c, p) in cov.iter().zip(px.as_chunks_mut::<4>().0) {
        let a = div255_u16(u16::from(c) * opacity);
        let inv = 255 - a;
        for (d, &k) in p.iter_mut().zip(&color) {
            *d = (div255_u16(k * a) + div255_u16(u16::from(*d) * inv)) as u8;
        }
    }
}

/// Exact `round(v / 255)` in 16 bits: `v + 128 + (v + 128) / 256` stays below 65536 for `v <= 255 * 255`.
fn div255_u16(v: u16) -> u16 {
    let t = v + 128;
    (t + (t >> 8)) >> 8
}

/// The regions to repaint between two frames: where an image left or arrived, merged where they
/// overlap; everything the frames span when the shared images changed order.
fn changed_regions(before: &[Shown], after: &[Shown], w: i32, h: i32) -> Vec<Rect> {
    if before == after {
        return Vec::new();
    }
    let was: std::collections::HashSet<&Shown> = before.iter().collect();
    let is: std::collections::HashSet<&Shown> = after.iter().collect();
    let kept_before = before.iter().filter(|s| is.contains(s));
    let kept_after = after.iter().filter(|s| was.contains(s));
    let rects: Vec<Rect> = if kept_before.ne(kept_after) {
        vec![before
            .iter()
            .chain(after)
            .fold(Rect::default(), |a, s| a.union(s.rect()))]
    } else {
        before
            .iter()
            .filter(|s| !is.contains(s))
            .chain(after.iter().filter(|s| !was.contains(s)))
            .map(Shown::rect)
            .collect()
    };
    merge(
        rects
            .into_iter()
            .map(|r| r.clip(w, h))
            .filter(|r| !r.is_empty())
            .collect(),
    )
}

/// Unions overlapping rectangles until none overlap, or into one past [`MAX_REGIONS`]; a frame
/// where nearly everything changed skips the quadratic pass.
fn merge(mut rects: Vec<Rect>) -> Vec<Rect> {
    if rects.len() > MAX_REGIONS * 4 {
        return vec![rects.iter().fold(Rect::default(), |a, &r| a.union(r))];
    }
    let mut i = 0;
    while i < rects.len() {
        let mut j = i + 1;
        let mut grew = false;
        while j < rects.len() {
            if overlaps(rects[i], rects[j]) {
                let other = rects.swap_remove(j);
                rects[i] = rects[i].union(other);
                grew = true;
            } else {
                j += 1;
            }
        }
        if !grew {
            i += 1;
        }
    }
    if rects.len() > MAX_REGIONS {
        let all = rects.iter().fold(Rect::default(), |a, &r| a.union(r));
        return vec![all];
    }
    rects
}

fn overlaps(a: Rect, b: Rect) -> bool {
    a.x0 < b.x1 && b.x0 < a.x1 && a.y0 < b.y1 && b.y0 < a.y1
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Exact `round(v / 255)` for `v <= 255 * 255`; the reference the 16-bit blend must match.
    fn div255(v: u32) -> u32 {
        (v + 128 + ((v + 128) >> 8)) >> 8
    }

    fn px(c: &Canvas, x: i32, y: i32) -> [u8; 4] {
        let i = ((y * c.width() + x) * 4) as usize;
        c.pixels()[i..i + 4].try_into().unwrap_or([0; 4])
    }

    #[test]
    fn div255_is_exact() {
        for v in [0u32, 1, 127, 128, 254, 255, 255 * 128, 255 * 255] {
            assert_eq!(div255(v), (v as f64 / 255.0).round() as u32, "v={v}");
        }
    }

    #[test]
    fn opaque_full_coverage_writes_the_color() {
        let mut c = Canvas::new(4, 4);
        let bmp = [255u8; 4];
        let img = Coverage {
            w: 2,
            h: 2,
            stride: 2,
            bitmap: &bmp,
            color: 0xFF80_4000,
            x: 1,
            y: 1,
        };
        let dirty = c.draw(&[img]);
        assert_eq!(dirty, Rect::new(1, 1, 3, 3));
        assert_eq!(px(&c, 1, 1), [255, 128, 64, 255]);
        assert_eq!(px(&c, 0, 0), [0, 0, 0, 0]);
    }

    #[test]
    fn transparency_and_coverage_premultiply() {
        let mut c = Canvas::new(1, 1);
        let bmp = [128u8];
        // 50% transparent white at 50% coverage: alpha ~ 64, premultiplied channels equal alpha.
        let img = Coverage {
            w: 1,
            h: 1,
            stride: 1,
            bitmap: &bmp,
            color: 0xFFFF_FF80,
            x: 0,
            y: 0,
        };
        c.draw(&[img]);
        let p = px(&c, 0, 0);
        assert_eq!(p[3], 64);
        assert_eq!(p[0], p[3]);
    }

    #[test]
    fn later_images_composite_over_earlier_ones() {
        let mut c = Canvas::new(1, 1);
        let bmp = [255u8];
        let black = Coverage {
            w: 1,
            h: 1,
            stride: 1,
            bitmap: &bmp,
            color: 0x0000_0000,
            x: 0,
            y: 0,
        };
        let half_white = Coverage {
            color: 0xFFFF_FF80,
            ..black
        };
        c.draw(&[black, half_white]);
        assert_eq!(px(&c, 0, 0), [127, 127, 127, 255]);
    }

    #[test]
    fn dirty_rect_covers_what_the_last_frame_left_behind() {
        let mut c = Canvas::new(10, 10);
        let bmp = [255u8; 4];
        let a = Coverage {
            w: 2,
            h: 2,
            stride: 2,
            bitmap: &bmp,
            color: 0xFFFF_FF00,
            x: 0,
            y: 0,
        };
        c.draw(&[a]);
        let moved = Coverage { x: 5, y: 5, ..a };
        assert_eq!(c.draw(&[moved]), Rect::new(0, 0, 7, 7));
        assert_eq!(px(&c, 0, 0), [0, 0, 0, 0], "old frame erased");
        assert_eq!(c.clear(), Rect::new(5, 5, 7, 7));
        assert_eq!(c.draw(&[]), Rect::default());
    }

    #[test]
    fn offscreen_parts_are_clipped() {
        let mut c = Canvas::new(2, 2);
        let bmp = [255u8; 9];
        let img = Coverage {
            w: 3,
            h: 3,
            stride: 3,
            bitmap: &bmp,
            color: 0xFFFF_FF00,
            x: -1,
            y: -1,
        };
        assert_eq!(c.draw(&[img]), Rect::new(0, 0, 2, 2));
        assert_eq!(px(&c, 1, 1), [255, 255, 255, 255]);
    }

    /// The per-channel source-over the packed blend replaced.
    fn reference_over(px: [u8; 4], cov: u8, color: u32) -> [u8; 4] {
        let opacity = 255 - (color & 0xFF);
        let (r, g, b) = (
            (color >> 24) & 0xFF,
            (color >> 16) & 0xFF,
            (color >> 8) & 0xFF,
        );
        let a = div255(u32::from(cov) * opacity);
        let inv = 255 - a;
        [
            (div255(r * a) + div255(u32::from(px[0]) * inv)) as u8,
            (div255(g * a) + div255(u32::from(px[1]) * inv)) as u8,
            (div255(b * a) + div255(u32::from(px[2]) * inv)) as u8,
            (a + div255(u32::from(px[3]) * inv)) as u8,
        ]
    }

    #[test]
    fn packed_blend_matches_per_channel_math() {
        let mut seed = 0x9E37_79B9_7F4A_7C15u64;
        let mut next = || {
            seed ^= seed << 13;
            seed ^= seed >> 7;
            seed ^= seed << 17;
            seed
        };
        for _ in 0..200_000 {
            let n = next();
            // Any premultiplied destination: channels never exceed alpha.
            let a = (n & 0xFF) as u8;
            let ch = |v: u64| ((v & 0xFF) as u16 * u16::from(a) / 255) as u8;
            let start = [ch(n >> 8), ch(n >> 16), ch(n >> 24), a];
            let cov = (n >> 32) as u8;
            let color = (n >> 40) as u32 | ((n as u32) << 24);
            let mut c = Canvas::new(1, 1);
            c.px.copy_from_slice(&start);
            let bmp = [cov];
            let img = Coverage {
                w: 1,
                h: 1,
                stride: 1,
                bitmap: &bmp,
                color,
                x: 0,
                y: 0,
            };
            c.blend(&img, Rect::new(0, 0, 1, 1));
            let want = if cov == 0 || color & 0xFF == 0xFF {
                start
            } else {
                reference_over(start, cov, color)
            };
            assert_eq!(
                px(&c, 0, 0),
                want,
                "start={start:?} cov={cov} color={color:08x}"
            );
        }
    }

    fn square(bmp: &[u8], x: i32, y: i32) -> Coverage<'_> {
        Coverage {
            w: 2,
            h: 2,
            stride: 2,
            bitmap: bmp,
            color: 0xFFFF_FF00,
            x,
            y,
        }
    }

    #[test]
    fn an_unchanged_frame_repaints_nothing() {
        let bmp = [255u8; 4];
        let mut c = Canvas::new(10, 10);
        c.draw(&[square(&bmp, 0, 0), square(&bmp, 6, 6)]);
        assert_eq!(
            c.draw(&[square(&bmp, 0, 0), square(&bmp, 6, 6)]),
            Rect::default()
        );
        assert_eq!(px(&c, 6, 6), [255, 255, 255, 255]);
    }

    #[test]
    fn only_the_image_that_moved_is_repainted() {
        let bmp = [255u8; 4];
        let mut c = Canvas::new(20, 20);
        c.draw(&[square(&bmp, 0, 0), square(&bmp, 10, 10)]);
        // The top-left sign stays; the bottom one moves right by two.
        let dirty = c.draw(&[square(&bmp, 0, 0), square(&bmp, 12, 10)]);
        assert_eq!(dirty, Rect::new(10, 10, 14, 12));
        assert_eq!(px(&c, 10, 10), [0, 0, 0, 0], "old position erased");
        assert_eq!(px(&c, 13, 11), [255, 255, 255, 255]);
        assert_eq!(px(&c, 1, 1), [255, 255, 255, 255], "untouched sign kept");
    }

    #[test]
    fn a_repainted_region_redraws_every_image_under_it() {
        let white = [255u8; 4];
        let mut c = Canvas::new(4, 4);
        let base = Coverage {
            color: 0x0000_0000,
            ..square(&white, 0, 0)
        };
        let top = Coverage {
            color: 0xFFFF_FF80,
            ..square(&white, 0, 0)
        };
        c.draw(&[base, top]);
        assert_eq!(px(&c, 0, 0), [127, 127, 127, 255]);
        // Removing the top image repaints its area with the base image still under it.
        c.draw(&[base]);
        assert_eq!(px(&c, 0, 0), [0, 0, 0, 255]);
    }

    #[test]
    fn a_reorder_repaints_both_frames_area() {
        let bmp = [255u8; 4];
        let mut c = Canvas::new(10, 10);
        let a = square(&bmp, 0, 0);
        let b = Coverage {
            color: 0x0000_0000,
            ..square(&bmp, 1, 1)
        };
        c.draw(&[a, b]);
        assert_eq!(c.draw(&[b, a]), Rect::new(0, 0, 3, 3));
        assert_eq!(px(&c, 1, 1), [255, 255, 255, 255], "a now on top");
    }

    #[test]
    fn far_apart_changes_stay_separate_regions() {
        let r = merge(vec![
            Rect::new(0, 0, 2, 2),
            Rect::new(10, 10, 12, 12),
            Rect::new(1, 1, 3, 3),
        ]);
        assert_eq!(r.len(), 2);
        assert!(r.contains(&Rect::new(0, 0, 3, 3)));
        let many: Vec<Rect> = (0..20).map(|i| Rect::new(i * 3, 0, i * 3 + 1, 1)).collect();
        assert_eq!(merge(many), vec![Rect::new(0, 0, 58, 1)]);
    }
}
