use gloo::timers::callback::Timeout;
use google_signin_client::{
    initialize, prompt_async, render_button, ButtonType, DismissedReason, GsiButtonConfiguration,
    IdConfiguration, NotDisplayedReason, PromptResult,
};
use log::warn;
use patternfly_yew::prelude::{Alert, AlertGroup, AlertType};
use web_sys::HtmlElement;
use yew::platform::spawn_local;
use yew::{html, Context, Html, NodeRef};

use crate::data::UserSessionData;
use crate::error::FrontendError;
use crate::model::ClientProperties;
use crate::server_api::fetch_settings;

#[derive(Debug, Default)]
pub struct App {
    client_id: Option<Box<str>>,
    user_session: UserSessionData,
    error_state: Option<ErrorState>,
    login_button_ref: NodeRef,
    running_timeout: Option<Timeout>,
}

#[derive(Debug)]
enum ErrorState {
    FrontendError(FrontendError),
    PromptResult(PromptResult),
}

#[derive(Debug)]
pub enum AppMessage {
    ClientIdReceived(Box<str>),
    TokenReceived(Box<str>),
    ClientError(FrontendError),
    LoginFailed(PromptResult),
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
            AppMessage::TokenReceived(token) => {
                self.user_session = UserSessionData::from_token(token);
                self.error_state = None;
                self.user_session.is_token_valid()
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
            AppMessage::CheckSession => self.user_session.is_token_valid(),
        }
    }

    fn view(&self, ctx: &Context<Self>) -> Html {
        let user_session = &self.user_session;
        if let (Some(client_id), false) = (&self.client_id, user_session.is_token_valid()) {
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
        if let Some(error) = &self.error_state {
            let error_message = match error {
                ErrorState::FrontendError(FrontendError::JS(js_error)) => {
                    html! {
                        <AlertGroup>
                            <Alert inline=true title="Javascript Error" r#type={AlertType::Danger}>{js_error.to_string()}</Alert>
                        </AlertGroup>
                    }
                }
                ErrorState::FrontendError(FrontendError::Serde(serde_error)) => {
                    html! {
                        <AlertGroup>
                            <Alert inline=true title="Serialization Error" r#type={AlertType::Danger}>{serde_error.to_string()}</Alert>
                        </AlertGroup>
                    }
                }
                ErrorState::FrontendError(FrontendError::Graphql { type_name, errors }) => {
                    let graphql_error = errors.clone();
                    html! {
                        <AlertGroup>
                            <Alert inline=true title="Error from Server on " r#type={AlertType::Danger}>
                                <ul>
                            {
                              graphql_error.iter().map(|error| {
                                    let message=&error.message;
                                    if let Some(path) = error
                                        .path.as_ref()
                                        .map(|p|
                                            p.iter()
                                                .map(|path| path.to_string())
                                                .collect::<Vec<String>>()
                                                .join("/")
                                        )
                                    {
                                        html!{<li>{message}{" at "}{path}</li>}
                                    }else{
                                        html!{<li>{message}</li>}
                                    }
                                }).collect::<Html>()
                            }
                                </ul>
                            </Alert>
                        </AlertGroup>
                    }
                }
                ErrorState::FrontendError(FrontendError::Reqwest(reqwest_error)) => {
                    html! {
                        <AlertGroup>
                            <Alert inline=true title="Cannot call Server" r#type={AlertType::Danger}>{reqwest_error.to_string()}</Alert>
                        </AlertGroup>
                    }
                }
                ErrorState::FrontendError(FrontendError::InvalidHeader(header_error)) => {
                    html! {
                        <AlertGroup>
                            <Alert inline=true title="Header Error" r#type={AlertType::Danger}>{header_error.to_string()}</Alert>
                        </AlertGroup>
                    }
                }
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
                <p>{ format!("User Session: {:?}",self.user_session)}</p>
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
            spawn_local(async move {
                match fetch_settings().await {
                    Ok(ClientProperties { google_client_id }) => {
                        scope.send_message(AppMessage::ClientIdReceived(google_client_id));
                    }
                    Err(err) => {
                        warn!("Error from server: {err}");
                        scope.send_message(AppMessage::ClientError(err));
                    }
                }
            })
        }
    }
}
