//! Default-font choice without fontconfig: the renderer falls back to one font file for any family a
//! script names but the file doesn't embed, so pick that file by the script the dialogue is in.

use std::path::{Path, PathBuf};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Script {
    Latin,
    /// Han, kana and Hangul: one CJK font covers all three.
    Cjk,
    Arabic,
    Hebrew,
    Thai,
}

const SCRIPTS: [Script; 5] = [
    Script::Latin,
    Script::Cjk,
    Script::Arabic,
    Script::Hebrew,
    Script::Thai,
];

fn script_of(c: char) -> Option<Script> {
    match c as u32 {
        0x3040..=0x30FF
        | 0x3400..=0x4DBF
        | 0x4E00..=0x9FFF
        | 0xAC00..=0xD7AF
        | 0x1100..=0x11FF
        | 0xF900..=0xFAFF
        | 0xFF66..=0xFF9F
        | 0x20000..=0x2FA1F => Some(Script::Cjk),
        0x0600..=0x06FF | 0x0750..=0x077F | 0xFB50..=0xFDFF | 0xFE70..=0xFEFF => {
            Some(Script::Arabic)
        }
        0x0590..=0x05FF => Some(Script::Hebrew),
        0x0E00..=0x0E7F => Some(Script::Thai),
        _ if c.is_alphabetic() => Some(Script::Latin),
        _ => None,
    }
}

/// Letter counts per script over dialogue text, override blocks excluded.
#[derive(Debug, Default, Clone)]
pub struct Tally {
    counts: [u32; SCRIPTS.len()],
}

impl Tally {
    pub fn add_event_text(&mut self, text: &str) {
        let mut in_override = false;
        for c in text.chars() {
            match c {
                '{' => in_override = true,
                '}' => in_override = false,
                _ if in_override => {}
                _ => {
                    if let Some(s) = script_of(c) {
                        if let Some(i) = SCRIPTS.iter().position(|&x| x == s) {
                            self.counts[i] = self.counts[i].saturating_add(1);
                        }
                    }
                }
            }
        }
    }

    /// The non-Latin script that needs its own font: at least 20 letters and a tenth of the text.
    pub fn dominant(&self) -> Script {
        let total: u32 = self.counts.iter().fold(0, |a, &n| a.saturating_add(n));
        SCRIPTS
            .iter()
            .zip(self.counts)
            .filter(|(s, n)| {
                **s != Script::Latin && *n >= 20 && u64::from(*n) * 10 >= u64::from(total)
            })
            .max_by_key(|(_, n)| *n)
            .map_or(Script::Latin, |(s, _)| *s)
    }
}

/// Font files on this device, best first per script, and the per-glyph fallback list.
#[derive(Debug, Clone, Default)]
pub struct FontCandidates {
    pub by_script: Vec<(Script, PathBuf)>,
    /// Searched in order for a glyph no script font, attached font or default font has.
    pub fallback: Vec<PathBuf>,
}

impl FontCandidates {
    /// Android's own system fonts, kept only when the file exists.
    pub fn android(exists: impl Fn(&Path) -> bool) -> Self {
        const KNOWN: &[(Script, &str)] = &[
            (Script::Latin, "/system/fonts/Roboto-Regular.ttf"),
            (Script::Latin, "/system/fonts/RobotoStatic-Regular.ttf"),
            (Script::Latin, "/system/fonts/DroidSans.ttf"),
            (Script::Cjk, "/system/fonts/NotoSansCJK-Regular.ttc"),
            (Script::Cjk, "/system/fonts/NotoSansSC-Regular.otf"),
            (Script::Cjk, "/system/fonts/DroidSansFallback.ttf"),
            (Script::Arabic, "/system/fonts/NotoNaskhArabic-Regular.ttf"),
            (Script::Arabic, "/system/fonts/NotoSansArabic-Regular.ttf"),
            (Script::Hebrew, "/system/fonts/NotoSansHebrew-Regular.ttf"),
            (Script::Thai, "/system/fonts/NotoSansThai-Regular.ttf"),
        ];
        // Outline fonts only: Android's colour emoji files are bitmaps the renderer cannot draw.
        const FALLBACK: &[&str] = &[
            "/system/fonts/Roboto-Regular.ttf",
            "/system/fonts/NotoSansCJK-Regular.ttc",
            "/system/fonts/NotoNaskhArabic-Regular.ttf",
            "/system/fonts/NotoSansHebrew-Regular.ttf",
            "/system/fonts/NotoSansThai-Regular.ttf",
            // Android 12+ ships variable fonts; earlier releases name the same scripts differently.
            "/system/fonts/NotoSansDevanagari-VF.ttf",
            "/system/fonts/NotoSansDevanagari-Regular.ttf",
            "/system/fonts/NotoSerifTibetan-VF.ttf",
            "/system/fonts/NotoSansTibetan-Regular.ttf",
            "/system/fonts/NotoSansSymbols-Regular-Subsetted.ttf",
            "/system/fonts/NotoSansSymbols-Regular-Subsetted2.ttf",
            "/system/fonts/DroidSansFallback.ttf",
        ];
        let by_script = KNOWN
            .iter()
            .filter(|(_, p)| exists(Path::new(p)))
            .map(|(s, p)| (*s, PathBuf::from(p)))
            .collect();
        let fallback = FALLBACK
            .iter()
            .map(Path::new)
            .filter(|p| exists(p))
            .map(Path::to_path_buf)
            .collect();
        Self {
            by_script,
            fallback,
        }
    }

    /// The file for `script`, else the Latin one, else nothing (the renderer then draws only embedded fonts).
    pub fn pick(&self, script: Script) -> Option<&Path> {
        let find = |s: Script| {
            self.by_script
                .iter()
                .find(|(x, _)| *x == s)
                .map(|(_, p)| p.as_path())
        };
        find(script).or_else(|| find(Script::Latin))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The relevant part of an Android 9 TV image's /system/fonts.
    const ANDROID_9: &[&str] = &[
        "Roboto-Regular.ttf",
        "NotoSansCJK-Regular.ttc",
        "NotoNaskhArabic-Regular.ttf",
        "NotoSansHebrew-Regular.ttf",
        "NotoSansThai-Regular.ttf",
        "NotoSansDevanagari-Regular.ttf",
        "NotoSansTibetan-Regular.ttf",
        "NotoSansSymbols-Regular-Subsetted.ttf",
        "NotoColorEmoji.ttf",
    ];

    #[test]
    fn android_9_fonts_cover_every_fallback_script() {
        let c = FontCandidates::android(|p| {
            p.file_name()
                .and_then(|n| n.to_str())
                .is_some_and(|n| ANDROID_9.contains(&n))
        });
        let names: Vec<_> = c
            .fallback
            .iter()
            .filter_map(|p| p.file_name()?.to_str())
            .collect();
        for script in [
            "CJK",
            "Arabic",
            "Hebrew",
            "Thai",
            "Devanagari",
            "Tibetan",
            "Symbols",
        ] {
            assert!(
                names.iter().any(|n| n.contains(script)),
                "{script} missing from {names:?}"
            );
        }
        assert!(
            !names.iter().any(|n| n.contains("Emoji")),
            "colour emoji is a bitmap font"
        );
    }

    #[test]
    fn override_tags_do_not_count() {
        let mut t = Tally::default();
        t.add_event_text("{\\fnArial Unicode MS\\blur3}hello");
        assert_eq!(t.counts[0], 5);
    }

    #[test]
    fn mostly_latin_with_a_few_kanji_stays_latin() {
        let mut t = Tally::default();
        t.add_event_text(&"The quick brown fox ".repeat(20));
        t.add_event_text("夢の中");
        assert_eq!(t.dominant(), Script::Latin);
    }

    #[test]
    fn japanese_and_korean_dialogue_pick_cjk() {
        let mut t = Tally::default();
        t.add_event_text(&"君の声が聞こえるよ".repeat(3));
        t.add_event_text(&"한국어 자막".repeat(3));
        assert_eq!(t.dominant(), Script::Cjk);
    }

    #[test]
    fn arabic_wins_over_a_little_cjk() {
        let mut t = Tally::default();
        t.add_event_text(&"مرحبا بالعالم".repeat(5));
        assert_eq!(t.dominant(), Script::Arabic);
    }

    #[test]
    fn picks_by_script_then_latin_then_none() {
        let only = |p: &str| {
            let p = p.to_owned();
            move |q: &Path| q == Path::new(&p)
        };
        let c = FontCandidates::android(only("/system/fonts/Roboto-Regular.ttf"));
        assert_eq!(
            c.pick(Script::Cjk),
            Some(Path::new("/system/fonts/Roboto-Regular.ttf"))
        );
        let none = FontCandidates::android(|_| false);
        assert_eq!(none.pick(Script::Latin), None);
        let all = FontCandidates::android(|_| true);
        assert_eq!(
            all.pick(Script::Cjk),
            Some(Path::new("/system/fonts/NotoSansCJK-Regular.ttc"))
        );
    }

    #[test]
    fn fallback_lists_present_outline_fonts_latin_first() {
        let all = FontCandidates::android(|_| true);
        assert_eq!(
            all.fallback.first().map(PathBuf::as_path),
            Some(Path::new("/system/fonts/Roboto-Regular.ttf"))
        );
        assert!(all
            .fallback
            .iter()
            .all(|p| !p.to_string_lossy().contains("Emoji")));
        let some = FontCandidates::android(|p| p.ends_with("NotoSansThai-Regular.ttf"));
        assert_eq!(
            some.fallback,
            vec![PathBuf::from("/system/fonts/NotoSansThai-Regular.ttf")]
        );
    }
}
