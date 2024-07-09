use log::{error, info};
use crate::components::image::Image;
use yew_hooks::use_size;
use std::cmp::Ordering;
use std::rc::Rc;

use gloo_events::EventListener;
use patternfly_yew::prelude::{Progress, Spinner};
use std::{cmp::Ordering, rc::Rc};
use tokio_stream::StreamExt;
use web_sys::{window, HtmlElement, Blob};
use yew::{function_component, html, html::Scope, platform::spawn_local, use_effect_with, use_node_ref, use_state_eq, Component, Context, Html, NodeRef, Properties, use_state, use_context};
use wasm_bindgen::{closure::Closure, JsCast, UnwrapThrowExt};
use web_sys::{window, Element, HtmlElement, ResizeObserver, ResizeObserverEntry};
use yew::{
    function_component, html, html::Scope, platform::spawn_local, use_effect_with, use_node_ref,
    use_state_eq, Component, Context, Html, NodeRef, Properties,
};

use crate::pages::single_album::row_iterator::RowIteratorTrait;
use crate::{
    data::{storage::AlbumEntry, DataAccess, DataAccessError, DataFetchMessage, MediaUrl},
    error::FrontendError,
};

type EntryList = Rc<Box<[AlbumEntry]>>;

#[derive(Debug)]
pub struct SingleAlbum {
    id: Box<str>,
    entries: EntryList,
    processing: ProcessingState,
    div_ref: NodeRef,
    scroll_top: i32,
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
pub enum SingleAlbumMessage {
    StartProgress,
    FinishProgress,
    EntryList(EntryList),
    UpdateProgress(f64),
    DataError(DataAccessError),
}
#[derive(PartialEq, Properties)]
pub struct SingleAlbumProps {
    pub id: String,
    pub top: i32,
    pub height: i32,
    pub scroll_top: i32,
}

mod row_iterator;

impl Component for SingleAlbum {
    type Message = SingleAlbumMessage;
    type Properties = SingleAlbumProps;

    fn create(ctx: &Context<Self>) -> Self {
        let props = ctx.props();
        let div_ref = Default::default();
        Self {
            id: props.id.clone().into_boxed_str(),
            entries: Rc::new(Box::new([])),
            processing: Default::default(),
            div_ref,
            scroll_top: props.scroll_top,
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

    fn changed(&mut self, ctx: &Context<Self>, _old_props: &Self::Properties) -> bool {
        let props = ctx.props();
        //info!("Updated scroll top: {}", props.scroll_top);
        if self.scroll_top != props.scroll_top {
            self.scroll_top = props.scroll_top;
            true
        } else {
            false
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
        let div_ref = self.div_ref.clone();
        let scroll_top = self.scroll_top;
        html! {
            <div ref={div_ref}>
                {indicator}
                <ol class="image-rows">
                {
                    for self.entries.iter().calculate_rows(6.0).into_iter().map(|row| {
                        let entries:Box<[AlbumEntry]>=row;
                        let rendered=false;
                        html!{
                            <li>
                                <ImageRow {entries} {rendered} {scroll_top}/>
                            </li>
                        }

                })
                }
                </ol>
            </div>
        }
        /*html! {
            <>
                {indicator}
                <ol class="image-rows">
                {
                    for self.entries.iter().map(|e|{
                        let style=format!("width: {}px; height: {}px;",e.0.target_width,e.0.target_height);
                        let optional_url_ref = e.1.borrow();
                        if let Some(blob)=optional_url_ref.deref(){
                            let src=blob.deref().to_string();
                            //html!{<li><img {src} loading="lazy" {width} {height}/></li>}
                            html!(<li><div><div {style} class="fa-regular fa-image"></div></div></li>)
                        }else{
                            html!(<li {style}><i class="fa-regular fa-image"></i></li>)
                        }
                    })
                }
                </ol>
            </>
        }*/

        //html!(<>{format!("Entries: {}",self.entries.len())}{indicator}</>)
    }

    fn rendered(&mut self, ctx: &Context<Self>, first_render: bool) {
        if first_render {
            let scope = ctx.link().clone();
            let id = self.id.clone();
            spawn_local(async move {
                //scope.send_message(SingleAlbumMessage::StartProgress);
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
            DataFetchMessage::Data(mut data) => {
                data.sort_by(|e1, e2| {
                    let create_compare = Option::cmp(&e1.created, &e1.created);
                    if create_compare != Ordering::Equal {
                        return create_compare;
                    }
                    e1.name.cmp(&e2.name)
                });
                scope.send_message(SingleAlbumMessage::EntryList(Rc::new(data)));
                /*
                scope.send_message(SingleAlbumMessage::StartProgress);
                let total_elements = data.len();
                for (idx, (entry, blob_ref)) in new_list.iter().enumerate() {
                    if idx % 10 == 9 {
                        scope.send_message(SingleAlbumMessage::UpdateProgress(
                            idx as f64 / total_elements as f64,
                        ));
                    }
                    let blob = access.fetch_thumbnail(entry, None).await?;

                    blob_ref.replace(Some(blob));
                }
                scope.send_message(SingleAlbumMessage::FinishProgress);*/
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
#[derive(Properties, PartialEq)]
struct ImageRowProps {
    entries: Box<[AlbumEntry]>,
    rendered: bool,
    scroll_top: i32,
}

#[function_component]
fn ImageRow(
    ImageRowProps {
        entries,
        rendered,
        scroll_top,
    }: &ImageRowProps,
) -> Html {
    let with_ratio: u32 = entries
        .iter()
        .map(|e| e.target_width * 1000 / e.target_height)
        .sum();
    //let total_height: u32 = entries.iter().map(|e| e.target_width).sum();
    let style = format!("aspect-ratio: {}/1000;", with_ratio);
    let window = window().unwrap();
    let height = window.inner_height().unwrap().as_f64().unwrap();
    let div_ref = use_node_ref();

    let rendered = use_state_eq(|| false);

    {
        let div_ref = div_ref.clone();
        let rendered = rendered.clone();

        use_effect_with((div_ref, *scroll_top), move |(div_ref, scroll_top)| {
            let div = div_ref
                .cast::<HtmlElement>()
                .expect("div_ref not attached to div element");
            let rect = div.get_bounding_client_rect();
            let visible = rect.bottom() >= -height && rect.top() <= 2.0 * height;
            //info!("Visible: {visible}");
            rendered.set(visible);
        });
    }

    html!(<div class="image-row" {style} ref={div_ref}>{
        for entries.iter().cloned().map(|entry|{
            html!(<Image {entry} rendered={*rendered}/>)
        })
    }</div>)
}


