use crate::data::{DataAccess, DataAccessError};
use patternfly_yew::prelude::Spinner;
use std::rc::Rc;
use yew::{function_component, html, props, use_context, Html, Properties};
use yew_hooks::{use_async, UseAsyncHandle};

#[derive(Clone, PartialEq, Properties)]
pub struct EntryHeaderProps {
    pub album_id: String,
    pub entry_id: String,
}

#[function_component]
pub fn EntryHeader(props: &EntryHeaderProps) -> Html {
    let access = use_context::<Rc<DataAccess>>();
    let props = props.clone();
    let title: UseAsyncHandle<Box<str>, DataAccessError> = use_async(async move {
        Ok((if let Some(access) = access {
            access
                .album_entry_data(&props.album_id, &props.entry_id)
                .await?
                .map(|details| details.name.clone())
        } else {
            None
        })
        .unwrap_or_else(|| props.entry_id.into()))
    });
    if !title.loading && title.data.is_none() && title.error.is_none() {
        title.run();
    }
    html! {<>{
        if let Some(data)=&title.data{
            html!(data)
        }else if title.loading {
            html!(<Spinner/>)
        }else{
            html!("Loaded")
        }
    }</>}
}
