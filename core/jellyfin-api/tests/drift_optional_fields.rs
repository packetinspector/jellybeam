//! Drift tolerance for response fields the `OpenAPI` spec marks `required` but a server can omit
//! (`codegen/DRIFT.md`): the same page-survives-drift contract `lib.rs` pins for enum drift.
use jellyfin_api::models::BaseItemDto;

/// `UserItemDataDto.Key` is spec-`required` but codegen relaxes it to optional: a server that
/// omits it must not fail deserializing the item (or, in a page, the whole page).
#[test]
fn base_item_dto_with_user_data_missing_key_deserializes() {
    let raw = include_str!("fixtures/base_item_dto_user_data_missing_key.json");
    let item: BaseItemDto = serde_json::from_str(raw).expect("deserializes despite missing Key");
    let user_data = item.user_data.expect("UserData present");
    assert_eq!(user_data.key, None);
    assert_eq!(user_data.played, Some(false));
}
