use yew::{html, Html};
use yew_nested_router::Target;

use crate::pages::album_list::AlbumList;
use crate::pages::single_album::SingleAlbum;

#[derive(Debug, Default, Clone, PartialEq, Eq, Target)]
pub enum AppRoute {
    #[default]
    AlbumList,
    Album {
        id: String,
    },
}

impl AppRoute {
    pub fn switch_main(self) -> Html {
        match self {
            AppRoute::AlbumList => html! {<AlbumList/>},
            AppRoute::Album { id } => html! {<SingleAlbum {id}/>},
        }
    }
}
