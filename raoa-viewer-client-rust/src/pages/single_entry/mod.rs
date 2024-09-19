use crate::{
    components::image::Image,
    data::{storage::AlbumEntry, DataAccess, DataAccessError, DataFetchMessage},
    error::FrontendError,
    pages::app::routing::{AlbumRoute, AlbumsRoute, AppRoute},
    utils::swiper::Swiper,
};
use log::{error, info};
use serde::{Deserialize, Serialize};
use std::{cmp::Ordering, rc::Rc};
use tokio_stream::StreamExt;
use wasm_bindgen::{closure::Closure, JsCast};
use web_sys::{window, Event, History, HtmlElement};
use yew::{html, html::Scope, platform::spawn_local, virtual_dom::VNode, Callback, Component, Context, Html, NodeRef, Properties};
use yew_nested_router::prelude::RouterContext;

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
    router_context: Option<RouterContext<AppRoute>>,
}
#[derive(Debug, Default)]
enum ProcessingState {
    #[default]
    None,
    FetchingInfinite,
    Progress(f64),
    Error(DataAccessError),
}
#[derive(Debug, Default)]
pub enum SingleEntryMessage {
    #[default]
    Noop,
    OnTransitionEnd(Event),
    UpdateDataFetch(DataFetchMessage<Box<[AlbumEntry]>>),
    ActivateEntry(Box<str>),
    SlidePrev,
    SlideNext,
}
#[derive(Properties, PartialEq, Serialize, Deserialize, Debug)]
pub struct SingleEntryProps {
    pub album_id: String,
    pub entry_id: String,
}

impl Component for SingleEntry {
    type Message = SingleEntryMessage;
    type Properties = SingleEntryProps;

    fn create(ctx: &Context<Self>) -> Self {
        let props = ctx.props();
        let router_context = ctx.link().context::<RouterContext<AppRoute>>(Callback::from(|_| {})).map(|(c, _)| c);
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
            router_context,
        }
    }

    fn update(&mut self, ctx: &Context<Self>, msg: Self::Message) -> bool {
        match msg {
            SingleEntryMessage::SlideNext => {
                if let Some(swiper) = &self.swiper {
                    swiper.slide_next();
                }
                false
            }
            SingleEntryMessage::SlidePrev => {
                if let Some(swiper) = &self.swiper {
                    swiper.slide_prev();
                }
                false
            }
            SingleEntryMessage::OnTransitionEnd(_) => {
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
                        //let album_id = self.album_id.to_string();
                        let entry_id = replace_id.to_string();
                        /*let target = AppRoute::Albums {
                            view: AlbumsRoute::Album {
                                id: album_id.clone(),
                                view: AlbumRoute::Entry {
                                    id: entry_id.clone(),
                                },
                            },
                        };*/

                        ctx.link().send_message(SingleEntryMessage::ActivateEntry(
                            entry_id.clone().into_boxed_str(),
                        ));
                        //let props = SingleEntryProps { album_id, entry_id };
                        //self.router.push_with(target, State::json(&props).unwrap());
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
                    let target = AppRoute::Albums {
                        view: AlbumsRoute::Album {
                            id: self.album_id.to_string(),
                            view: AlbumRoute::Entry {
                                id: self.entry_id.to_string(),
                            },
                        },
                    };
                    if let Some(mut router_context) = self.router_context.clone() {
                        router_context.replace(target.clone());
                    }
                    true
                }
            }
            SingleEntryMessage::Noop => false,
        }
    }

    fn view(&self, ctx: &Context<Self>) -> Html {
        let image_slider_ref = self.image_slider_ref.clone();
        let video_root_ref = self.video_root_ref.clone();
        let onclick = ctx.link().callback(|_| SingleEntryMessage::SlideNext);
        //let image = render_entry(&self.entry);
        html! {
            <div class="image" ref={video_root_ref}>
                <swiper-container ref={image_slider_ref} init="true" css-mode="true">
                    <swiper-slide>{render_entry(&self.prev_entry)}</swiper-slide>
                    <swiper-slide>
                        <div class="swiper-zoom-container">
                            {render_entry(&self.entry)}
                        </div>
                    </swiper-slide>
                    <swiper-slide>{render_entry(&self.next_entry)}</swiper-slide>
                </swiper-container>
                <div class="overlay">
                    <div class="right">
                        <div>
                            <button {onclick}>{">"}</button>
                        </div>
                    </div>
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
                        swiper.enable_zoom();
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
    let (access, _) = scope
        .context::<Rc<DataAccess>>(Default::default())
        .expect("Context missing");
    let mut album_stream = access.fetch_album_content_interactive(id);
    while let Some(msg) = album_stream.next().await {
        scope.send_message(SingleEntryMessage::UpdateDataFetch(msg));
    }
    Ok(())
}
