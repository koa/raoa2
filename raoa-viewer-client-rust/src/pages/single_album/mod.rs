use crate::{
    components::image::Image,
    data::{storage::AlbumEntry, DataAccess, DataAccessError, DataFetchMessage},
    error::FrontendError,
    pages::{
        app::routing::{AlbumRoute, AlbumsRoute, AppRoute},
        single_album::row_iterator::{BlockIteratorTrait, RowIteratorTrait},
    },
};
use chrono::Local;
use log::error;
use patternfly_yew::prelude::{Progress, Spinner};
use std::{cmp::Ordering, rc::Rc};
use tokio_stream::StreamExt;
use web_sys::{window, HtmlElement};
use yew::{
    function_component, html, html::Scope, platform::spawn_local, use_effect_with, use_node_ref,
    use_state_eq, Component, Context, Html, NodeRef, Properties,
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

mod util;

mod row_iterator;
mod sections {
    use crate::data::storage::AlbumEntry;
    use crate::pages::single_album::row_iterator::ImageBlock;
    use crate::pages::single_album::util::{IteratorSegment, SameSplitCondition};
    use chrono::Local;
    use chrono::{DateTime, Datelike};
    use std::marker::PhantomData;
    use yew::Html;

    pub struct Section {
        header: Html,
        blocks: Box<[ImageBlock]>,
    }

    struct SectionIterator<
        E: Clone,
        I: Iterator<Item = E>,
        S: Fn(&E) -> D,
        D: PartialEq + Clone,
        C: for<'a> FnMut(
            IteratorSegment<'a, E, I, SameSplitCondition<'a, D, S, E>, &'_ mut Option<E>>,
        ) -> R,
        R,
    > {
        iterator: I,
        splitter: S,
        collector: C,
        last_entry: Option<E>,
        discriminant_phantom: PhantomData<D>,
    }
    impl<
            E: Clone,
            I: Iterator<Item = E>,
            S: Fn(&E) -> D,
            D: PartialEq + Clone,
            C: for<'a> FnMut(
                IteratorSegment<'a, E, I, SameSplitCondition<'a, D, S, E>, &'_ mut Option<E>>,
            ) -> R,
            R,
        > Iterator for SectionIterator<E, I, S, D, C, R>
    {
        type Item = R;

        fn next(&mut self) -> Option<Self::Item> {
            let mut last_entry = self.last_entry.take();
            if last_entry.is_none() {
                last_entry = self.iterator.next();
            }
            let last_entry = last_entry.take()?;
            let splitter = &self.splitter;

            let condition = SameSplitCondition::new(&last_entry, splitter);
            Some((self.collector)(IteratorSegment::new(
                Some(last_entry),
                &mut self.iterator,
                condition,
                Some(&mut self.last_entry),
            )))
        }
    }
    fn split_days(
        entries: impl IntoIterator<Item = AlbumEntry>,
    ) -> impl Iterator<Item = Box<[AlbumEntry]>> {
        SectionIterator {
            iterator: entries.into_iter(),
            splitter: |album| {
                album.created.as_ref().map(|date| {
                    let dt: DateTime<Local> = (*date.as_ref()).into();
                    (dt.year(), dt.month(), dt.day())
                })
            },
            collector: |i| i.collect::<Box<[_]>>(),
            last_entry: None,
            discriminant_phantom: Default::default(),
        }
    }
}

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

    fn update(&mut self, _ctx: &Context<Self>, msg: Self::Message) -> bool {
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
                <ol class="image-blocks">
                {
                    for self.entries.iter().calculate_rows(6.0).calculate_blocks(40.0).map(|row| {
                        let entries=row;
                        html!{
                            <li>
                                <ImageBlock {entries} {scroll_top}/>
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
                if let Err(e) = fetch_album_content(&scope, &id).await {
                    error!("Cannot get album list: {e}");
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
    entries: row_iterator::ImageRow,
    scroll_top: i32,
}

#[function_component]
fn ImageRow(
    ImageRowProps {
        entries,
        scroll_top,
    }: &ImageRowProps,
) -> Html {
    let with_ratio: u32 = (entries.height() * 1000.0) as u32;
    let style = format!("aspect-ratio: 1000/{with_ratio};");
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
    let entries = if *rendered { Some(entries) } else { None };

    html!(<div class="image-row" {style} ref={div_ref}>{
        for entries.iter().flat_map(|row| row.images().iter()).cloned().map(|entry|{
            let target=AppRoute::Albums {view: AlbumsRoute::Album {id: entry.album_id.to_string(),view: AlbumRoute::Entry {id: entry.entry_id.to_string()}}};
            html!(<Image {entry} rendered={*rendered} {target}/>)
        })
    }</div>)
}
#[derive(Properties, PartialEq)]
struct ImageBlockProps {
    entries: row_iterator::ImageBlock,
    scroll_top: i32,
}
#[function_component]
fn ImageBlock(
    ImageBlockProps {
        entries,
        scroll_top,
    }: &ImageBlockProps,
) -> Html {
    let height = entries.rows().iter().map(|r| r.height()).sum::<f64>() * 1000.0;
    let style = format!("aspect-ratio: 1000/{height};");

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
            rendered.set(visible);
        });
    }
    let entries = if *rendered { Some(entries) } else { None };

    html!(<div class="image-block" {style} ref={div_ref}>
           <ol class="image-rows">{
        for entries.iter().flat_map(|b| b.rows().iter()).cloned().map(|entries|{
            html!(<li><ImageRow {entries} {scroll_top}/></li>)
        })
    }</ol></div>)
}
