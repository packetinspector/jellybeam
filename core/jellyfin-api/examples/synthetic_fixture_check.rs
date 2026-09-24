//! Validate generated stress-server JSON against the app's actual wire model.
use std::io::Read;

fn main() {
    let mut json = String::new();
    std::io::stdin()
        .read_to_string(&mut json)
        .expect("read synthetic fixture from stdin");
    match serde_json::from_str::<jellyfin_api::models::BaseItemDtoQueryResult>(&json) {
        Ok(_) => println!("Synthetic fixture schema valid"),
        Err(error) => {
            eprintln!("Synthetic fixture schema invalid: {error}");
            std::process::exit(1);
        }
    }
}
