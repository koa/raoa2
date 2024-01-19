use yew::{html, Html};
use yew_nested_router::Target;

use crate::pages::album_list::AlbumList;

#[derive(Debug, Default, Clone, PartialEq, Eq, Target)]
pub enum AppRoute {
    #[default]
    AlbumList,
    Group,
}

impl AppRoute {
    pub fn switch_main(self) -> Html {
        match self {
            AppRoute::AlbumList => html! {<AlbumList/>},
            AppRoute::Group => html! {"Group"},
        }
    }
}
