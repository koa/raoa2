use yew::{html, Html, UseStateHandle};
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
    pub fn switch_main(self, top: i32, height: i32, scroll_top: i32) -> Html {
        match self {
            AppRoute::AlbumList => html! {<AlbumList/>},
            AppRoute::Album { id } => {
                html! {<SingleAlbum {id} top={top} height={height} scroll_top={scroll_top}/>}
            }
        }
    }
}
