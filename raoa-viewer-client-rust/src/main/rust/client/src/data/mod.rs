use std::borrow::Cow;
use std::cell::{Ref, RefCell, RefMut};
use std::collections::HashMap;
use std::rc::Rc;

use log::{error, info};
use patternfly_yew::prelude::{Alert, AlertGroup, AlertType};
use thiserror::Error;
use tokio::sync::mpsc;
use tokio::sync::mpsc::Sender;
use tokio_stream::wrappers::ReceiverStream;
use tokio_stream::Stream;
use yew::platform::spawn_local;
use yew::{html, Html};

use crate::data::server_api::graphql::model::get_album_details::GetAlbumDetailsAlbumByIdLabels;
use crate::data::server_api::graphql::model::{
    all_album_versions, get_album_details, AllAlbumVersions, GetAlbumDetails,
};
use crate::data::server_api::graphql::{query, GraphqlAccessError};
use crate::data::session::UserSessionData;
use crate::data::storage::{AlbumDetails, StorageAccess, StorageError};

pub mod server_api;
pub mod session;
pub mod storage;

#[derive(Debug)]
pub struct DataAccess {
    storage: StorageAccess,
    login_data: RefCell<UserSessionData>,
}

impl PartialEq for DataAccess {
    fn eq(&self, other: &Self) -> bool {
        self.login_data == other.login_data
    }
}

impl DataAccess {
    pub async fn new() -> Result<Rc<DataAccess>, DataAccessError> {
        Ok(Rc::new(Self {
            storage: StorageAccess::new()
                .await
                .map_err(DataAccessError::Initialization)?,
            login_data: Default::default(),
        }))
    }

    pub fn storage(&self) -> &StorageAccess {
        &self.storage
    }
    pub fn storage_mut(&mut self) -> &mut StorageAccess {
        &mut self.storage
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

    async fn do_fetch_albums_interactive(
        &self,
        tx: Sender<DataFetchMessage<Box<[AlbumDetails]>>>,
    ) -> Result<(), DoFetchError<Box<[AlbumDetails]>>> {
        let stored_albums = self
            .storage
            .list_albums()
            .await
            .map_err(DataAccessError::FetchAlbum)?;
        if let Some(token) = self.login_data().jwt() {
            tx.send(DataFetchMessage::PreliminaryData(stored_albums.clone()))
                .await?;
            let mut existing_albums = stored_albums
                .iter()
                .map(|e| (e.id(), e))
                .collect::<HashMap<_, _>>();
            let responses =
                query::<AllAlbumVersions>(token, all_album_versions::Variables {}).await?;
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
                        token,
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
                        self.storage
                            .store_album(entry.clone())
                            .await
                            .map_err(DataAccessError::FetchAlbum)?;
                        all_albums.push(entry);
                    }
                }
            }
            tx.send(DataFetchMessage::PreliminaryData(
                all_albums.into_boxed_slice(),
            ))
            .await?;
        } else {
            tx.send(DataFetchMessage::PreliminaryData(stored_albums))
                .await?;
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
    PreliminaryData(D),
    Progress(f64),
    FinalData(D),
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
