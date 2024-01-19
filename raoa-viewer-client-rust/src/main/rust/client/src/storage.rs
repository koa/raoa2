use indexed_db_futures::{
    prelude::IdbOpenDbRequestLike, request::OpenDbRequest, IdbDatabase, IdbQuerySource,
    IdbVersionChangeEvent,
};
use serde::{Deserialize, Serialize};
use wasm_bindgen::JsValue;
use web_sys::IdbTransactionMode;

use crate::error::FrontendError;
use crate::server_api::graphql::model::DateTime;

const INDEX_DB_NAME: &'static str = "photos";
const ALBUM_LIST: &str = "albums";

#[derive(Serialize, Deserialize, Debug, Clone, Eq, PartialEq)]
pub struct AlbumDetails {
    id: Box<str>,
    name: Box<str>,
    version: Box<str>,
    timestamp: Option<DateTime>,
    entry_count: u32,
    fnch_album_id: Option<Box<str>>,
}

impl AlbumDetails {
    pub fn id(&self) -> &Box<str> {
        &self.id
    }
    pub fn name(&self) -> &Box<str> {
        &self.name
    }
    pub fn version(&self) -> &Box<str> {
        &self.version
    }

    pub fn timestamp(&self) -> &Option<DateTime> {
        &self.timestamp
    }

    pub fn entry_count(&self) -> u32 {
        self.entry_count
    }
    pub fn fnch_album_id(&self) -> &Option<Box<str>> {
        &self.fnch_album_id
    }
    pub fn new(
        id: Box<str>,
        name: Box<str>,
        version: Box<str>,
        timestamp: Option<DateTime>,
        entry_count: u32,
        fnch_album_id: Option<Box<str>>,
    ) -> Self {
        Self {
            id,
            name,
            version,
            timestamp,
            entry_count,
            fnch_album_id,
        }
    }
}

pub async fn list_albums() -> Result<Box<[AlbumDetails]>, FrontendError> {
    let db = get_database().await?;

    let tx = db.transaction_on_one_with_mode(ALBUM_LIST, IdbTransactionMode::Readonly)?;
    let album_list_index = tx.object_store(ALBUM_LIST)?;
    let albums = album_list_index
        .get_all_keys_with_limit(u32::MAX)?
        .await?
        .into_iter()
        .map(|value| {
            serde_wasm_bindgen::from_value::<AlbumDetails>(value)
                .expect("Error reading album entry")
        })
        .collect();

    Ok(albums)
}
pub async fn store_album(data: AlbumDetails) -> Result<(), FrontendError> {
    let database = get_database().await?;
    let tx = database.transaction_on_one_with_mode(ALBUM_LIST, IdbTransactionMode::Readwrite)?;
    let store = tx.object_store(ALBUM_LIST)?;
    let value = serde_wasm_bindgen::to_value(&data)?;
    store.put_key_val_owned(data.id.as_ref(), &value)?;
    Ok(())
}
pub async fn get_album_by_id(id: &str) -> Result<Option<AlbumDetails>, FrontendError> {
    let database = get_database().await?;
    let tx = database.transaction_on_one_with_mode(ALBUM_LIST, IdbTransactionMode::Readwrite)?;
    let store = tx.object_store(ALBUM_LIST)?;

    match store
        .get(&JsValue::from_str(id))?
        .await?
        .map(serde_wasm_bindgen::from_value::<AlbumDetails>)
    {
        None => Ok(None),
        Some(Ok(value)) => Ok(Some(value)),
        Some(Err(error)) => Err(error.into()),
    }
}

async fn get_database() -> Result<IdbDatabase, FrontendError> {
    let mut db_req: OpenDbRequest = IdbDatabase::open_u32(INDEX_DB_NAME, 1)?;
    db_req.set_on_upgrade_needed(Some(|evt: &IdbVersionChangeEvent| -> Result<(), JsValue> {
        // Check if the object store exists; create it if it doesn't
        if !evt.db().object_store_names().any(|n| n == ALBUM_LIST) {
            evt.db().create_object_store(ALBUM_LIST)?;
        }
        Ok(())
    }));
    Ok(db_req.await?)
}
