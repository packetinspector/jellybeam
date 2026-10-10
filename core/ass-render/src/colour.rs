//! docs/13: a script's colours are what VSFilter showed, converted to YCbCr with the matrix its
//! `YCbCr Matrix` header names (BT.601 TV when missing) and back to RGB with the video's own. libass
//! leaves this to the player (ass_types.h, `ASS_YCbCrMatrix`); "None" and a matching video keep them.

use substation::types::YCbCrMatrix;

/// The video's colour matrix as the player reports it.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum VideoMatrix {
    Bt601,
    Bt709,
    Bt2020,
    /// Not signalled: guessed from the height, as xy-VSFilter and mpv do.
    #[default]
    Unknown,
}

/// What the subtitle colours are converted for.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct VideoColour {
    pub matrix: VideoMatrix,
    pub full_range: bool,
    /// PQ or HLG: libass leaves colours over HDR to the player, and matching them exactly is moot.
    pub hdr: bool,
    pub height: i32,
}

/// One YCbCr space: luma coefficients and range.
#[derive(Debug, Clone, Copy, PartialEq)]
struct Space {
    kr: f64,
    kb: f64,
    full: bool,
}

const BT601: (f64, f64) = (0.299, 0.114);
const BT709: (f64, f64) = (0.2126, 0.0722);
const BT2020: (f64, f64) = (0.2627, 0.0593);
const SMPTE240M: (f64, f64) = (0.212, 0.087);
const FCC: (f64, f64) = (0.30, 0.11);

/// The conversion for `header` on `video`, or `None` when colours pass unchanged.
pub fn mangle(header: YCbCrMatrix, video: VideoColour) -> Option<Mangle> {
    let space = |(kr, kb): (f64, f64), full| Space { kr, kb, full };
    let from = match header {
        YCbCrMatrix::Default | YCbCrMatrix::Bt601Tv => space(BT601, false),
        YCbCrMatrix::Bt601Pc => space(BT601, true),
        YCbCrMatrix::Bt709Tv => space(BT709, false),
        YCbCrMatrix::Bt709Pc => space(BT709, true),
        YCbCrMatrix::Smpte240mTv => space(SMPTE240M, false),
        YCbCrMatrix::Smpte240mPc => space(SMPTE240M, true),
        YCbCrMatrix::FccTv => space(FCC, false),
        YCbCrMatrix::FccPc => space(FCC, true),
        // An unparsable header is treated like "None": changing colours on a guess is worse.
        YCbCrMatrix::None | YCbCrMatrix::Unknown => return None,
    };
    if video.hdr {
        return None;
    }
    let coefficients = match video.matrix {
        VideoMatrix::Bt601 => BT601,
        VideoMatrix::Bt709 => BT709,
        VideoMatrix::Bt2020 => BT2020,
        VideoMatrix::Unknown if video.height > 0 && video.height <= 576 => BT601,
        VideoMatrix::Unknown => BT709,
    };
    let to = space(coefficients, video.full_range);
    (from != to).then_some(Mangle { from, to })
}

/// A header-to-video colour conversion.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Mangle {
    from: Space,
    to: Space,
}

impl Mangle {
    /// `color` (0xRRGGBBTT) converted; the transparency byte is kept.
    pub fn apply(&self, color: u32) -> u32 {
        let channel = |shift: u32| f64::from((color >> shift) & 0xFF) / 255.0;
        let ycc = to_ycbcr([channel(24), channel(16), channel(8)], self.from);
        let [r, g, b] = to_rgb(ycc, self.to);
        let byte = |v: f64| (v * 255.0).round().clamp(0.0, 255.0) as u32;
        (byte(r) << 24) | (byte(g) << 16) | (byte(b) << 8) | (color & 0xFF)
    }
}

/// RGB in 0..1 to 8-bit-scaled Y, Cb, Cr (Y 16..235 and C 16..240 when TV range).
fn to_ycbcr([r, g, b]: [f64; 3], s: Space) -> [f64; 3] {
    let y = s.kr * r + (1.0 - s.kr - s.kb) * g + s.kb * b;
    let cb = (b - y) / (2.0 * (1.0 - s.kb));
    let cr = (r - y) / (2.0 * (1.0 - s.kr));
    if s.full {
        [255.0 * y, 128.0 + 255.0 * cb, 128.0 + 255.0 * cr]
    } else {
        [16.0 + 219.0 * y, 128.0 + 224.0 * cb, 128.0 + 224.0 * cr]
    }
}

fn to_rgb([y, cb, cr]: [f64; 3], s: Space) -> [f64; 3] {
    let (y, cb, cr) = if s.full {
        (y / 255.0, (cb - 128.0) / 255.0, (cr - 128.0) / 255.0)
    } else {
        (
            (y - 16.0) / 219.0,
            (cb - 128.0) / 224.0,
            (cr - 128.0) / 224.0,
        )
    };
    let r = y + 2.0 * (1.0 - s.kr) * cr;
    let b = y + 2.0 * (1.0 - s.kb) * cb;
    let g = (y - s.kr * r - s.kb * b) / (1.0 - s.kr - s.kb);
    [r, g, b]
}

#[cfg(test)]
mod tests {
    use super::*;

    const HD: VideoColour = VideoColour {
        matrix: VideoMatrix::Bt709,
        full_range: false,
        hdr: false,
        height: 1080,
    };
    const SD: VideoColour = VideoColour {
        matrix: VideoMatrix::Unknown,
        full_range: false,
        hdr: false,
        height: 480,
    };

    fn rgb(c: u32) -> (u32, u32, u32) {
        (c >> 24, (c >> 16) & 0xFF, (c >> 8) & 0xFF)
    }

    #[test]
    fn a_bt601_or_missing_header_on_hd_video_shifts_colours_as_vsfilter_did() {
        // Values from the formula in libass's ass_types.h, BT.601 TV in and BT.709 TV out.
        for header in [YCbCrMatrix::Default, YCbCrMatrix::Bt601Tv] {
            let m = mangle(header, HD).expect("converted");
            assert_eq!(rgb(m.apply(0xFF00_0000)), (255, 25, 0), "red");
            assert_eq!(rgb(m.apply(0x00FF_0000)), (0, 215, 0), "green");
            assert_eq!(rgb(m.apply(0x0000_FF00)), (0, 15, 255), "blue");
            assert_eq!(rgb(m.apply(0xFF80_0000)), (255, 133, 0), "orange");
            assert_eq!(rgb(m.apply(0xE0AC_6900)), (229, 173, 101), "skin");
            assert_eq!(
                rgb(m.apply(0x8080_8000)),
                (128, 128, 128),
                "grey stays grey"
            );
        }
    }

    #[test]
    fn transparency_is_kept() {
        let m = mangle(YCbCrMatrix::Default, HD).expect("converted");
        assert_eq!(m.apply(0xFF00_0080) & 0xFF, 0x80);
    }

    #[test]
    fn none_a_matching_video_unparsable_and_hdr_keep_colours() {
        assert_eq!(mangle(YCbCrMatrix::None, HD), None);
        assert_eq!(mangle(YCbCrMatrix::Unknown, HD), None);
        assert_eq!(mangle(YCbCrMatrix::Bt709Tv, HD), None);
        assert_eq!(
            mangle(YCbCrMatrix::Default, SD),
            None,
            "SD guesses BT.601, the header's own"
        );
        assert_eq!(
            mangle(YCbCrMatrix::Default, VideoColour { hdr: true, ..HD }),
            None
        );
    }

    #[test]
    fn unknown_video_guesses_from_the_height() {
        let unknown_hd = VideoColour {
            matrix: VideoMatrix::Unknown,
            ..HD
        };
        assert_eq!(
            mangle(YCbCrMatrix::Default, unknown_hd),
            mangle(YCbCrMatrix::Default, HD)
        );
        assert!(
            mangle(YCbCrMatrix::Bt709Tv, SD).is_some(),
            "a BT.709 header on SD video converts the other way"
        );
    }

    #[test]
    fn a_pc_range_header_differs_from_tv() {
        let tv = mangle(
            YCbCrMatrix::Bt709Tv,
            VideoColour {
                full_range: true,
                ..HD
            },
        )
        .expect("converted");
        assert_ne!(rgb(tv.apply(0x4080_C000)), (0x40, 0x80, 0xC0));
        assert_eq!(
            mangle(
                YCbCrMatrix::Bt709Pc,
                VideoColour {
                    full_range: true,
                    ..HD
                }
            ),
            None
        );
    }
}
