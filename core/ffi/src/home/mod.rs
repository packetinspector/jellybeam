//! docs/25: selectable Home layouts. The only module that names every layout: each layout's
//! record and builder live in its own submodule, and rules two layouts share live in
//! [`blocks`].

mod blocks;
mod classic;

pub use classic::{ClassicHome, HomeShelf, ShelfSource};

use media_cache::Mirror;
use serde::Deserialize as _;

use crate::settings::Settings;

/// docs/25 §4.2: which Home layout the viewer picked. Persisted in `Settings::home_layout`;
/// each variant's on-disk name is fixed once shipped.
#[derive(
    uniffi::Enum, serde::Serialize, serde::Deserialize, Default, Debug, Clone, Copy, PartialEq, Eq,
)]
pub enum HomeLayout {
    #[default]
    #[serde(rename = "classic")]
    Classic,
}

/// docs/25 §4.3: one variant per layout, holding only that layout's record.
#[derive(uniffi::Enum, Debug, Clone, PartialEq)]
pub enum HomeSnapshot {
    Classic { home: ClassicHome },
}

/// docs/25 §4.4: builds the layout the caller asked for, never the persisted one, so a
/// request can't race a settings write. Without a mirror, the requested variant comes back
/// empty.
pub(crate) fn snapshot(
    layout: HomeLayout,
    mirror: Option<&Mirror>,
    settings: &Settings,
) -> HomeSnapshot {
    match layout {
        HomeLayout::Classic => HomeSnapshot::Classic {
            home: mirror.map_or_else(ClassicHome::default, |m| classic::build(m, settings)),
        },
    }
}

/// docs/25 §4.2: an unknown or malformed stored layout (a downgrade, a retired layout) loads as
/// the default instead of failing the whole settings file.
pub(crate) fn deserialize_layout<'de, D>(deserializer: D) -> Result<HomeLayout, D::Error>
where
    D: serde::Deserializer<'de>,
{
    #[derive(serde::Deserialize)]
    #[serde(untagged)]
    enum Stored {
        Known(HomeLayout),
        Other(serde::de::IgnoredAny),
    }
    Ok(match Stored::deserialize(deserializer)? {
        Stored::Known(layout) => layout,
        Stored::Other(_) => HomeLayout::default(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[derive(serde::Deserialize)]
    struct Holder {
        #[serde(deserialize_with = "deserialize_layout", default)]
        layout: HomeLayout,
    }

    fn load(json: &str) -> HomeLayout {
        serde_json::from_str::<Holder>(json).expect("parse").layout
    }

    #[test]
    fn stored_layout_names_round_trip_and_unknown_values_fall_back() {
        assert_eq!(load(r#"{"layout":"classic"}"#), HomeLayout::Classic);
        assert_eq!(load(r#"{"layout":"retired-layout"}"#), HomeLayout::Classic);
        assert_eq!(load(r#"{"layout":42}"#), HomeLayout::Classic);
        assert_eq!(load("{}"), HomeLayout::Classic);
        assert_eq!(
            serde_json::to_string(&HomeLayout::Classic).expect("serialize"),
            r#""classic""#
        );
    }

    #[test]
    fn no_mirror_yields_the_requested_variant_empty() {
        let HomeSnapshot::Classic { home } =
            snapshot(HomeLayout::Classic, None, &Settings::default());
        assert_eq!(home, ClassicHome::default());
    }
}
