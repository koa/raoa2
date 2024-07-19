use crate::components::album_header::AlbumHeader;
use crate::pages::album_list::AlbumList;
use crate::pages::single_album::SingleAlbum;
use patternfly_yew::prelude::{
    Breadcrumb, BreadcrumbRouterItem, Nav, Toolbar, ToolbarContent, ToolbarItem,
};
use yew::{html, Html};
use yew_nested_router::components::Link;
use yew_nested_router::Target;

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
    pub fn switch_header(self, login_button: Html) -> Html {
        let breadcrumbs = match self {
            AppRoute::AlbumList => {
                html! {
                            <Breadcrumb>
                                <BreadcrumbRouterItem<AppRoute> to={AppRoute::AlbumList}>{"Album List"}</BreadcrumbRouterItem<AppRoute>>
                            </Breadcrumb>
                }
            }
            AppRoute::Album { id } => {
                html! {
                            <Breadcrumb>
                                <BreadcrumbRouterItem<AppRoute> to={AppRoute::AlbumList}>{"Album List"}</BreadcrumbRouterItem<AppRoute>>
                                <BreadcrumbRouterItem<AppRoute> to={AppRoute::Album {id: id.clone()}}><AlbumHeader {id}/></BreadcrumbRouterItem<AppRoute>>
                            </Breadcrumb>
                }
            }
        };
        html! {<Toolbar>
            <ToolbarContent>
                <ToolbarItem>
                    {breadcrumbs}
                </ToolbarItem>
                <ToolbarItem>
                    {login_button}
                </ToolbarItem>
            </ToolbarContent>
        </Toolbar>}
    }
}
