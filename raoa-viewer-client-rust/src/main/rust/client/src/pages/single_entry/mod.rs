use crate::components::image::Image;
use crate::data::storage::AlbumEntry;
use crate::data::{DataAccess, DataAccessError, DataFetchMessage};
use crate::error::FrontendError;
use crate::pages::app::routing::{AlbumRoute, AlbumsRoute, AppRoute};
use crate::pages::single_album::{SingleAlbum, SingleAlbumMessage};
use crate::utils::swiper::{Error, Swiper};
use log::{error, info};
use std::cmp::Ordering;
use std::rc::Rc;
use tokio_stream::StreamExt;
use wasm_bindgen::closure::Closure;
use wasm_bindgen::JsCast;
use web_sys::{Event, HtmlElement};
use yew::html::{ontransitionend, Scope};
use yew::platform::spawn_local;
use yew::virtual_dom::VNode;
use yew::{html, Callback, Component, Context, Html, NodeRef, Properties};
use yew_nested_router::prelude::{RouterContext, State};
use yew_nested_router::Router;

type EntryList = Rc<Box<[AlbumEntry]>>;

pub struct SingleEntry {
    album_id: Box<str>,
    entry_id: Box<str>,
    image_slider_ref: NodeRef,
    video_root_ref: NodeRef,
    swiper: Option<Swiper>,
    transition_end_listener: Option<Closure<dyn Fn(Event)>>,
    processing_state: ProcessingState,
    entries_of_album: Box<[AlbumEntry]>,
    prev_entry: Option<AlbumEntry>,
    entry: Option<AlbumEntry>,
    next_entry: Option<AlbumEntry>,
    router: RouterContext<AppRoute>,
}
#[derive(Debug, Default)]
enum ProcessingState {
    #[default]
    None,
    FetchingInfinite,
    Progress(f64),
    Error(DataAccessError),
}
#[derive(Debug)]
pub enum SingleEntryMessage {
    SlideNext,
    OnTransitionEnd(Event),
    UpdateDataFetch(DataFetchMessage<Box<[AlbumEntry]>>),
    ActivateEntry(Box<str>),
}
#[derive(Properties, PartialEq)]
pub struct SingleEntryProps {
    pub album_id: String,
    pub entry_id: String,
}

impl Component for SingleEntry {
    type Message = SingleEntryMessage;
    type Properties = SingleEntryProps;

    fn create(ctx: &Context<Self>) -> Self {
        let props = ctx.props();
        let (router, context_listener) = ctx
            .link()
            .context(Callback::noop())
            .expect("No Message Context Provided");

        Self {
            album_id: props.album_id.clone().into_boxed_str(),
            entry_id: props.entry_id.clone().into_boxed_str(),
            image_slider_ref: Default::default(),
            video_root_ref: Default::default(),
            swiper: None,
            transition_end_listener: None,
            processing_state: Default::default(),
            entries_of_album: Box::new([]),
            prev_entry: None,
            entry: None,
            next_entry: None,
            router,
        }
    }

    fn update(&mut self, ctx: &Context<Self>, msg: Self::Message) -> bool {
        info!("MSG: {msg:?}");
        match msg {
            SingleEntryMessage::SlideNext => {
                if let Some(swiper) = &self.swiper {
                    swiper.slide_next();
                }
                false
            }
            SingleEntryMessage::OnTransitionEnd(event) => {
                if let Some(swiper) = &self.swiper {
                    let active_index = swiper.active_index();
                    if let Some(replace_id) = if active_index == 0 {
                        self.prev_entry.as_ref()
                    } else if active_index == 2 {
                        self.next_entry.as_ref()
                    } else {
                        None
                    }
                    .map(|e| &e.entry_id)
                    {
                        let target = AppRoute::Albums {
                            view: AlbumsRoute::Album {
                                id: self.album_id.to_string(),
                                view: AlbumRoute::Entry {
                                    id: replace_id.to_string(),
                                },
                            },
                        };
                        ctx.link().send_message(SingleEntryMessage::ActivateEntry(
                            replace_id.to_string().into_boxed_str(),
                        ));
                        self.router.push(target);
                    }
                    swiper.set_active_index(1);
                    swiper.update();
                }
                false
            }
            SingleEntryMessage::UpdateDataFetch(fetch_update) => match fetch_update {
                DataFetchMessage::Data(mut data) => {
                    data.sort_by(|e1, e2| {
                        let create_compare = Option::cmp(&e1.created, &e1.created);
                        if create_compare != Ordering::Equal {
                            return create_compare;
                        }
                        e1.name.cmp(&e2.name)
                    });
                    self.entries_of_album = data;
                    self.processing_state = ProcessingState::None;
                    ctx.link()
                        .send_message(SingleEntryMessage::ActivateEntry(self.entry_id.clone()));
                    false
                }
                DataFetchMessage::Progress(p) => {
                    self.processing_state = ProcessingState::Progress(p);
                    true
                }
                DataFetchMessage::Error(error) => {
                    self.processing_state = ProcessingState::Error(error);
                    true
                }
            },
            SingleEntryMessage::ActivateEntry(entry_id) => {
                if self.entry_id == entry_id && self.entry.is_some() {
                    false
                } else {
                    let mut prev_entry = None;
                    let mut last_found = false;
                    for entry in &self.entries_of_album {
                        if last_found {
                            self.next_entry = Some(entry.clone());
                            break;
                        }
                        if entry.entry_id == entry_id {
                            self.prev_entry = Option::<&_>::cloned(prev_entry);
                            self.entry = Some(entry.clone());
                            last_found = true;
                        } else {
                            prev_entry = Some(entry);
                        }
                    }
                    self.entry_id = entry_id;
                    true
                }
            }
        }
    }

    fn view(&self, ctx: &Context<Self>) -> Html {
        let image_slider_ref = self.image_slider_ref.clone();
        let video_root_ref = self.video_root_ref.clone();
        let onclick = ctx.link().callback(|_| SingleEntryMessage::SlideNext);
        let image = render_entry(&self.entry);
        html! {
            <div class="image" ref={video_root_ref}>
                <swiper-container ref={image_slider_ref} init="true">
                    <swiper-slide>{render_entry(&self.prev_entry)}</swiper-slide>
                    <swiper-slide>
                        <div class="swiper-zoom-container">
                            {render_entry(&self.entry)}
                        </div>
                    </swiper-slide>
                    <swiper-slide>{render_entry(&self.next_entry)}</swiper-slide>
                </swiper-container>
                <div class="overlay">
                    <button {onclick}>{"Next"}</button>
                </div>
            </div>
        }
    }

    fn rendered(&mut self, ctx: &Context<Self>, first_render: bool) {
        if first_render {
            if let Some(node) = self.image_slider_ref.cast::<HtmlElement>() {
                let scope = ctx.link();
                let slide_change_transition_end =
                    scope.callback(SingleEntryMessage::OnTransitionEnd);
                let listener = Closure::<dyn Fn(Event)>::wrap(Box::new(move |e: Event| {
                    slide_change_transition_end.emit(e)
                }));

                node.add_event_listener_with_callback(
                    "swiperslidechangetransitionend",
                    listener.as_ref().unchecked_ref(),
                )
                .unwrap();

                self.transition_end_listener = Some(listener);
                let swiper = Swiper::new(node);
                match swiper {
                    Ok(swiper) => {
                        swiper.set_active_index(1);
                        let active_index = swiper.active_index();
                        info!("Got active index: {active_index}");
                        info!("Swiper: {swiper:?}");
                        self.swiper = Some(swiper);
                    }
                    Err(e) => {
                        error!("Cannot initialize swiper {e}");
                    }
                }
                let id = self.album_id.clone();
                let scope = scope.clone();
                spawn_local(async move {
                    if let Err(e) = fetch_album_content(&scope, &id).await {
                        error!("Cannot get album list: {e}");
                    }
                });
            }
        }
    }
}

fn render_entry(option: &Option<AlbumEntry>) -> Option<VNode> {
    option
        .as_ref()
        .cloned()
        .map(|entry| html!(<Image {entry}/>))
}
async fn fetch_album_content(scope: &Scope<SingleEntry>, id: &str) -> Result<(), FrontendError> {
    info!("Fetch");
    let (access, _) = scope
        .context::<Rc<DataAccess>>(Default::default())
        .expect("Context missing");
    let mut album_stream = access.fetch_album_content_interactive(id);

    while let Some(msg) = album_stream.next().await {
        scope.send_message(SingleEntryMessage::UpdateDataFetch(msg));
    }
    Ok(())
}
