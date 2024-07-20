use crate::{
    components::album_header::AlbumHeader,
    pages::{album_list::AlbumList, single_album::SingleAlbum},
};
use log::info;
use patternfly_yew::prelude::{
    Breadcrumb, BreadcrumbRouterItem, Nav, Toolbar, ToolbarContent, ToolbarElementModifier,
    ToolbarItem,
};
use yew::{html, Html};
use yew_nested_router::Target;

#[derive(Clone, Debug, PartialEq, Eq, Target)]
pub enum AppRoute {
    Albums {
        #[target(nested)]
        view: AlbumsRoute,
    },
}
#[derive(Debug, Clone, PartialEq, Eq, Target)]
pub enum AlbumsRoute {
    #[target(index)]
    List,
    Album {
        id: String,
        #[target(nested)]
        view: AlbumRoute,
    },
}
#[derive(Debug, Clone, PartialEq, Eq, Target)]
pub enum AlbumRoute {
    #[target(index)]
    List,
    Entry {
        id: String,
    },
}
impl AppRoute {
    pub fn switch_main(self, top: i32, height: i32, scroll_top: i32) -> Html {
        info!("Route: {self:?}");
        match self {
            AppRoute::Albums { view } => match view {
                AlbumsRoute::List => {
                    html! {<AlbumList/>}
                }
                AlbumsRoute::Album { id: album_id, view } => match view {
                    AlbumRoute::List => {
                        html! {<SingleAlbum id={album_id} top={top} height={height} scroll_top={scroll_top}/>}
                    }
                    AlbumRoute::Entry { id: entry_id } => {
                        html!("Dummy")
                    }
                },
            },
        }
    }
    pub fn switch_header(self, login_button: Html) -> Html {
        let breadcrumbs = match self {
            AppRoute::Albums { view } => match view {
                AlbumsRoute::List => {
                    html! {
                        <Breadcrumb>
                            <BreadcrumbRouterItem<AppRoute> to={AppRoute::Albums {view}}>{"Album List"}</BreadcrumbRouterItem<AppRoute>>
                        </Breadcrumb>
                    }
                }
                AlbumsRoute::Album { id: album_id, view } => match view {
                    AlbumRoute::List => {
                        html! {
                            <Breadcrumb>
                                <BreadcrumbRouterItem<AppRoute> to={AppRoute::Albums {view: AlbumsRoute::List}}>{"Album List"}</BreadcrumbRouterItem<AppRoute>>
                                <BreadcrumbRouterItem<AppRoute> to={AppRoute::Albums {view: AlbumsRoute::Album {id: album_id.clone(),view: AlbumRoute::List} }}><AlbumHeader id={album_id.clone()}/></BreadcrumbRouterItem<AppRoute>>
                            </Breadcrumb>
                        }
                    }
                    AlbumRoute::Entry { id: entry_id } => {
                        html! {
                            <Breadcrumb>
                                <BreadcrumbRouterItem<AppRoute> to={AppRoute::Albums {view: AlbumsRoute::List}}>{"Album List"}</BreadcrumbRouterItem<AppRoute>>
                                <BreadcrumbRouterItem<AppRoute> to={AppRoute::Albums {view: AlbumsRoute::Album {id: album_id.clone(),view: AlbumRoute::List} }}><AlbumHeader id={album_id.clone()}/></BreadcrumbRouterItem<AppRoute>>
                                <BreadcrumbRouterItem<AppRoute> to={AppRoute::Albums {view: AlbumsRoute::Album {id: album_id.clone(),view: AlbumRoute::Entry {id: entry_id.clone()}} }}>{"Photo"}</BreadcrumbRouterItem<AppRoute>>
                            </Breadcrumb>
                        }
                    }
                },
            },
        };
        html! {<Toolbar>
            <ToolbarContent>
                <ToolbarItem>
                    {breadcrumbs}
                </ToolbarItem>
                <ToolbarItem modifiers={[ToolbarElementModifier::Right]}>
                    {login_button}
                </ToolbarItem>
            </ToolbarContent>
        </Toolbar>}
    }
}
#[cfg(test)]
mod test {
    use crate::pages::app::routing::{AlbumsRoute, AppRoute};
    use yew_nested_router::prelude::Target;

    #[test]
    fn test_parse_album_path() {
        let found_path = AppRoute::parse_path(&["albums", ""]);
        assert_eq!(
            found_path,
            Some(AppRoute::Albums {
                view: AlbumsRoute::List,
            })
        );
    }
    #[test]
    fn test_format_path() {
        let route = AppRoute::Albums {
            view: AlbumsRoute::List,
        };
        let path = route.render_path();
        assert_eq!(path, vec!["albums", ""]);
    }
}
