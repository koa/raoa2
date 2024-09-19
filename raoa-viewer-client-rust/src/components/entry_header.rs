use crate::data::DataAccess;
use std::rc::Rc;
use yew::{html, html::Scope, platform::spawn_local, Component, Context, Html, Properties};

#[derive(Clone, PartialEq, Properties)]
pub struct EntryHeaderProps {
    pub album_id: String,
    pub entry_id: String,
}

pub struct EntryHeader {
    album_id: Box<str>,
    entry_id: Box<str>,
    loaded_title: Option<Box<str>>,
}

impl EntryHeader {
    fn fetch_title(&self, scope: &Scope<EntryHeader>) {
        let scope = scope.clone();
        let (access, _) = scope
            .context::<Rc<DataAccess>>(Default::default())
            .expect("Context missing");
        let album_id = self.album_id.clone();
        let entry_id = self.entry_id.clone();
        spawn_local(async move {
            if let Some(title) = access
                .album_entry_data(&album_id, &entry_id)
                .await
                .ok()
                .flatten()
                .map(|details| details.name.clone())
            {
                scope.send_message(EntryHeaderMsg::TitleUpdated(title));
            }
        });
    }
}
#[derive(Debug)]
pub enum EntryHeaderMsg {
    TitleUpdated(Box<str>),
}

impl Component for EntryHeader {
    type Message = EntryHeaderMsg;
    type Properties = EntryHeaderProps;

    fn create(ctx: &Context<Self>) -> Self {
        let props = ctx.props();
        EntryHeader {
            album_id: props.album_id.clone().into_boxed_str(),
            entry_id: props.entry_id.clone().into_boxed_str(),
            loaded_title: None,
        }
    }

    fn update(&mut self, _ctx: &Context<Self>, msg: Self::Message) -> bool {
        match msg {
            EntryHeaderMsg::TitleUpdated(title) => {
                self.loaded_title = Some(title);
                true
            }
        }
    }

    fn changed(&mut self, ctx: &Context<Self>, _old_props: &Self::Properties) -> bool {
        let new_props = ctx.props();
        self.album_id = new_props.album_id.clone().into_boxed_str();
        if new_props.entry_id.as_str() != self.entry_id.as_ref() {
            self.entry_id = new_props.entry_id.clone().into_boxed_str();
            self.fetch_title(ctx.link());
            true
        } else {
            false
        }
    }

    fn view(&self, ctx: &Context<Self>) -> Html {
        html! {<>{
            if let Some(data)=&self.loaded_title{
                html!(data)
            }else {
                html!(self.entry_id.as_ref())
            }
        }</>}
    }

    fn rendered(&mut self, ctx: &Context<Self>, first_render: bool) {
        if first_render {
            self.fetch_title(ctx.link());
        }
    }
}
