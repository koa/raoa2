use std::error::Error;
use std::fmt::{Debug, Display, Formatter};

use crate::data::server_api;
use crate::data::storage::StorageError;
use log::error;
use patternfly_yew::prelude::{Alert, AlertGroup, AlertType};
use reqwest::header::InvalidHeaderValue;
use thiserror::Error;
use wasm_bindgen::JsValue;
use web_sys::DomException;
use yew::{html, Html};

pub struct JavascriptError {
    original_value: JsValue,
}

impl JavascriptError {
    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
        if let Some(string) = self.original_value.as_string() {
            f.write_str(&string)?;
        }
        Ok(())
    }
}

impl From<JsValue> for JavascriptError {
    fn from(value: JsValue) -> Self {
        JavascriptError {
            original_value: value,
        }
    }
}

impl Debug for JavascriptError {
    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
        Self::fmt(self, f)
    }
}

impl Display for JavascriptError {
    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
        Self::fmt(self, f)
    }
}

impl Error for JavascriptError {}

#[derive(Error, Debug)]
pub enum FrontendError {
    #[error("Generic Javascript error: {0}")]
    JS(#[from] JavascriptError),
    #[error("Cannot convert json: {0}")]
    Serde(#[from] serde_json::Error),
    #[error("Graphql Execution Error: {type_name} {errors:?}")]
    Graphql {
        type_name: &'static str,
        errors: Vec<graphql_client::Error>,
    },
    #[error("Error on http request: {0}")]
    Reqwest(#[from] reqwest::Error),
    #[error("Invalid http header: {0}")]
    InvalidHeader(#[from] InvalidHeaderValue),
    //#[error("Cannot parse integer")]
    //ParseInteError(#[from] ParseIntError),
    #[error("No session found")]
    NotLoggedIn,
    #[error("Error from dom: {0}")]
    DomError(DomError),
    #[error("Error serializing to JSValue: {0}")]
    SerdeWasmBindgen(#[from] serde_wasm_bindgen::Error),
    #[error("Error accessing Storage: {0}")]
    StorageAccess(#[from] StorageError),
    #[error("Error accessing server: {0}")]
    Server(#[from] server_api::Error),
}

impl FrontendError {
    pub fn render_error_message(&self) -> Html {
        match self {
            FrontendError::JS(js_error) => {
                html! {
                    <AlertGroup>
                        <Alert inline=true title="Javascript Error" r#type={AlertType::Danger}>{js_error.to_string()}</Alert>
                    </AlertGroup>
                }
            }
            FrontendError::Serde(serde_error) => {
                html! {
                    <AlertGroup>
                        <Alert inline=true title="Serialization Error" r#type={AlertType::Danger}>{serde_error.to_string()}</Alert>
                    </AlertGroup>
                }
            }
            FrontendError::Graphql { type_name, errors } => {
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
            FrontendError::Reqwest(reqwest_error) => {
                html! {
                    <AlertGroup>
                        <Alert inline=true title="Cannot call Server" r#type={AlertType::Danger}>{reqwest_error.to_string()}</Alert>
                    </AlertGroup>
                }
            }
            FrontendError::InvalidHeader(header_error) => {
                html! {
                    <AlertGroup>
                        <Alert inline=true title="Header Error" r#type={AlertType::Danger}>{header_error.to_string()}</Alert>
                    </AlertGroup>
                }
            }
            FrontendError::DomError(dom_error) => html! {
                <AlertGroup>
                    <Alert inline=true title="Error from indexdb" r#type={AlertType::Danger}>{dom_error.to_string()}</Alert>
                </AlertGroup>
            },
            FrontendError::SerdeWasmBindgen(error) => html! {
                <AlertGroup>
                    <Alert inline=true title="Error deserializing from indexdb" r#type={AlertType::Danger}>{error.to_string()}</Alert>
                </AlertGroup>
            },
            FrontendError::StorageAccess(error) => html! {
                <AlertGroup>
                    <Alert inline=true title="Error from storage access" r#type={AlertType::Danger}>{error.to_string()}</Alert>
                </AlertGroup>
            },
            FrontendError::Server(error) => html! {
                <AlertGroup>
                    <Alert inline=true title="Error from storage access" r#type={AlertType::Danger}>{error.to_string()}</Alert>
                </AlertGroup>
            },
            FrontendError::NotLoggedIn => {
                html! {
                        <AlertGroup>
                            <Alert inline=true title="Session timeout" r#type={AlertType::Danger}>{"Login abgelaufen, bitte neu einloggen"}</Alert>
                        </AlertGroup>
                }
            }
        }
    }
}

#[derive(Error, Debug)]
pub struct DomError {
    exception: DomException,
}

impl From<DomException> for DomError {
    fn from(exception: DomException) -> Self {
        Self { exception }
    }
}

impl Display for DomError {
    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
        self.exception.fmt(f)
    }
}

impl From<DomException> for FrontendError {
    fn from(exception: DomException) -> Self {
        FrontendError::DomError(DomError { exception })
    }
}
