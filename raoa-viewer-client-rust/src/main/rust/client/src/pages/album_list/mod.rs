use std::rc::Rc;

use log::{error, info};
use patternfly_yew::prelude::{Card, Gallery, Level, Progress, Spinner, Title};
use tokio_stream::StreamExt;
use yew::{html, html::Scope, platform::spawn_local, Component, Context, Html};

use crate::data::storage::AlbumDetails;
use crate::data::{DataAccess, DataFetchMessage};
use crate::error::FrontendError;

#[derive(Debug, Default)]
pub struct AlbumList {
    albums: Box<[AlbumDetails]>,
    processing: ProcessingState,
}
pub enum AlbumListMessage {
    AlbumList(Box<[AlbumDetails]>),
    UpdateProgress(f64),
    StartProgress,
    FinishProgress,
}

#[derive(Debug, Default)]
pub enum ProcessingState {
    #[default]
    None,
    FetchingInfinite,
    Progress(f64),
}

impl Component for AlbumList {
    type Message = AlbumListMessage;
    type Properties = ();

    fn create(ctx: &Context<Self>) -> Self {
        Default::default()
    }

    fn update(&mut self, ctx: &Context<Self>, msg: Self::Message) -> bool {
        match msg {
            AlbumListMessage::AlbumList(mut response) => {
                response.sort_by(|a, b| a.timestamp().cmp(&b.timestamp()).reverse());
                self.albums = response;
                true
            }
            AlbumListMessage::UpdateProgress(v) => {
                self.processing = ProcessingState::Progress(v);
                true
            }
            AlbumListMessage::StartProgress => {
                self.processing = ProcessingState::FetchingInfinite;
                true
            }
            AlbumListMessage::FinishProgress => {
                self.processing = ProcessingState::None;
                true
            }
        }
    }

    fn view(&self, ctx: &Context<Self>) -> Html {
        let indicator = match self.processing {
            ProcessingState::None => html! {},
            ProcessingState::FetchingInfinite => html! {<Spinner/>},
            ProcessingState::Progress(value) => {
                html! {<Progress description="Lade Album Daten" {value}/>}
            }
        };
        let album_cards: Vec<_> = self
            .albums
            .iter()
            .map(|album| {
                let timestamp = album
                    .timestamp()
                    .map(|t| t.as_ref().format("%c").to_string())
                    .map(|date| html! {<Title level={Level::H4}>{date}</Title>});
                let title = html! {<>{timestamp}<Title>{html!(album.name())}</Title></>};
                //
                html! {<Card {title} full_height=true></Card>}
            })
            .collect();

        html! {
            <>
            <Gallery gutter=true style="margin: 0.5em">
                {for album_cards.into_iter()}
            </Gallery>
            {indicator}
            </>
        }
    }

    fn rendered(&mut self, ctx: &Context<Self>, first_render: bool) {
        if first_render {
            let scope = ctx.link().clone();
            spawn_local(async move {
                scope.send_message(AlbumListMessage::StartProgress);
                if let Err(e) = fetch_albums(&scope).await {
                    error!("Cannot get album list: {e}");
                }
                scope.send_message(AlbumListMessage::FinishProgress);
            });
        }
    }
}
async fn fetch_albums(scope: &Scope<AlbumList>) -> Result<(), FrontendError> {
    let (access, _) = scope
        .context::<Rc<DataAccess>>(Default::default())
        .expect("Context missing");
    let mut album_stream = access.fetch_albums_interactive();
    while let Some(msg) = album_stream.next().await {
        match msg {
            DataFetchMessage::PreliminaryData(data) => {
                scope.send_message(AlbumListMessage::AlbumList(data));
            }
            DataFetchMessage::Progress(p) => {
                scope.send_message(AlbumListMessage::UpdateProgress(p))
            }
            DataFetchMessage::FinalData(data) => {
                scope.send_message(AlbumListMessage::AlbumList(data))
            }
            DataFetchMessage::Error(_) => {}
        }
    }
    Ok(())
}
