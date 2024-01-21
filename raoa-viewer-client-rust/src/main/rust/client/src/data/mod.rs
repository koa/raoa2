use std::borrow::Cow;
use std::cell::{Ref, RefCell, RefMut};
use std::collections::HashMap;
use std::ops::Deref;
use std::rc::Rc;
use std::time::Duration;

use google_signin_client::{
    prompt_async, render_button, ButtonType, DismissedReason, GsiButtonConfiguration, PromptResult,
};
use log::{error, info};
use ordered_float::OrderedFloat;
use patternfly_yew::prelude::{Alert, AlertGroup, AlertType};
use thiserror::Error;
use tokio::sync::mpsc;
use tokio::sync::mpsc::Sender;
use tokio_stream::wrappers::ReceiverStream;
use tokio_stream::Stream;
use web_sys::{window, HtmlElement, Navigator, Window};
use yew::platform::spawn_local;
use yew::{html, Html, NodeRef};

use crate::data::server_api::graphql::model::get_album_details::GetAlbumDetailsAlbumByIdLabels;
use crate::data::server_api::graphql::model::{
    album_content, all_album_versions, get_album_details, AlbumContent, AllAlbumVersions,
    GetAlbumDetails,
};
use crate::data::server_api::graphql::{query, GraphqlAccessError};
use crate::data::session::UserSessionData;
use crate::data::storage::{AlbumDetails, AlbumEntry, StorageAccess, StorageError};

pub mod server_api;
pub mod session;
pub mod storage;

#[derive(Debug)]
pub struct DataAccess {
    storage: RefCell<StorageAccess>,
    login_data: RefCell<UserSessionData>,
    login_button_ref: NodeRef,
}

impl PartialEq for DataAccess {
    fn eq(&self, other: &Self) -> bool {
        self.login_data == other.login_data
    }
}

impl DataAccess {
    pub async fn new(login_button_ref: NodeRef) -> Result<Rc<DataAccess>, DataAccessError> {
        Ok(Rc::new(Self {
            storage: RefCell::new(
                StorageAccess::new()
                    .await
                    .map_err(DataAccessError::Initialization)?,
            ),
            login_data: Default::default(),
            login_button_ref,
        }))
    }

    pub fn storage(&self) -> Ref<'_, StorageAccess> {
        self.storage.borrow()
    }
    pub fn storage_mut(&self) -> RefMut<'_, StorageAccess> {
        self.storage.borrow_mut()
    }
    pub fn login_data(&self) -> Ref<'_, UserSessionData> {
        self.login_data.borrow()
    }
    pub fn login_data_mut(&self) -> RefMut<'_, UserSessionData> {
        self.login_data.borrow_mut()
    }

    pub fn fetch_albums_interactive(
        self: &Rc<Self>,
    ) -> impl Stream<Item = DataFetchMessage<Box<[AlbumDetails]>>> {
        let (tx, rx) = mpsc::channel(10);
        let s_clone = self.clone();
        spawn_local(async move {
            match s_clone.do_fetch_albums_interactive(tx.clone()).await {
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
    async fn valid_token_str(&self) -> Option<String> {
        self.valid_user_session()
            .await
            .and_then(|s| s.jwt().map(|s| s.to_string()))
    }

    async fn do_fetch_albums_interactive(
        &self,
        tx: Sender<DataFetchMessage<Box<[AlbumDetails]>>>,
    ) -> Result<(), DoFetchError<Box<[AlbumDetails]>>> {
        let stored_albums = self
            .storage()
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
                    .remove(album.id.as_str())
                    .filter(|entry| entry.version() == album.version.as_str())
                {
                    all_albums.push(found_entry.clone());
                } else {
                    tx.send(DataFetchMessage::Progress(idx as f64 / album_count as f64))
                        .await?;
                    let details = query::<GetAlbumDetails>(
                        &token,
                        get_album_details::Variables {
                            album_id: album.id.clone(),
                        },
                    )
                    .await?;
                    if let Some(album_data) = details.album_by_id {
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
                        let entry = AlbumDetails::new(
                            album_data.id.into_boxed_str(),
                            album_data.name.unwrap_or_default().into_boxed_str(),
                            album_data.version.into_boxed_str(),
                            album_data.album_time,
                            album_data.entry_count.map(|i| i as u32).unwrap_or(0),
                            fnch_album_id,
                        );
                        self.storage_mut()
                            .store_album(entry.clone())
                            .await
                            .map_err(DataAccessError::FetchAlbum)?;
                        all_albums.push(entry);
                    }
                }
            }
            tx.send(DataFetchMessage::Data(all_albums.into_boxed_slice()))
                .await?;
            for id in existing_albums.keys() {
                self.storage_mut()
                    .remove_album(&id)
                    .await
                    .map_err(DataAccessError::FetchAlbum)?;
            }
        }
        Ok(())
    }
    async fn valid_user_session(&self) -> Option<Ref<'_, UserSessionData>> {
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

        {
            let session_data = self.login_data();
            if session_data.is_token_valid() {
                return Some(session_data);
            }
        }
        let result = prompt_async().await;
        if result == PromptResult::Dismissed(DismissedReason::CredentialReturned) {
            return Some(self.login_data());
        }

        if let Some(login_button_ref) = self
            .login_button_ref
            .cast::<HtmlElement>()
            .map(AutoHideHtml::new)
        {
            render_button(
                login_button_ref.0.clone(),
                GsiButtonConfiguration::new(ButtonType::Standard),
            );
            let mut ref_mut = self.login_data_mut();
            if let Some(mut rx) = ref_mut.wait_for_token() {
                drop(ref_mut);
                rx.recv().await;
                let session_data = self.login_data();
                if session_data.is_token_valid() {
                    return Some(session_data);
                }
            }
        }
        None
    }
    pub fn fetch_album_content_interactive(
        self: &Rc<Self>,
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
        tx: Sender<DataFetchMessage<Box<[AlbumEntry]>>>,
        id: String,
    ) -> Result<(), DoFetchError<Box<[AlbumEntry]>>> {
        let result = self.storage().list_album_entries(&id).await;
        info!("Result: {result:?}");
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
                    entry_id: entry_id,
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
                        .map(|v| Duration::from_secs_f32(v as f32)),
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
                    .store_album_entries(modified_entries)
                    .await
                    .map_err(DataAccessError::FetchAlbum)?;
            }
            if !existing_entries.is_empty() {
                self.storage()
                    .remove_album_entries(existing_entries.values().copied())
                    .await
                    .map_err(DataAccessError::FetchAlbum)?;
            }
        }
        Ok(())
    }
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

#[derive(Debug, Error)]
pub enum DataAccessError {
    #[error("Error initializing data access: {0}")]
    Initialization(StorageError),
    #[error("Error fetching albums: {0}")]
    FetchAlbum(StorageError),
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
