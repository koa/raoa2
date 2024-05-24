use gloo::file::ObjectUrl;
use std::cell::RefCell;
use std::ops::DerefMut;
use std::rc::Rc;

use lazy_static::__Deref;
use log::{error, info};
use patternfly_yew::prelude::{Progress, Spinner};
use tokio_stream::StreamExt;
use web_sys::Blob;
use yew::{html, html::Scope, platform::spawn_local, Component, Context, Html, Properties};

use crate::data::server_api::thumbnail_url;
use crate::data::MediaUrl;
use crate::{
    data::{storage::AlbumEntry, DataAccess, DataAccessError, DataFetchMessage},
    error::FrontendError,
};

#[derive(Default)]
pub struct SingleAlbum {
    id: Box<str>,
    entries: Rc<Box<[(AlbumEntry, RefCell<Option<MediaUrl>>)]>>,
    processing: ProcessingState,
}
#[derive(Debug, Default)]
enum ProcessingState {
    #[default]
    None,
    FetchingInfinite,
    Progress(f64),
    Error(DataAccessError),
}
//#[derive(Debug)]
pub enum SingleAlbumMessage {
    StartProgress,
    FinishProgress,
    EntryList(Rc<Box<[(AlbumEntry, RefCell<Option<MediaUrl>>)]>>),
    UpdateProgress(f64),
    DataError(DataAccessError),
}
#[derive(PartialEq, Properties)]
pub struct SingleAlbumProps {
    pub id: String,
}

impl Component for SingleAlbum {
    type Message = SingleAlbumMessage;
    type Properties = SingleAlbumProps;

    fn create(ctx: &Context<Self>) -> Self {
        let props = ctx.props();
        Self {
            id: props.id.clone().into_boxed_str(),
            entries: Rc::new(Box::new([])),
            processing: Default::default(),
        }
    }

    fn update(&mut self, ctx: &Context<Self>, msg: Self::Message) -> bool {
        //info!("Msg: {msg:?}");
        match msg {
            SingleAlbumMessage::StartProgress => {
                self.processing = ProcessingState::FetchingInfinite;
                true
            }
            SingleAlbumMessage::FinishProgress => match &self.processing {
                ProcessingState::None
                | ProcessingState::FetchingInfinite
                | ProcessingState::Progress(_) => {
                    self.processing = ProcessingState::None;
                    true
                }
                ProcessingState::Error(_) => false,
            },
            SingleAlbumMessage::EntryList(entries) => {
                let equals = self.entries == entries;
                if equals {
                    false
                } else {
                    self.entries = entries;
                    let scope = ctx.link().clone();
                    spawn_local(async move {});
                    true
                }
            }
            SingleAlbumMessage::UpdateProgress(p) => {
                self.processing = ProcessingState::Progress(p);
                true
            }
            SingleAlbumMessage::DataError(e) => {
                self.processing = ProcessingState::Error(e);
                true
            }
        }
    }

    fn view(&self, ctx: &Context<Self>) -> Html {
        let indicator = match &self.processing {
            ProcessingState::None => html! {},
            ProcessingState::FetchingInfinite => html! {<Spinner/>},
            ProcessingState::Progress(value) => {
                html! {<Progress description="Lade Album Inhalt" {value}/>}
            }
            ProcessingState::Error(error) => error.render_error_message(),
        };
        html! {
            <>
                {indicator}
                <ol class="image-rows">
                {
                    for self.entries.iter().map(|e|{
                    let b = e.1.borrow();
                    let d = b.deref();
                    if let Some(blob)=d{
                        let src=blob.deref().to_string();
                        html!{<li><img {src}/></li>}
                    }else{
                        html!(<li>{"Loading"}</li>)
                    }
                    })

                }
                </ol>
            </>
        }

        //html!(<>{format!("Entries: {}",self.entries.len())}{indicator}</>)
    }

    fn rendered(&mut self, ctx: &Context<Self>, first_render: bool) {
        if first_render {
            let scope = ctx.link().clone();
            let id = self.id.clone();
            spawn_local(async move {
                scope.send_message(SingleAlbumMessage::StartProgress);
                match fetch_album_content(&scope, &id).await {
                    /*Err(FrontendError::NotLoggedIn)=>{
                        scope.send_message(SingleAlbumMessage::)
                    }*/
                    Err(e) => {
                        error!("Cannot get album list: {e}");
                    }
                    Ok(..) => {}
                }
                scope.send_message(SingleAlbumMessage::FinishProgress);
            });
        }
    }
}

async fn fetch_album_content(scope: &Scope<SingleAlbum>, id: &str) -> Result<(), FrontendError> {
    let (access, _) = scope
        .context::<Rc<DataAccess>>(Default::default())
        .expect("Context missing");
    let mut album_stream = access.fetch_album_content_interactive(id);

    while let Some(msg) = album_stream.next().await {
        match msg {
            DataFetchMessage::Data(data) => {
                let new_list = Rc::new(
                    data.iter()
                        .cloned()
                        .map(|e| (e, RefCell::new(None)))
                        .collect::<Box<[_]>>(),
                );

                scope.send_message(SingleAlbumMessage::EntryList(new_list.clone()));
                scope.send_message(SingleAlbumMessage::StartProgress);
                for (idx, (entry, blob_ref)) in new_list.iter().enumerate() {
                    if idx % 10 == 9 {
                        scope.send_message(SingleAlbumMessage::UpdateProgress(
                            new_list.len() as f64 / idx as f64,
                        ));
                    }
                    let blob = access.fetch_thumbnail(entry, None).await?;

                    blob_ref.replace(Some(blob));
                }
                scope.send_message(SingleAlbumMessage::FinishProgress);
            }
            DataFetchMessage::Progress(p) => {
                scope.send_message(SingleAlbumMessage::UpdateProgress(p))
            }
            DataFetchMessage::Error(error) => {
                scope.send_message(SingleAlbumMessage::DataError(error));
            }
        }
    }
    Ok(())
}
