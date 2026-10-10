//! Per-item font store. Fonts wait here (not in the library) until a track is shown, are deduplicated
//! by content, and stop at a byte budget so one file can't take the process's memory.

use std::collections::HashSet;

/// What [`FontStore::offer`] did with a font.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Admission {
    Kept,
    Duplicate,
    OverBudget,
    /// Not an OpenType/TrueType/collection file by its magic bytes; substation reads no WOFF.
    NotAFont,
}

pub struct PendingFont {
    pub name: String,
    pub data: Vec<u8>,
}

pub struct FontStore {
    budget_bytes: usize,
    max_font_bytes: usize,
    held_bytes: usize,
    seen: HashSet<u64>,
    pending: Vec<PendingFont>,
    rejected_over_budget: u32,
}

impl FontStore {
    pub fn new(budget_bytes: usize, max_font_bytes: usize) -> Self {
        Self {
            budget_bytes,
            max_font_bytes,
            held_bytes: 0,
            seen: HashSet::new(),
            pending: Vec::new(),
            rejected_over_budget: 0,
        }
    }

    pub fn offer(&mut self, name: String, data: Vec<u8>) -> Admission {
        if !looks_like_font(&data) {
            return Admission::NotAFont;
        }
        let hash = content_hash(&data);
        if self.seen.contains(&hash) {
            return Admission::Duplicate;
        }
        if data.len() > self.max_font_bytes || self.held_bytes + data.len() > self.budget_bytes {
            self.rejected_over_budget += 1;
            return Admission::OverBudget;
        }
        self.seen.insert(hash);
        self.held_bytes += data.len();
        self.pending.push(PendingFont { name, data });
        Admission::Kept
    }

    /// Hands the waiting fonts to the renderer; their bytes stay counted against the budget.
    pub fn take_pending(&mut self) -> Vec<PendingFont> {
        std::mem::take(&mut self.pending)
    }

    pub fn held_bytes(&self) -> usize {
        self.held_bytes
    }

    pub fn rejected_over_budget(&self) -> u32 {
        self.rejected_over_budget
    }
}

/// sfnt 1.0, 'true', 'OTTO', 'ttcf'.
fn looks_like_font(data: &[u8]) -> bool {
    matches!(
        data.get(..4),
        Some([0, 1, 0, 0] | b"true" | b"OTTO" | b"ttcf")
    )
}

/// Two 32-bit multiply-xor lanes over 4-byte words: whole-file like FNV-1a but a quarter of the
/// steps and no 64-bit multiplies, which a 32-bit TV CPU does in several instructions. A collision
/// only skips a font that looked like a duplicate.
fn content_hash(data: &[u8]) -> u64 {
    let (mut a, mut b) = (0x811c_9dc5_u32 ^ data.len() as u32, 0x9e37_79b9_u32);
    let (words, tail) = data.as_chunks::<4>();
    for w in words {
        let w = u32::from_le_bytes(*w);
        a = (a ^ w).wrapping_mul(0x0100_0193);
        b = (b ^ w).rotate_left(13).wrapping_mul(0x85eb_ca6b);
    }
    for &t in tail {
        a = (a ^ u32::from(t)).wrapping_mul(0x0100_0193);
    }
    (u64::from(a) << 32) | u64::from(b)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn font(tag: u8, len: usize) -> Vec<u8> {
        let mut v = vec![tag; len];
        v[..4].copy_from_slice(&[0, 1, 0, 0]);
        v
    }

    #[test]
    fn keeps_dedupes_and_budgets() {
        let mut s = FontStore::new(100, 100);
        assert_eq!(s.offer("a".into(), font(1, 40)), Admission::Kept);
        assert_eq!(s.offer("a-copy".into(), font(1, 40)), Admission::Duplicate);
        assert_eq!(s.offer("b".into(), font(2, 50)), Admission::Kept);
        assert_eq!(s.offer("c".into(), font(3, 20)), Admission::OverBudget);
        assert_eq!(s.held_bytes(), 90);
        assert_eq!(s.rejected_over_budget(), 1);
    }

    #[test]
    fn one_oversized_font_is_refused_even_with_budget_left() {
        let mut s = FontStore::new(100, 30);
        assert_eq!(s.offer("big".into(), font(1, 40)), Admission::OverBudget);
        assert_eq!(s.offer("ok".into(), font(2, 30)), Admission::Kept);
    }

    #[test]
    fn similar_fonts_hash_apart() {
        let (a, mut b) = (font(1, 4001), font(1, 4001));
        b[2000] ^= 1;
        assert_ne!(content_hash(&a), content_hash(&b));
        assert_ne!(content_hash(&a[..4000]), content_hash(&a));
    }

    #[test]
    fn rejects_non_fonts() {
        let mut s = FontStore::new(100, 100);
        assert_eq!(
            s.offer("x".into(), b"PK\x03\x04zip".to_vec()),
            Admission::NotAFont
        );
        assert_eq!(
            s.offer("w".into(), b"wOF2font".to_vec()),
            Admission::NotAFont
        );
        assert_eq!(s.held_bytes(), 0);
    }

    #[test]
    fn taken_fonts_stay_counted() {
        let mut s = FontStore::new(100, 100);
        s.offer("a".into(), font(1, 60));
        assert_eq!(s.take_pending().len(), 1);
        assert_eq!(s.held_bytes(), 60);
        assert_eq!(s.offer("b".into(), font(2, 50)), Admission::OverBudget);
    }
}
