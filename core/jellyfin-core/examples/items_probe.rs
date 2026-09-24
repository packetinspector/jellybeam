//! One-off diagnostic: server-reported episode list (id / name / index / LocationType).
//!
//!   cargo run -p jellyfin-core --example items_probe -- <server-host> <parent_id>

use jellyfin_api::{ClientIdentity, ItemQuery, JellyfinClient};

fn identity() -> ClientIdentity {
    ClientIdentity {
        client: "Jellybeam Items Probe".to_string(),
        device: "items-probe".to_string(),
        device_id: "jellybeam-items-probe".to_string(),
        version: "0.1.0".to_string(),
    }
}

fn main() {
    let filter = std::env::args().nth(1).expect("server filter arg");
    let parent = std::env::args().nth(2).expect("parent id arg");
    let home = std::env::var("HOME").expect("HOME");
    let path = format!("{home}/Library/Application Support/Jellybeam/dev-session-list.json");
    let raw = std::fs::read_to_string(&path).expect("read session store");
    let parsed: serde_json::Value = serde_json::from_str(&raw).expect("parse");
    let s = parsed["sessions"]
        .as_array()
        .expect("sessions")
        .iter()
        .find(|s| s["base_url"].as_str().unwrap_or("").contains(&filter))
        .expect("no matching session");
    let client = JellyfinClient::from_token(
        s["base_url"].as_str().expect("base_url"),
        identity(),
        s["token"].as_str().expect("token"),
    )
    .with_user_id(s["user_id"].as_str().expect("user_id"));

    let rt = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .expect("rt");
    rt.block_on(async move {
        let page = client
            .get_items(&ItemQuery {
                parent_id: Some(parent),
                recursive: true,
                include_item_types: vec!["Episode".to_string()],
                fields: vec!["LocationTypes".to_string()],
                limit: 100,
                ..ItemQuery::new()
            })
            .await
            .expect("items");
        for it in &page.items {
            println!(
                "{} | S{:?}E{:?} | {:?} | location={:?}",
                it.id.map(|u| u.to_string()).unwrap_or_default(),
                it.parent_index_number,
                it.index_number,
                it.name,
                it.location_type,
            );
        }
        println!("total: {}", page.items.len());
    });
}
