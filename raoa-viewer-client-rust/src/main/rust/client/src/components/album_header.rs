use crate::data::{DataAccess, DataAccessError};
use log::info;
use patternfly_yew::prelude::Spinner;
use std::rc::Rc;
use yew::{function_component, html, use_context, Html, Properties};
use yew_hooks::{use_async, UseAsyncHandle};

#[derive(Clone, PartialEq, Properties)]
pub struct AlbumHeaderProps {
    pub id: String,
}

#[function_component]
pub fn AlbumHeader(props: &AlbumHeaderProps) -> Html {
    let access = use_context::<Rc<DataAccess>>();
    let id = props.id.to_string();
    let title: UseAsyncHandle<Box<str>, DataAccessError> = use_async(async move {
        Ok((if let Some(access) = access {
            access
                .album_data(&id)
                .await?
                .map(|details| details.name.clone())
        } else {
            None
        })
        .unwrap_or_else(|| id.into()))
    });
    info!(
        "Loading: {},{},{}",
        title.loading,
        title.data.is_some(),
        title.error.is_some()
    );
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
