use std::rc::Rc;

use gloo::timers::callback::Timeout;
use google_signin_client::{
    initialize, prompt_async, render_button, ButtonType, DismissedReason, GsiButtonConfiguration,
    IdConfiguration, NotDisplayedReason, PromptResult,
};
use log::{info, warn};
use patternfly_yew::prelude::{Alert, AlertGroup, AlertType, BackdropViewer, Page, ToastViewer};
use web_sys::HtmlElement;
use yew::{
    function_component, html, platform::spawn_local, Context, ContextProvider, Html, NodeRef,
    Properties,
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
    //user_session: UserSessionData,
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
    TokenReceived(Box<str>),
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

            AppMessage::TokenReceived(token) => {
                if let Some(mut session) = self.data.as_deref().map(DataAccess::login_data_mut) {
                    session.update_token(token);
                    self.error_state = None;
                    session.is_token_valid()
                } else {
                    false
                }
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
                .map(|v| UserSessionData::is_token_valid(v))
                .unwrap_or(true),
            AppMessage::DataAccessError(error) => {
                self.error_state = Some(ErrorState::DataAccessError(error));
                true
            }
        }
    }

    fn view(&self, ctx: &Context<Self>) -> Html {
        let login_data = self.data.as_deref().map(DataAccess::login_data);
        let login_valid = login_data.map(|t| t.is_token_valid()).unwrap_or(false);
        if let (Some(client_id), false) = (&self.client_id, login_valid) {
            if self.error_state.is_none() {
                let mut configuration = IdConfiguration::new(client_id.clone().into_string());
                //configuration.set_auto_select(true);
                let link = ctx.link().clone();
                configuration.set_callback(Box::new(move |response| {
                    link.send_message(AppMessage::TokenReceived(
                        response.credential().to_string().into_boxed_str(),
                    ));
                }));
                let link = ctx.link().clone();
                initialize(configuration);
                spawn_local(async move {
                    let result = prompt_async().await;
                    if result != PromptResult::Dismissed(DismissedReason::CredentialReturned) {
                        link.send_message(AppMessage::LoginFailed(result))
                    }
                });
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
                ErrorState::PromptResult(PromptResult::NotDisplayed(
                    NotDisplayedReason::SuppressedByUser,
                )) => {
                    html! {
                        <AlertGroup>
                            <Alert inline=true title="Not Displayed" r#type={AlertType::Danger}>{"Suppressed by user"}</Alert>
                        </AlertGroup>
                    }
                }
                ErrorState::PromptResult(PromptResult::Skipped(skipped_reason)) => {
                    html! {
                        <AlertGroup>
                            <Alert inline=true title="Skipped" r#type={AlertType::Danger}>{format!("{skipped_reason:?}")}</Alert>
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
                ErrorState::PromptResult(PromptResult::NotDisplayed(reason)) => {
                    html! {
                        <AlertGroup>
                            <Alert inline=true title="Not Displayed" r#type={AlertType::Danger}>{format!("{reason:?}")}</Alert>
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
            render_button(
                login_button_ref,
                GsiButtonConfiguration::new(ButtonType::Standard),
            );
        }

        if first_render {
            let scope = ctx.link().clone();
            let login_ref = self.login_button_ref.clone();
            spawn_local(async move {
                match DataAccess::new(login_ref).await {
                    Ok(data) => {
                        scope.send_message(AppMessage::DataAccessInitialized(data));
                    }
                    Err(err) => {
                        warn!("Error from server: {err}");
                        scope.send_message(AppMessage::DataAccessError(err));
                    }
                }
                match fetch_settings().await {
                    Ok(ClientProperties { google_client_id }) => {
                        scope.send_message(AppMessage::ClientIdReceived(google_client_id));
                    }
                    Err(err) => {
                        warn!("Error from server: {err}");
                        scope.send_message(AppMessage::ClientError(err));
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
    html! {
        <BackdropViewer>
            <ToastViewer>
                <Page>
                    <RouterSwitch<AppRoute>
                        render = { AppRoute::switch_main}
                    />
                    {props.login_button.clone()}
                </Page>
            </ToastViewer>
        </BackdropViewer>
    }
}
