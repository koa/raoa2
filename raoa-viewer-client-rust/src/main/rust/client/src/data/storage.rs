use std::time::Duration;

use log::info;
use ordered_float::OrderedFloat;
use rexie::{Index, KeyRange, ObjectStore, Rexie, TransactionMode};
use serde::{Deserialize, Serialize};
use thiserror::Error;
use wasm_bindgen::JsValue;

use crate::data::server_api::graphql::model::DateTime;

const INDEX_DB_NAME: &str = "photos";
const ALBUM_LIST: &str = "albums";
const ALBUM_ENTRIES: &str = "album_entries";
const ALBUM_ENTRIES_IDX_ALBUM: &str = "album";

#[derive(Debug)]
pub struct StorageAccess {
    db: Rexie,
}

#[derive(Debug, Error)]
pub enum StorageError {
    #[error("Error from index db: {0}")]
    IndexDb(#[from] rexie::Error),
    #[error("Error serializing to JSValue: {0}")]
    SerdeWasmBindgen(#[from] serde_wasm_bindgen::Error),
}

impl StorageAccess {
    pub async fn new() -> Result<StorageAccess, StorageError> {
        let db = Rexie::builder(INDEX_DB_NAME)
            .version(1)
            .add_object_store(ObjectStore::new(ALBUM_LIST).key_path("id"))
            .add_object_store(
                ObjectStore::new(ALBUM_ENTRIES)
                    .key_path("entry_id")
                    .add_index(Index::new(ALBUM_ENTRIES_IDX_ALBUM, "album_id")),
            )
            .build()
            .await?;
        Ok(StorageAccess { db })
    }
    pub async fn list_albums(&self) -> Result<Box<[AlbumDetails]>, StorageError> {
        let tx = self
            .db
            .transaction(&[ALBUM_LIST], TransactionMode::ReadOnly)?;
        let album_list_index = tx.store(ALBUM_LIST)?;
        let albums = album_list_index
            .get_all(None, None, None, None)
            .await?
            .into_iter()
            .map(|(_, v)| v)
            .map(serde_wasm_bindgen::from_value::<AlbumDetails>)
            .collect::<Result<_, _>>()?;
        Ok(albums)
    }
    pub async fn store_album(&self, data: AlbumDetails) -> Result<(), StorageError> {
        let tx = self
            .db
            .transaction(&[ALBUM_LIST], TransactionMode::ReadWrite)?;
        let store = tx.store(ALBUM_LIST)?;
        let v = serde_wasm_bindgen::to_value(&data)?;
        store.put(&v, None).await?;
        Ok(())
    }
    pub async fn remove_album(&self, id: &str) -> Result<(), StorageError> {
        let tx = self
            .db
            .transaction(&[ALBUM_LIST, ALBUM_ENTRIES], TransactionMode::ReadWrite)?;
        let entries_store = tx.store(ALBUM_ENTRIES)?;
        let key_value = &JsValue::from_str(id);
        let key = KeyRange::only(key_value)?;
        loop {
            let entries = entries_store
                .index(ALBUM_ENTRIES_IDX_ALBUM)?
                .get_all(Some(&key), Some(100), Some(0), None)
                .await?;
            if entries.is_empty() {
                break;
            }
            info!("Remove {} entries", { entries.len() });
            for (key, _) in entries {
                entries_store.delete(&key).await?;
            }
        }
        let store = tx.store(ALBUM_LIST)?;
        store.delete(key_value).await?;

        Ok(())
    }
    pub async fn get_album_by_id(&self, id: &str) -> Result<Option<AlbumDetails>, StorageError> {
        let tx = self
            .db
            .transaction(&[ALBUM_LIST], TransactionMode::ReadWrite)?;
        let store = tx.store(ALBUM_LIST)?;

        let entry_value = store.get(&JsValue::from_str(id)).await?;
        if entry_value.is_object() {
            Ok(Some(serde_wasm_bindgen::from_value(entry_value)?))
        } else {
            Ok(None)
        }
    }
    pub async fn list_album_entries(
        &self,
        album_id: &str,
    ) -> Result<Box<[AlbumEntry]>, StorageError> {
        let tx = self
            .db
            .transaction(&[ALBUM_ENTRIES], TransactionMode::ReadOnly)?;
        let store = tx.store(ALBUM_ENTRIES)?;
        let index = store.index(ALBUM_ENTRIES_IDX_ALBUM)?;
        let key_value = &JsValue::from_str(album_id);
        let range = KeyRange::only(key_value)?;
        let key = Some(&range);

        let total_amount = index.count(key).await?;
        let mut ret = Vec::with_capacity(total_amount as usize);
        for (_, entry) in index.get_all(key, None, None, None).await? {
            ret.push(serde_wasm_bindgen::from_value(entry)?);
        }
        Ok(ret.into_boxed_slice())
    }
    pub async fn store_album_entries(
        &self,
        entries: impl IntoIterator<Item = AlbumEntry>,
    ) -> Result<(), StorageError> {
        let tx = self
            .db
            .transaction(&[ALBUM_ENTRIES], TransactionMode::ReadWrite)?;
        let store = tx.store(ALBUM_ENTRIES)?;
        for entry in entries {
            let value = serde_wasm_bindgen::to_value(&entry)?;
            store.put(&value, None).await?;
        }
        Ok(())
    }
    pub async fn remove_album_entries(
        &self,
        entries: impl IntoIterator<Item = &AlbumEntry>,
    ) -> Result<(), StorageError> {
        let tx = self
            .db
            .transaction(&[ALBUM_ENTRIES], TransactionMode::ReadWrite)?;
        let store = tx.store(ALBUM_ENTRIES)?;
        for entry in entries {
            store.delete(&JsValue::from_str(&entry.entry_id)).await?;
        }
        Ok(())
    }
}

#[derive(Serialize, Deserialize, Debug, Clone, Eq, PartialEq)]
pub struct AlbumDetails {
    pub id: Box<str>,
    pub name: Box<str>,
    pub version: Box<str>,
    pub timestamp: Option<DateTime>,
    pub entry_count: u32,
    pub fnch_album_id: Option<Box<str>>,
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

#[derive(Serialize, Deserialize, Debug, Clone, Eq, PartialEq)]
pub struct AlbumEntry {
    pub album_id: Box<str>,
    pub entry_id: Box<str>,
    pub name: Box<str>,
    pub target_width: u32,
    pub target_height: u32,
    pub created: Option<DateTime>,
    pub keywords: Box<[Box<str>]>,
    pub camera_model: Option<Box<str>>,
    pub exposure_time: Option<u64>,
    pub f_number: Option<OrderedFloat<f32>>,
    pub focal_length_35: Option<OrderedFloat<f32>>,
    pub iso_speed_ratings: Option<OrderedFloat<f32>>,
}

impl AlbumEntry {
    pub fn album_id(&self) -> &str {
        &self.album_id
    }
    pub fn entry_id(&self) -> &str {
        &self.entry_id
    }
    pub fn name(&self) -> &str {
        &self.name
    }
    pub fn target_width(&self) -> u32 {
        self.target_width
    }
    pub fn target_height(&self) -> u32 {
        self.target_height
    }
    pub fn created(&self) -> Option<&DateTime> {
        self.created.as_ref()
    }
    pub fn keywords(&self) -> &[Box<str>] {
        &self.keywords
    }
    pub fn camera_model(&self) -> Option<&str> {
        self.camera_model.as_deref()
    }
    pub fn exposure_time(&self) -> Option<Duration> {
        self.exposure_time.map(|t| Duration::from_nanos(t))
    }
    pub fn f_number(&self) -> Option<OrderedFloat<f32>> {
        self.f_number
    }
    pub fn focal_length_35(&self) -> Option<OrderedFloat<f32>> {
        self.focal_length_35
    }
    pub fn iso_speed_ratings(&self) -> Option<OrderedFloat<f32>> {
        self.iso_speed_ratings
    }
    pub fn new(
        album_id: Box<str>,
        entry_id: Box<str>,
        name: Box<str>,
        target_width: u32,
        target_height: u32,
        created: Option<DateTime>,
        keywords: Box<[Box<str>]>,
        camera_model: Option<Box<str>>,
        exposure_time: Option<Duration>,
        f_number: Option<OrderedFloat<f32>>,
        focal_length_35: Option<OrderedFloat<f32>>,
        iso_speed_ratings: Option<OrderedFloat<f32>>,
    ) -> Self {
        Self {
            album_id,
            entry_id,
            name,
            target_width,
            target_height,
            created,
            keywords,
            camera_model,
            exposure_time: exposure_time.map(|d| d.as_nanos() as u64),
            f_number,
            focal_length_35,
            iso_speed_ratings,
        }
    }
}
#[cfg(test)]
mod test {
    use std::time::Duration;

    use crate::data::storage::AlbumEntry;

    #[test]
    fn test_serialize_duration() {
        let original_duration = Some(Duration::from_nanos(625000));
        let duration_as_string = serde_json::to_string(&original_duration).unwrap();
        //println!("Duration: {duration_as_string}");
        let copied_duration: Option<Duration> = serde_json::from_str(&duration_as_string).unwrap();
        assert_eq!(original_duration, copied_duration);
        //println!("Restored: {copied_duration:?}");
    }
    #[test]
    fn test_serialize_album_entry() {
        let original_entry = [AlbumEntry {
            album_id: "aid".to_string().into_boxed_str(),
            entry_id: "eid".to_string().into_boxed_str(),
            name: "my big picture".to_string().into_boxed_str(),
            target_width: 0,
            target_height: 0,
            created: None,
            keywords: Box::new([]),
            camera_model: None,
            exposure_time: Some(Duration::from_nanos(625000).as_nanos() as u64),
            f_number: None,
            focal_length_35: None,
            iso_speed_ratings: None,
        }];
        let entry_as_string = serde_json::to_string(&original_entry).unwrap();
        //println!("Duration: {entry_as_string}");
        let copied_entry: [AlbumEntry; 1] = serde_json::from_str(&entry_as_string).unwrap();
        //println!("Restored: {copied_entry:?}");
        assert_eq!(original_entry, copied_entry);
    }
}
