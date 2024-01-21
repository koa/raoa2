use indexed_db_futures::{
    prelude::IdbOpenDbRequestLike, request::OpenDbRequest, IdbDatabase, IdbQuerySource,
    IdbVersionChangeEvent,
};
use serde::{Deserialize, Serialize};
use thiserror::Error;
use wasm_bindgen::JsValue;
use web_sys::{DomException, IdbTransactionMode};

use crate::{
    data::server_api::graphql::model::DateTime,
    error::{DomError, FrontendError},
};

const INDEX_DB_NAME: &str = "photos";
const ALBUM_LIST: &str = "albums";

#[derive(Debug)]
pub struct StorageAccess {
    db: IdbDatabase,
}

#[derive(Debug, Error)]
pub enum StorageError {
    #[error("Error from index db: {0}")]
    IndexDb(DomError),
    #[error("Error serializing to JSValue: {0}")]
    SerdeWasmBindgen(#[from] serde_wasm_bindgen::Error),
}
impl From<DomException> for StorageError {
    fn from(exception: DomException) -> Self {
        StorageError::IndexDb(exception.into())
    }
}

impl StorageAccess {
    pub async fn new() -> Result<StorageAccess, StorageError> {
        let mut db_req: OpenDbRequest = IdbDatabase::open_u32(INDEX_DB_NAME, 1)?;
        db_req.set_on_upgrade_needed(Some(|evt: &IdbVersionChangeEvent| -> Result<(), JsValue> {
            // Check if the object store exists; create it if it doesn't
            if !evt.db().object_store_names().any(|n| n == ALBUM_LIST) {
                evt.db().create_object_store(ALBUM_LIST)?;
            }
            Ok(())
        }));
        Ok(StorageAccess { db: db_req.await? })
    }
    pub async fn list_albums(&self) -> Result<Box<[AlbumDetails]>, StorageError> {
        let tx = self
            .db
            .transaction_on_one_with_mode(ALBUM_LIST, IdbTransactionMode::Readonly)?;
        let album_list_index = tx.object_store(ALBUM_LIST)?;
        let albums = album_list_index
            .get_all()?
            .await?
            .into_iter()
            .map(serde_wasm_bindgen::from_value::<AlbumDetails>)
            .collect::<Result<_, _>>()?;
        Ok(albums)
    }
    pub async fn store_album(&self, data: AlbumDetails) -> Result<(), StorageError> {
        let tx = self
            .db
            .transaction_on_one_with_mode(ALBUM_LIST, IdbTransactionMode::Readwrite)?;
        let store = tx.object_store(ALBUM_LIST)?;
        let value = serde_wasm_bindgen::to_value(&data)?;
        store.put_key_val_owned(data.id.as_ref(), &value)?;
        Ok(())
    }
    pub async fn get_album_by_id(&self, id: &str) -> Result<Option<AlbumDetails>, FrontendError> {
        let tx = self
            .db
            .transaction_on_one_with_mode(ALBUM_LIST, IdbTransactionMode::Readwrite)?;
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
}

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
    pub fn id(&self) -> &str {
        &self.id
    }
    pub fn name(&self) -> &str {
        &self.name
    }
    pub fn version(&self) -> &str {
        &self.version
    }

    pub fn timestamp(&self) -> Option<&DateTime> {
        self.timestamp.as_ref()
    }

    pub fn entry_count(&self) -> u32 {
        self.entry_count
    }
    pub fn fnch_album_id(&self) -> Option<&str> {
        self.fnch_album_id.as_deref()
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
