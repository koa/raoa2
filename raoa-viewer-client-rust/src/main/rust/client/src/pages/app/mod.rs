use std::rc::Rc;

use gloo::timers::callback::Timeout;
use google_signin_client::{
    initialize, prompt_async, DismissedReason, IdConfiguration, NotDisplayedReason, PromptResult,
};
use log::{info, warn};
use patternfly_yew::prelude::{Alert, AlertGroup, AlertType, BackdropViewer, Page, ToastViewer};
use web_sys::{Event, HtmlElement};
use yew::virtual_dom::ListenerKind::{onclick, onscroll};
use yew::{
    function_component, html, platform::spawn_local, props, use_state_eq, Callback, Context,
    ContextProvider, Html, NodeRef, Properties, TargetCast,
};
use yew_nested_router::{prelude::Switch as RouterSwitch, Router};

use crate::data::{DataAccess, DataAccessError};
use crate::{
    data::{server_api::fetch_settings, session::UserSessionData},
    error::FrontendError,
    model::ClientProperties,
    pages::app::routing::AppRoute,
};

pub mod routing;

#[derive(Debug, Default)]
pub struct App {
    client_id: Option<Box<str>>,
    data: Option<Rc<DataAccess>>,
    error_state: Option<ErrorState>,
    login_button_ref: NodeRef,
    running_timeout: Option<Timeout>,
}

#[derive(Debug)]
enum ErrorState {
    FrontendError(FrontendError),
    DataAccessError(DataAccessError),
    PromptResult(PromptResult),
}

#[derive(Debug)]
pub enum AppMessage {
    DataAccessInitialized(Rc<DataAccess>),
    ClientIdReceived(Box<str>),
    ClientError(FrontendError),
    LoginFailed(PromptResult),
    DataAccessError(DataAccessError),
    CheckSession,
}

impl yew::Component for App {
    type Message = AppMessage;
    type Properties = ();

    fn create(ctx: &Context<Self>) -> Self {
        Default::default()
    }

    fn update(&mut self, _ctx: &Context<Self>, msg: Self::Message) -> bool {
        match msg {
            AppMessage::DataAccessInitialized(data) => {
                self.data = Some(data);
                true
            }
            AppMessage::ClientIdReceived(client_id) => {
                self.client_id = Some(client_id);
                self.error_state = None;
                true
            }
            AppMessage::ClientError(error) => {
                self.error_state = Some(ErrorState::FrontendError(error));
                true
            }
            AppMessage::LoginFailed(prompt) => {
                self.error_state = Some(ErrorState::PromptResult(prompt));
                true
            }
            AppMessage::CheckSession => self
                .data
                .as_deref()
                .map(DataAccess::login_data)
                .as_ref()
                .and_then(|v| {
                    info!("Drop here");
                    v.borrow().as_ref().map(|s| s.is_token_valid())
                })
                .unwrap_or(true),
            AppMessage::DataAccessError(error) => {
                self.error_state = Some(ErrorState::DataAccessError(error));
                true
            }
        }
    }

    fn view(&self, ctx: &Context<Self>) -> Html {
        let login_valid = self
            .data
            .as_deref()
            .map(DataAccess::login_data)
            .and_then(|t| t.borrow().as_ref().map(|d| d.is_token_valid()))
            .unwrap_or(false);
        if let (Some(client_id), false) = (&self.client_id, login_valid) {
            if self.error_state.is_none() {
                let mut configuration = IdConfiguration::new(client_id.clone().into_string());
                //configuration.set_auto_select(true);
                /*let link = ctx.link().clone();
                let link = ctx.link().clone();
                initialize(configuration);
                spawn_local(async move {
                    let result = prompt_async().await;
                    info!("Result: {result:?}");
                    if result != PromptResult::Dismissed(DismissedReason::CredentialReturned) {
                        link.send_message(AppMessage::LoginFailed(result))
                    }
                });*/
            }
        }
        if let Some(context) = self.data.clone() {
            let login_button = html!(<div ref={self.login_button_ref.clone()}></div>);
            html! {
            <ContextProvider<Rc<DataAccess>> {context}>
                <Router<AppRoute> default={AppRoute::default()}>
                    <MainPage {login_button}/>
                </Router<AppRoute>>
            </ContextProvider<Rc<DataAccess>>>
            }
        } else if let Some(error) = &self.error_state {
            let error_message = match error {
                ErrorState::PromptResult(PromptResult::NotDisplayed) => {
                    html! {
                        <AlertGroup>
                            <Alert inline=true title="Not Displayed" r#type={AlertType::Danger}>{"Suppressed by user"}</Alert>
                        </AlertGroup>
                    }
                }
                ErrorState::PromptResult(PromptResult::Skipped) => {
                    html! {
                        <AlertGroup>
                            <Alert inline=true title="Skipped" r#type={AlertType::Danger}></Alert>
                        </AlertGroup>
                    }
                }
                ErrorState::PromptResult(PromptResult::Dismissed(reason)) => {
                    html! {
                        <AlertGroup>
                            <Alert inline=true title="Dismissed" r#type={AlertType::Danger}>{format!("{reason:?}")}</Alert>
                        </AlertGroup>
                    }
                }
                ErrorState::FrontendError(fe) => fe.render_error_message(),
                ErrorState::DataAccessError(de) => de.render_error_message(),
            };
            html! {
                <>
                    {error_message}
                    <div ref={self.login_button_ref.clone()}></div>
                </>
            }
        } else {
            html! {
                <>
                <p>{"yew works"}</p>
                <p>{ format!("Client id: {:?}",self.client_id)}</p>
                <p>{ format!("Error: {:?}",self.error_state)}</p>
                <p>{ format!("User Session: {:?}",self.data)}</p>
                </>
            }
        }
    }

    fn rendered(&mut self, ctx: &Context<Self>, first_render: bool) {
        if let Some(login_button_ref) = self.login_button_ref.cast::<HtmlElement>() {
            let login_valid = self
                .data
                .as_ref()
                .map(|da| da.is_token_valid())
                .unwrap_or(false);
            login_button_ref.set_hidden(self.client_id.is_none() && !login_valid);
        }

        if first_render {
            let scope = ctx.link().clone();
            let login_ref = self.login_button_ref.clone();
            spawn_local(async move {
                let google_client_id = match fetch_settings().await {
                    Ok(ClientProperties { google_client_id }) => {
                        scope.send_message(AppMessage::ClientIdReceived(google_client_id.clone()));
                        google_client_id
                    }
                    Err(err) => {
                        warn!("Error from server: {err}");
                        scope.send_message(AppMessage::ClientError(err));
                        return;
                    }
                };
                match DataAccess::new(login_ref, google_client_id).await {
                    Ok(data) => {
                        scope.send_message(AppMessage::DataAccessInitialized(data));
                    }
                    Err(err) => {
                        warn!("Error from server: {err}");
                        scope.send_message(AppMessage::DataAccessError(err));
                    }
                }
            });
        }
    }
}

#[derive(PartialEq, Properties)]
struct MainProps {
    login_button: Html,
}

#[function_component(MainPage)]
fn main_page(props: &MainProps) -> Html {
    let top = use_state_eq(|| 0);
    let height = use_state_eq(|| 0);
    let scroll_top = use_state_eq(|| 0);
    let on_main_scroll = {
        let top = top.clone();
        let height = height.clone();
        let scroll_top = scroll_top.clone();
        Callback::from(move |event: Event| {
            if let Some(target) = event.target_dyn_into::<HtmlElement>() {
                top.set(target.offset_top());
                height.set(target.offset_height());
                scroll_top.set(target.scroll_top());
                //info!("Scroll Top: {}", target.scroll_top());
            }
        })
    };
    let login_button = props.login_button.clone();
    let tools = html! {
        <div style="margin-left: auto">{login_button}</div>
    };
    html! {
        <BackdropViewer>
            <ToastViewer>
                <Page {on_main_scroll} {tools}>
                    <RouterSwitch<AppRoute>
                        render = {move |route:AppRoute| route.switch_main(*top, *height, *scroll_top)}
                    />
                </Page>
            </ToastViewer>
        </BackdropViewer>
    }
}
