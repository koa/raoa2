use std::collections::HashMap;

use log::error;
use patternfly_yew::prelude::Spinner;
use yew::html::Scope;
use yew::platform::spawn_local;
use yew::{html, Component, Context, Html};

use crate::error::FrontendError;
use crate::server_api::graphql::model::all_album_versions::AllAlbumVersionsListAlbums;
use crate::server_api::graphql::model::get_album_details::GetAlbumDetailsAlbumByIdLabels;
use crate::server_api::graphql::model::AllAlbumVersions;
use crate::server_api::graphql::model::{all_album_versions, get_album_details, GetAlbumDetails};
use crate::server_api::graphql::query;
use crate::storage::{list_albums, store_album, AlbumDetails};

#[derive(Debug, Default)]
pub struct AlbumList {
    albums: Option<Box<[AlbumDetails]>>,
}
pub enum AlbumListMessage {
    AlbumList(Box<[AlbumDetails]>),
}

impl Component for AlbumList {
    type Message = AlbumListMessage;
    type Properties = ();

    fn create(ctx: &Context<Self>) -> Self {
        Default::default()
    }

    fn update(&mut self, ctx: &Context<Self>, msg: Self::Message) -> bool {
        match msg {
            AlbumListMessage::AlbumList(response) => {
                self.albums = Some(response);
                true
            }
        }
    }

    fn view(&self, ctx: &Context<Self>) -> Html {
        match &self.albums {
            None => html! {<Spinner/>},
            Some(albums) => {
                html! {format!( "Album list: {}", albums.len())}
            }
        }
    }

    fn rendered(&mut self, ctx: &Context<Self>, first_render: bool) {
        if first_render {
            let scope = ctx.link().clone();
            spawn_local(async move {
                let r = fetch_albums(&scope).await;
                match r {
                    Ok(message) => scope.send_message(message),
                    Err(e) => {
                        error!("Cannot get album list: {e}");
                    }
                }
            });
        }
    }
}
async fn fetch_albums(scope: &Scope<AlbumList>) -> Result<AlbumListMessage, FrontendError> {
    let mut idx = list_albums()
        .await?
        .iter()
        .map(|e| (e.id().clone(), e.clone()))
        .collect::<HashMap<_, _>>();
    let responses = query::<AllAlbumVersions, _>(scope, all_album_versions::Variables {}).await?;
    let mut all_albums = Vec::with_capacity(responses.list_albums.len());
    for album in responses.list_albums.iter() {
        if let Some(found_entry) = idx
            .remove(album.id.as_str())
            .filter(|entry| entry.version().as_ref() == album.version.as_str())
        {
            all_albums.push(found_entry);
        } else {
            let details = query::<GetAlbumDetails, _>(
                &scope.clone(),
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
                let d = AlbumDetails::new(
                    album_data.id.into_boxed_str(),
                    album_data.name.unwrap_or_default().into_boxed_str(),
                    album_data.version.into_boxed_str(),
                    album_data.album_time,
                    album_data.entry_count.map(|i| i as u32).unwrap_or(0),
                    fnch_album_id,
                );
                store_album(d.clone()).await?;
                all_albums.push(d);
            }
        }
    }

    Ok(AlbumListMessage::AlbumList(all_albums.into_boxed_slice()))
}
