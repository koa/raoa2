use std::cmp::Ordering;
use std::rc::Rc;

use gloo_events::EventListener;
use log::{error, info};
use patternfly_yew::prelude::{Progress, Spinner};
use tokio_stream::StreamExt;
use web_sys::{window, HtmlElement};
use yew::{
    function_component, html, html::Scope, platform::spawn_local, use_effect_with, use_node_ref,
    use_state_eq, Component, Context, Html, NodeRef, Properties,
};

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
    listener: Option<EventListener>,
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

trait RowIteratorTrait<'a, I: Iterator<Item = &'a AlbumEntry>> {
    fn calculate_rows(self, width: f64) -> RowIterator<'a, I>;
}
impl<'a, I: Iterator<Item = &'a AlbumEntry>> RowIteratorTrait<'a, I> for I {
    fn calculate_rows(self, width: f64) -> RowIterator<'a, I> {
        RowIterator {
            iterator: self,
            remainder: None,
            width,
        }
    }
}

struct RowIterator<'a, I: Iterator<Item = &'a AlbumEntry>> {
    iterator: I,
    remainder: Option<&'a AlbumEntry>,
    width: f64,
}

impl<'a, I: Iterator<Item = &'a AlbumEntry>> Iterator for RowIterator<'a, I> {
    type Item = Box<[AlbumEntry]>;

    fn next(&mut self) -> Option<Self::Item> {
        let mut row = Vec::new();
        let mut current_width = 0.0;
        if let Some(entry) = self.remainder.take() {
            current_width += entry.target_width as f64 / entry.target_height as f64;
            row.push(entry.clone());
        }
        for entry in self.iterator.by_ref() {
            current_width += entry.target_width as f64 / entry.target_height as f64;
            if !row.is_empty() && current_width > self.width {
                self.remainder = Some(entry);
                break;
            }
            row.push(entry.clone());
        }
        if row.is_empty() {
            None
        } else {
            Some(row.into_boxed_slice())
        }
    }
}

impl Component for SingleAlbum {
    type Message = SingleAlbumMessage;
    type Properties = SingleAlbumProps;

    fn create(ctx: &Context<Self>) -> Self {
        let props = ctx.props();
        let div_ref = Default::default();

        let window = window().unwrap();

        info!("Install resize event on {window:?}");
        let listener = Some(EventListener::new(&window, "resize", |event| {
            info!("On Resize: {event:?}")
        }));
        info!("Event: {:?}", listener);
        info!("Original scroll top: {}", props.scroll_top);

        Self {
            id: props.id.clone().into_boxed_str(),
            entries: Rc::new(Box::new([])),
            processing: Default::default(),
            listener,
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
                    for self.entries.iter().calculate_rows(4.0).into_iter().map(|row| {
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

#[derive(Properties, PartialEq, Clone, Debug)]
struct ImageProps {
    entry: AlbumEntry,
    rendered: bool,
}

struct Image {
    blob_url: Option<MediaUrl>,
    length: u16,
    div_ref: NodeRef,
    entry: AlbumEntry,
    rendered: bool,
}

#[derive(Debug)]
enum ImageMessage {
    SetLength(u16),
    SetDataUrl(MediaUrl, u16),
    ShowImage(bool),
    Resized,
}

impl Component for Image {
    type Message = ImageMessage;
    type Properties = ImageProps;

    fn create(ctx: &Context<Self>) -> Self {
        let props = ctx.props();
        Self {
            blob_url: None,
            length: 0,
            div_ref: Default::default(),
            entry: props.entry.clone(),
            rendered: props.rendered,
        }
    }

    fn update(&mut self, ctx: &Context<Self>, msg: Self::Message) -> bool {
        //info!("msg: {msg:?}");
        match msg {
            ImageMessage::SetLength(l) => {
                if l != self.length {
                    self.length = l;
                    self.blob_url = None;
                    true
                } else {
                    false
                }
            }
            ImageMessage::SetDataUrl(url, l) => {
                if l == self.length {
                    self.blob_url = Some(url);
                    true
                } else {
                    false
                }
            }
            ImageMessage::ShowImage(visible) => {
                if self.rendered != visible {
                    self.rendered = visible;
                    true
                } else {
                    false
                }
            }
            ImageMessage::Resized => true,
        }
    }

    fn changed(&mut self, ctx: &Context<Self>, _old_props: &Self::Properties) -> bool {
        let props = ctx.props();
        let mut modified = false;
        if self.entry != props.entry {
            self.entry = props.entry.clone();
            self.blob_url = None;
            modified = true;
        }
        if self.rendered != props.rendered {
            self.rendered = props.rendered;
            modified = true;
        }

        modified
    }

    fn view(&self, ctx: &Context<Self>) -> Html {
        let style = format!(
            "aspect-ratio: {}/{};",
            self.entry.target_width, self.entry.target_height,
        );
        let content = match self.blob_url.as_ref() {
            None => {
                html!({ &*self.entry.name })
            }
            Some(src) => {
                let src = src.to_string();
                html!(<img {src}/>)
            }
        };
        let div_ref = self.div_ref.clone();
        let onresize = ctx.link().callback(|_| ImageMessage::Resized);
        html!(<div class="image-entry" {style} {onresize} ref={div_ref}>{content}</div>)
    }

    fn rendered(&mut self, ctx: &Context<Self>, first_render: bool) {
        if !self.rendered {
            return;
        }
        let scope = ctx.link();
        let div = self
            .div_ref
            .cast::<HtmlElement>()
            .expect("div_ref not attached to div element");
        //let window = window().unwrap();
        //let window_height = window.inner_height().unwrap().as_f64().unwrap();
        let rect = div.get_bounding_client_rect();
        //let visible = rect.bottom() > 0.0 && rect.top() < window_height;
        //scope.send_message(ImageMessage::ShowImage(visible));
        //info!("Size: {}:{}", rect.width(), rect.height());
        let length = find_target_length(f64::max(rect.width(), rect.height()) as u16);
        scope.send_message(ImageMessage::SetLength(length));
        if self.rendered && self.blob_url.is_none() && length > 0 {
            let (access, _) = scope
                .context::<Rc<DataAccess>>(Default::default())
                .expect("Context missing");
            let entry = self.entry.clone();
            let scope = scope.clone();
            spawn_local(async move {
                if let Ok(data) = access.fetch_thumbnail(&entry, Some(length)).await {
                    scope.send_message(ImageMessage::SetDataUrl(data, length))
                }
            });
        }
    }
}

fn find_target_length(length: u16) -> u16 {
    let mut result = 25;
    while result < length {
        result <<= 1;
    }
    result
}
