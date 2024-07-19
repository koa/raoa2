use std::rc::Rc;

use log::{error, info};
use patternfly_yew::prelude::CardTitle;
use patternfly_yew::prelude::{Card, CardBody, Gallery, Level, Progress, Spinner, Title};
use tokio_stream::StreamExt;
use yew::{html, html::Scope, platform::spawn_local, Component, Context, Html};
use yew_nested_router::components::Link;

use crate::components::image::Image;
use crate::{
    data::{storage::AlbumDetails, DataAccess, DataAccessError, DataFetchMessage},
    error::FrontendError,
    pages::app::routing::AppRoute,
};

#[derive(Debug, Default)]
pub struct AlbumList {
    albums: Box<[AlbumDetails]>,
    processing: ProcessingState,
}
#[derive(Debug)]
pub enum AlbumListMessage {
    AlbumList(Box<[AlbumDetails]>),
    UpdateProgress(f64),
    StartProgress,
    DataError(DataAccessError),
}

#[derive(Debug, Default)]
pub enum ProcessingState {
    #[default]
    None,
    FetchingInfinite,
    Progress(f64),
    Error(DataAccessError),
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
                self.processing = ProcessingState::None;
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
            AlbumListMessage::DataError(error) => {
                self.processing = ProcessingState::Error(error);
                true
            }
        }
    }

    fn view(&self, ctx: &Context<Self>) -> Html {
        let indicator = match &self.processing {
            ProcessingState::None => html! {},
            ProcessingState::FetchingInfinite => html! {<Spinner/>},
            ProcessingState::Progress(value) => {
                html! {<Progress description="Lade Album Daten" {value}/>}
            }
            ProcessingState::Error(error) => error.render_error_message(),
        };
        html! {
            <>
            {indicator}
            <Gallery gutter=true style="margin: 0.5em">
                {for self
                    .albums
                    .iter()
                    .map(|album| {
                        let timestamp = album
                            .timestamp()
                            .map(|t| t.as_ref().format("%c").to_string())
                            .map(|date| html! {<Title level={Level::H4}>{date}</Title>});
                        let title = html! {<>{timestamp}<Title>{html!(album.name())}</Title></>};
                        let to = AppRoute::Album {
                            id: album.id().into(),
                        };
                        let title_image = album.title_image.as_ref().map(|title_image|
                        {let entry=title_image.clone();
                        html!(<Image {entry} rendered=true/>) } ).unwrap_or_default();

                        //
                        html! {<Link<AppRoute> {to}><Card full_height=true>
                        <CardTitle>{title}</CardTitle>
                        <CardBody>{title_image}</CardBody>
                        </Card></Link<AppRoute>>}
                    })
                }
            </Gallery>
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
            DataFetchMessage::Data(data) => {
                scope.send_message(AlbumListMessage::AlbumList(data));
            }
            DataFetchMessage::Progress(p) => {
                scope.send_message(AlbumListMessage::UpdateProgress(p))
            }
            DataFetchMessage::Error(error) => {
                scope.send_message(AlbumListMessage::DataError(error));
            }
        }
    }
    Ok(())
}
