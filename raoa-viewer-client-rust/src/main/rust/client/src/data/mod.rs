use gloo::{
    file::ObjectUrl,
    storage::{LocalStorage, Storage},
};
use google_signin_client::{
    initialize, prompt_async, render_button, ButtonType, DismissedReason, GsiButtonConfiguration,
    IdConfiguration, PromptResult,
};
use log::{error, info, warn};
use lru::LruCache;
use ordered_float::OrderedFloat;
use patternfly_yew::prelude::{Alert, AlertGroup, AlertType};
use std::collections::HashSet;
use std::num::NonZero;
use std::sync::Arc;
use std::{
    borrow::Cow,
    collections::HashMap,
    fmt::{Debug, Formatter},
    ops::Deref,
    rc::Rc,
    time::Duration,
};
use thiserror::Error;
use tokio::sync::{mpsc, watch, Mutex, MutexGuard};
use tokio_stream::{wrappers::ReceiverStream, Stream};
use wasm_bindgen::JsValue;
use wasm_bindgen_futures::JsFuture;
use web_sys::{window, Blob, Cache, HtmlElement, Navigator, Response, Window};
use yew::{html, platform::spawn_local, Html, NodeRef};

use crate::{
    data::{
        server_api::{
            fetch_blob,
            graphql::{
                model::{
                    album_content, all_album_versions, get_album_details,
                    get_album_details::GetAlbumDetailsAlbumByIdLabels, AlbumContent,
                    AllAlbumVersions, GetAlbumDetails,
                },
                query, GraphqlAccessError,
            },
            thumbnail_url,
        },
        session::UserSessionData,
        storage::{AlbumDetails, AlbumEntry, StorageAccess, StorageError},
    },
    error::FrontendError,
};

pub mod server_api;
pub mod session;
pub mod storage;
#[derive(Clone)]
pub struct MediaUrl(ObjectUrl);

impl Deref for MediaUrl {
    type Target = ObjectUrl;

    fn deref(&self) -> &Self::Target {
        &self.0
    }
}

impl From<ObjectUrl> for MediaUrl {
    fn from(value: ObjectUrl) -> Self {
        MediaUrl(value)
    }
}

impl PartialEq for MediaUrl {
    fn eq(&self, other: &Self) -> bool {
        self.0.to_string().eq(&other.0.to_string())
    }
}

impl Debug for MediaUrl {
    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
        write!(f, "MediaUrl: {}", self.0.to_string())
    }
}

#[derive(Debug)]
pub struct DataAccess {
    storage: Mutex<StorageAccess>,
    login_data: watch::Receiver<Option<UserSessionData>>,
    login_data_updater: mpsc::Sender<Option<UserSessionData>>,
    login_button_ref: NodeRef,
    cache_storage: Cache,
    client_id: Box<str>,
    thumbnails: Mutex<LruCache<Box<str>, Arc<Mutex<MediaUrl>>>>,
}

impl PartialEq for DataAccess {
    fn eq(&self, other: &Self) -> bool {
        *self.login_data.borrow() == *other.login_data.borrow()
    }
}

impl DataAccess {
    pub async fn new(
        login_button_ref: NodeRef,
        google_client_id: impl ToString,
    ) -> Result<Rc<DataAccess>, DataAccessError> {
        let caches = JsFuture::from(window().unwrap().caches().unwrap().open("thumbnails"))
            .await
            .unwrap()
            .into();
        let (watch_sender, login_data) = watch::channel(None);
        let (login_data_updater, mut ch_receiver) = mpsc::channel(3);
        spawn_local(async move {
            while let Some(data) = ch_receiver.recv().await {
                if let Err(e) = watch_sender.send(data) {
                    error!("Cannot send message {}", e);
                    break;
                }
            }
        });
        Ok(Rc::new(Self {
            storage: Mutex::new(
                StorageAccess::new()
                    .await
                    .map_err(DataAccessError::Initialization)?,
            ),
            login_data,
            login_data_updater,
            login_button_ref,
            cache_storage: caches,
            client_id: google_client_id.to_string().into(),
            thumbnails: Mutex::new(LruCache::new(
                NonZero::new(100).expect("should be bigger than 0"),
            )),
        }))
    }

    pub async fn storage(&self) -> MutexGuard<'_, StorageAccess> {
        self.storage.lock().await
    }
    pub fn login_data(&self) -> &watch::Receiver<Option<UserSessionData>> {
        &self.login_data
    }
    pub async fn update_token(&self, token: impl Into<Box<str>>) {
        info!("Update token");
        let data = UserSessionData::new(token.into());
        if let Err(e) = self.login_data_updater.send(Some(data)).await {
            error!("Channel closed: {e}");
        }
    }

    pub fn fetch_albums_interactive(
        self: Rc<Self>,
    ) -> impl Stream<Item = DataFetchMessage<Box<[AlbumDetails]>>> {
        let (tx, rx) = mpsc::channel(10);

        spawn_local(async move {
            match self.do_fetch_albums_interactive(tx.clone()).await {
                Err(DoFetchError::ExternalError(e)) => {
                    tx.send(DataFetchMessage::Error(e))
                        .await
                        .expect("Cannot send message");
                }
                Err(DoFetchError::StreamError(e)) => {
                    error!("Cannot send message: {e}");
                }
                Ok(()) => {}
            };
        });
        ReceiverStream::new(rx)
    }
    async fn valid_token_str(&self) -> Option<Box<str>> {
        self.valid_user_session()
            .await
            .map(|s| s.jwt().to_string().into_boxed_str())
    }

    async fn do_fetch_albums_interactive(
        &self,
        tx: mpsc::Sender<DataFetchMessage<Box<[AlbumDetails]>>>,
    ) -> Result<(), DoFetchError<Box<[AlbumDetails]>>> {
        let stored_albums = self
            .storage()
            .await
            .list_albums()
            .await
            .map_err(DataAccessError::FetchAlbum)?;
        tx.send(DataFetchMessage::Data(stored_albums.clone()))
            .await?;
        if let Some(token) = self.valid_token_str().await {
            let mut existing_albums = stored_albums
                .iter()
                .map(|e| (e.id(), e))
                .collect::<HashMap<_, _>>();
            let responses =
                query::<AllAlbumVersions>(&token, all_album_versions::Variables {}).await?;
            let album_count = responses.list_albums.len();
            let mut all_albums = Vec::with_capacity(album_count);
            for (idx, album) in responses.list_albums.iter().enumerate() {
                if let Some(found_entry) = existing_albums
                    .remove(album.version.as_str())
                    .filter(|entry| entry.version() == album.version.as_str())
                {
                    all_albums.push(found_entry.clone());
                } else {
                    tx.send(DataFetchMessage::Progress(idx as f64 / album_count as f64))
                        .await?;
                    let album_id = &album.id;
                    let fetched = self.fetch_album_by_id(&token, album_id).await?;
                    if let Some(entry) = fetched {
                        all_albums.push(entry);
                    }
                }
            }
            tx.send(DataFetchMessage::Data(all_albums.into_boxed_slice()))
                .await?;
            for id in existing_albums.keys() {
                self.storage()
                    .await
                    .remove_album(&id)
                    .await
                    .map_err(DataAccessError::FetchAlbum)?;
            }
        }
        Ok(())
    }

    async fn fetch_album_by_id(
        &self,
        token: &str,
        album_id: &str,
    ) -> Result<Option<AlbumDetails>, DataAccessError> {
        let details = query::<GetAlbumDetails>(
            &token,
            get_album_details::Variables {
                album_id: album_id.to_string(),
            },
        )
        .await?;
        let fetched = if let Some(album_data) = details.album_by_id {
            let mut fnch_album_id = None;
            for GetAlbumDetailsAlbumByIdLabels {
                label_name,
                label_value,
            } in album_data.labels
            {
                if label_name.as_str() == "fnch-competition_id" {
                    fnch_album_id = Some(label_value.into_boxed_str())
                }
            }
            let title_entry = album_data.title_entry.map(|e| AlbumEntry {
                album_id: album_id.to_string().into_boxed_str(),
                entry_id: e.id.into_boxed_str(),
                name: e.name.map(|name| name.into_boxed_str()).unwrap_or_default(),
                target_width: e.target_width.unwrap_or_default() as u32,
                target_height: e.target_height.unwrap_or_default() as u32,
                created: e.created,
                keywords: e.keywords.into_iter().map(|k| k.into_boxed_str()).collect(),
                camera_model: e.camera_model.map(|s| s.into_boxed_str()),
                exposure_time: e
                    .exposure_time
                    .map(|v| Duration::from_secs_f32(v as f32).as_nanos() as u64),
                f_number: e.f_number.map(|v| OrderedFloat(v as f32)),
                focal_length_35: e.focal_length35.map(|v| OrderedFloat(v as f32)),
                iso_speed_ratings: e.iso_speed_ratings.map(|v| OrderedFloat(v as f32)),
            });
            let entry = AlbumDetails::new(
                album_data.id.into_boxed_str(),
                album_data.name.unwrap_or_default().into_boxed_str(),
                album_data.version.into_boxed_str(),
                album_data.album_time,
                album_data.entry_count.map(|i| i as u32).unwrap_or(0),
                fnch_album_id, /* std::option::Option<Box<str>> */
                title_entry,
            );
            self.storage()
                .await
                .store_album(entry.clone())
                .await
                .map_err(DataAccessError::StoreAlbum)?;
            Some(entry)
        } else {
            None
        };
        Ok(fetched)
    }

    pub fn is_token_valid(&self) -> bool {
        self.login_data
            .borrow()
            .as_ref()
            .map(|s| s.is_token_valid())
            .unwrap_or(false)
    }
    pub async fn album_data(&self, id: &str) -> Result<Option<AlbumDetails>, DataAccessError> {
        let data = self
            .storage()
            .await
            .get_album_by_id(id)
            .await
            .map_err(DataAccessError::FetchAlbum)?;
        if data.is_some() {
            return Ok(data);
        }
        if let Some(token) = self.valid_token_str().await {
            self.fetch_album_by_id(token.as_ref(), id).await
        } else {
            Ok(None)
        }
    }
    async fn valid_user_session(&self) -> Option<UserSessionData> {
        if let Ok(token) = LocalStorage::get::<Box<str>>("jwt") {
            let current_session = UserSessionData::new(token);
            let login_updater = self.login_data_updater.clone();
            if current_session.is_token_valid() {
                if let Err(e) = login_updater.send(Some(current_session.clone())).await {
                    error!("Cannot login: {e}");
                }
                return Some(current_session);
            }
        }
        // hide login button (if still visible)
        if let Some(login_button_ref) = self.login_button_ref.cast::<HtmlElement>() {
            login_button_ref.set_hidden(true);
        }
        let online = window()
            .as_ref()
            .map(Window::navigator)
            .as_ref()
            .map(Navigator::on_line)
            .unwrap_or(false);
        if !online {
            return None;
        }

        let login_data_receiver = self.login_data();
        let session_data = login_data_receiver.borrow();
        if let Some(session) = &*session_data {
            if session.is_token_valid() {
                return Some(session.clone());
            }
        }
        drop(session_data);

        let mut configuration = IdConfiguration::new(self.client_id.clone().into_string());
        //configuration.set_auto_select(true);

        let login_updater = self.login_data_updater.clone();
        configuration.set_callback(Box::new(move |response| {
            let login_updater = login_updater.clone();
            spawn_local(async move {
                let jwt_token_as_string = response.credential().to_string().into_boxed_str();
                if let Err(e) = LocalStorage::set("jwt", jwt_token_as_string.clone()) {
                    error!("Cannot update locale storage: {e}");
                }
                if let Err(e) = login_updater
                    .send(Some(UserSessionData::new(jwt_token_as_string)))
                    .await
                {
                    error!("Cannot login: {e}");
                }
            })
        }));
        initialize(configuration);
        spawn_local(async move {
            let result = prompt_async().await;
            if result != PromptResult::Dismissed(DismissedReason::CredentialReturned) {
                //link.send_message(AppMessage::LoginFailed(result))
            }
        });

        let result = prompt_async().await;
        if result == PromptResult::Dismissed(DismissedReason::CredentialReturned) {
            let session_data = login_data_receiver.borrow();
            if let Some(session) = &*session_data {
                if session.is_token_valid() {
                    return Some(session.clone());
                }
            }
            drop(session_data);
        }
        if let Some(login_button_ref) = self
            .login_button_ref
            .cast::<HtmlElement>()
            .map(AutoHideHtml::new)
        {
            render_button(
                login_button_ref.0.clone(),
                GsiButtonConfiguration::new(ButtonType::Icon),
            );
            let mut ref_mut = self.login_data.clone();
            let data = ref_mut.wait_for(|data| data.is_some()).await;
            match data {
                Ok(d) => {
                    if let Some(session) = d.deref() {
                        return Some(session.clone());
                    }
                    error!("No session data found");
                }
                Err(e) => {
                    error!("Cannot wait for session data: {e}");
                }
            }
        }

        None
    }
    pub fn fetch_album_content_interactive(
        self: Rc<Self>,
        id: &str,
    ) -> impl Stream<Item = DataFetchMessage<Box<[AlbumEntry]>>> {
        let (tx, rx) = mpsc::channel(10);
        let s_clone = self.clone();
        let id = id.to_string();

        spawn_local(async move {
            match s_clone
                .do_fetch_album_entries_interactive(tx.clone(), id)
                .await
            {
                Err(DoFetchError::ExternalError(e)) => {
                    tx.send(DataFetchMessage::Error(e))
                        .await
                        .expect("Cannot send message");
                }
                Err(DoFetchError::StreamError(e)) => {
                    error!("Cannot send message: {e}");
                }
                Ok(()) => {}
            };
        });
        ReceiverStream::new(rx)
    }
    async fn do_fetch_album_entries_interactive(
        &self,
        tx: mpsc::Sender<DataFetchMessage<Box<[AlbumEntry]>>>,
        id: String,
    ) -> Result<(), DoFetchError<Box<[AlbumEntry]>>> {
        let result = self.storage().await.list_album_entries(&id).await;
        let stored_entries = result.map_err(DataAccessError::FetchAlbum)?.clone();
        tx.send(DataFetchMessage::Data(stored_entries.clone()))
            .await?;
        if let Some(token) = self.valid_token_str().await {
            let mut existing_entries = stored_entries
                .into_iter()
                .map(|e| (e.entry_id(), e))
                .collect::<HashMap<_, _>>();
            let responses = query::<AlbumContent>(
                &token,
                album_content::Variables {
                    album_id: id.clone(),
                },
            )
            .await
            .map_err(DataAccessError::GraphqlAlbumContent)?;
            let entries = responses
                .album_by_id
                .as_ref()
                .map(|a| a.entries.as_slice())
                .unwrap_or_default();
            let entry_count = entries.len();
            let mut found_entries = Vec::with_capacity(entry_count);
            let mut modified_entries = Vec::with_capacity(entry_count);
            let step_size = 1.max(entry_count / 100);
            for (idx, entry) in entries.iter().enumerate() {
                if idx % step_size == 0 {
                    tx.send(DataFetchMessage::Progress(idx as f64 / entry_count as f64))
                        .await?;
                }
                let entry_id = entry.id.clone().into_boxed_str();
                let existing_entry = existing_entries.remove(entry_id.as_ref());
                let entry = AlbumEntry {
                    album_id: id.clone().into_boxed_str(),
                    entry_id,
                    name: entry.name.clone().unwrap_or_default().into_boxed_str(),
                    target_width: entry.target_width.map(|i| i as u32).unwrap_or_default(),
                    target_height: entry.target_height.map(|i| i as u32).unwrap_or_default(),
                    created: entry.created.clone(),
                    keywords: entry
                        .keywords
                        .iter()
                        .map(|kw| kw.clone().into_boxed_str())
                        .collect(),
                    camera_model: entry.camera_model.clone().map(|s| s.into_boxed_str()),
                    exposure_time: entry
                        .exposure_time
                        .map(|v| Duration::from_secs_f32(v as f32).as_nanos() as u64),
                    f_number: entry.f_number.map(|v| OrderedFloat(v as f32)),
                    focal_length_35: entry.focal_length35.map(|v| OrderedFloat(v as f32)),
                    iso_speed_ratings: entry.iso_speed_ratings.map(|v| OrderedFloat(v as f32)),
                };
                if Some(&entry) != existing_entry {
                    modified_entries.push(entry.clone());
                }
                found_entries.push(entry);
            }
            tx.send(DataFetchMessage::Data(found_entries.into_boxed_slice()))
                .await?;

            if !modified_entries.is_empty() {
                self.storage()
                    .await
                    .store_album_entries(modified_entries)
                    .await
                    .map_err(DataAccessError::FetchAlbum)?;
            }
            if !existing_entries.is_empty() {
                self.storage()
                    .await
                    .remove_album_entries(existing_entries.values().copied())
                    .await
                    .map_err(DataAccessError::FetchAlbum)?;
            }
        }
        Ok(())
    }
    pub async fn fetch_thumbnail(
        &self,
        entry: &AlbumEntry,
        max_length: Option<u16>,
    ) -> Result<Arc<Mutex<MediaUrl>>, FrontendError> {
        //info!("Fetch length {max_length:?}");
        let entry_path = thumbnail_url(&entry.album_id, &entry.entry_id, max_length);
        let mut read_lock = self.thumbnails.lock().await;
        if let Some(url) = read_lock.get(entry_path.as_str()) {
            //info!("Found in cache");
            return Ok(url.clone());
        }
        drop(read_lock);
        //info!("Cache miss: {}", entry_path);

        let result = JsFuture::from(self.cache_storage.match_with_str(&entry_path))
            .await
            .map_err(js_to_frontend_error)?;
        if !result.is_undefined() {
            let media_url = extract_blob_from_result(result)
                .await
                .map_err(js_to_frontend_error)?;
            let arc = Arc::new(Mutex::new(media_url));
            let mut urls = self.thumbnails.lock().await;
            urls.put(entry_path.into_boxed_str(), arc.clone());
            Ok(arc)
        } else if let Some(token) = self.valid_token_str().await {
            let loaded_blob = fetch_blob(&token, &entry_path).await?;
            JsFuture::from(self.cache_storage.put_with_str(&entry_path, &loaded_blob))
                .await
                .map_err(js_to_frontend_error)?;
            let result = JsFuture::from(self.cache_storage.match_with_str(&entry_path))
                .await
                .map_err(js_to_frontend_error)?;
            info!("Fetched from server");
            let media_url = extract_blob_from_result(result)
                .await
                .map_err(js_to_frontend_error)?;
            Ok(Arc::new(Mutex::new(media_url)))
        } else {
            Err(FrontendError::NotLoggedIn)
        }
    }
}
async fn extract_blob_from_result(result: JsValue) -> Result<MediaUrl, JsValue> {
    Ok(ObjectUrl::from(Blob::from(
        JsFuture::from(Response::from(result).blob()?).await?,
    ))
    .into())
}
fn js_to_frontend_error(error: JsValue) -> FrontendError {
    FrontendError::JS(error.into())
}
#[derive(Debug, Error)]
enum DoFetchError<D> {
    ExternalError(DataAccessError),
    StreamError(#[from] mpsc::error::SendError<DataFetchMessage<D>>),
}

impl<D, T: Into<DataAccessError>> From<T> for DoFetchError<D> {
    fn from(value: T) -> Self {
        DoFetchError::ExternalError(value.into())
    }
}

pub enum DataFetchMessage<D> {
    Progress(f64),
    Data(D),
    Error(DataAccessError),
}

#[derive(Debug, Error, Clone)]
pub enum DataAccessError {
    #[error("Error initializing data access: {0}")]
    Initialization(StorageError),
    #[error("Error fetching album: {0}")]
    FetchAlbum(StorageError),
    #[error("Error Storing album: {0}")]
    StoreAlbum(StorageError),
    #[error("Error fetching albums: {0}")]
    GraphqlAllAlbumVersions(GraphqlAccessError<AllAlbumVersions>),
    #[error("Error fetch album details: {0}")]
    GraphqlGetAlbumDetails(GraphqlAccessError<GetAlbumDetails>),
    #[error("Error fetch album enries: {0}")]
    GraphqlAlbumContent(GraphqlAccessError<AlbumContent>),
}

impl DataAccessError {
    pub fn lookup_error_message(&self) -> (Cow<'_, str>, Cow<'_, str>) {
        match self {
            DataAccessError::Initialization(error) => (
                "Fehler bei der Initialisierung".into(),
                error.to_string().into(),
            ),
            DataAccessError::FetchAlbum(error) => (
                "Fehler beim Laden der lokalen Daten".into(),
                error.to_string().into(),
            ),
            DataAccessError::GraphqlAllAlbumVersions(error) => (
                "Fehler beim Laden der Daten vom Server".into(),
                error.to_string().into(),
            ),
            DataAccessError::GraphqlGetAlbumDetails(error) => (
                "Fehler beim Laden der Daten vom Server".into(),
                error.to_string().into(),
            ),
            DataAccessError::GraphqlAlbumContent(error) => (
                "Fehler beim Laden der Daten vom Server".into(),
                error.to_string().into(),
            ),
            DataAccessError::StoreAlbum(error) => (
                "Fehler beim Speichern der lokalen Daten".into(),
                error.to_string().into(),
            ),
        }
    }
    pub fn render_error_message(&self) -> Html {
        let (title, content) = self.lookup_error_message();
        html! {
            <AlertGroup>
                <Alert inline=true title={title.to_string()} r#type={AlertType::Danger}>{content}</Alert>
            </AlertGroup>
        }
    }
}

impl From<GraphqlAccessError<AllAlbumVersions>> for DataAccessError {
    fn from(value: GraphqlAccessError<AllAlbumVersions>) -> Self {
        Self::GraphqlAllAlbumVersions(value)
    }
}
impl From<GraphqlAccessError<GetAlbumDetails>> for DataAccessError {
    fn from(value: GraphqlAccessError<GetAlbumDetails>) -> Self {
        Self::GraphqlGetAlbumDetails(value)
    }
}

struct AutoHideHtml(HtmlElement);

impl Drop for AutoHideHtml {
    fn drop(&mut self) {
        self.0.set_hidden(true);
    }
}

impl AutoHideHtml {
    pub fn new(element: HtmlElement) -> Self {
        element.set_hidden(false);
        Self(element)
    }
}
