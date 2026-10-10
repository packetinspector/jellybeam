//! Media3's embedded-SSA sample format: Matroska blocks arrive as
//! `Dialogue: 0:00:00:00,H:MM:SS:CC,` + the block payload, with the block duration written as the
//! end timecode and the start carried by the sample time. The renderer wants the payload and the times.

const PREFIX: &[u8] = b"Dialogue: 0:00:00:00,";
const END_TIMECODE_LEN: usize = 10; // "H:MM:SS:CC"

/// One embedded event ready for `ass_process_chunk`.
#[derive(Debug, PartialEq, Eq)]
pub struct Chunk<'a> {
    pub duration_ms: i64,
    /// `ReadOrder,Layer,Style,Name,MarginL,MarginR,MarginV,Effect,Text`.
    pub payload: &'a [u8],
}

/// Splits a Media3 SSA sample; `None` for anything not in that exact shape.
pub fn parse_sample(sample: &[u8]) -> Option<Chunk<'_>> {
    let rest = sample.strip_prefix(PREFIX)?;
    if rest.len() < END_TIMECODE_LEN + 1 || rest[END_TIMECODE_LEN] != b',' {
        return None;
    }
    let duration_ms = parse_timecode(&rest[..END_TIMECODE_LEN])?;
    let payload = &rest[END_TIMECODE_LEN + 1..];
    // Media3 pads nothing, but a stray trailing NUL from a muxer would reach the renderer as text.
    let payload = payload.strip_suffix(b"\0").unwrap_or(payload);
    Some(Chunk {
        duration_ms,
        payload,
    })
}

/// `H:MM:SS:CC` (Media3 writes centiseconds after a colon) to milliseconds.
fn parse_timecode(tc: &[u8]) -> Option<i64> {
    let s = std::str::from_utf8(tc).ok()?;
    // Digits only: `parse` would accept a sign and yield a negative duration.
    let mut parts = s.split(':').map(|p| {
        p.bytes()
            .all(|b| b.is_ascii_digit())
            .then(|| p.parse::<i64>().ok())
            .flatten()
    });
    let (h, m, sec, cs) = (
        parts.next()??,
        parts.next()??,
        parts.next()??,
        parts.next()??,
    );
    if parts.next().is_some() || m > 59 || sec > 59 || cs > 99 {
        return None;
    }
    Some(((h * 60 + m) * 60 + sec) * 1000 + cs * 10)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn splits_duration_and_payload() {
        let s = b"Dialogue: 0:00:00:00,0:00:02:80,12,0,Default,,0,0,0,,Hello";
        let c = parse_sample(s).expect("parsed");
        assert_eq!(c.duration_ms, 2800);
        assert_eq!(c.payload, b"12,0,Default,,0,0,0,,Hello");
    }

    #[test]
    fn long_durations_use_hours() {
        let c = parse_sample(b"Dialogue: 0:00:00:00,1:02:03:04,0,0,D,,0,0,0,,x").expect("parsed");
        assert_eq!(c.duration_ms, 3_723_040);
    }

    #[test]
    fn rejects_other_shapes() {
        assert_eq!(
            parse_sample(b"Comment: 0:00:00:00,0:00:01:00,0,0,D,,0,0,0,,x"),
            None
        );
        assert_eq!(parse_sample(b"Dialogue: 0:00:00:00,0:00:01"), None);
        assert_eq!(
            parse_sample(b"Dialogue: 0:00:00:00,0:61:00:00,0,0,D,,0,0,0,,x"),
            None
        );
    }

    #[test]
    fn signed_or_empty_fields_are_refused() {
        for tc in [
            "0:-1:-1:-1",
            "-5:00:00:0",
            "+1:00:00:00",
            "0:+1:00:00",
            "0::00:00",
        ] {
            assert_eq!(parse_timecode(tc.as_bytes()), None, "{tc}");
        }
    }

    #[test]
    fn trailing_nul_is_dropped() {
        let c = parse_sample(b"Dialogue: 0:00:00:00,0:00:01:00,0,0,D,,0,0,0,,x\0").expect("parsed");
        assert_eq!(c.payload, b"0,0,D,,0,0,0,,x");
    }
}
