use std::cmp::Ordering;
use std::rc::Rc;

use gloo_events::EventListener;
use log::{error, info};
use patternfly_yew::prelude::{Progress, Spinner};
use tokio_stream::StreamExt;
use web_sys::{window, HtmlElement, Blob};
use yew::{function_component, html, html::Scope, platform::spawn_local, use_effect_with, use_node_ref, use_state_eq, Component, Context, Html, NodeRef, Properties, use_state, use_context};

use crate::{
    data::{storage::AlbumEntry, DataAccess, DataAccessError, DataFetchMessage, MediaUrl},
    error::FrontendError,
};
#[derive(Properties, PartialEq, Clone, Debug)]
pub struct ImageProps {
    pub entry: AlbumEntry,
    pub rendered: bool,
}

pub struct Image {
    blob_url: Option<MediaUrl>,
    length: u16,
    div_ref: NodeRef,
    entry: AlbumEntry,
    rendered: bool,
}

#[derive(Debug)]
pub enum ImageMessage {
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
        let onresize = ctx.link().callback(|evt| {
            info!("Resized: {evt:?}");
            ImageMessage::Resized
        });
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

