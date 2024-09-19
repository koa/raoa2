use lazy_static::lazy_static;
use log::error;
use reqwest::header::InvalidHeaderValue;
use thiserror::Error;
use wasm_bindgen::JsCast;
use wasm_bindgen_futures::JsFuture;
use web_sys::{Request, RequestInit, RequestMode, Response};

use crate::{
    error::{FrontendError, JavascriptError},
    model::ClientProperties,
    utils::host,
};

pub mod graphql;
lazy_static! {
    static ref CONFIG_URL: String = format!("{}/config", host());
}
pub async fn fetch_settings() -> Result<ClientProperties, FrontendError> {
    Ok(reqwest::get(CONFIG_URL.as_str()).await?.json().await?)
}
pub fn thumbnail_url(album_id: &str, entry_id: &str, max_length: Option<u16>) -> String {
    format!(
        "{}/rest/album/{album_id}/{entry_id}/thumbnail{}",
        host(),
        max_length
            .map(|l| format!("?maxLength={l}"))
            .unwrap_or_default()
    )
}
#[derive(Error, Debug)]
pub enum Error {
    #[error("Generic Javascript error: {0}")]
    JS(#[from] JavascriptError),
    #[error("cannot create header")]
    InvalidHeaderValue(#[from] InvalidHeaderValue),
    #[error("cannot fetch data")]
    Reqwest(#[from] reqwest::Error),
    #[error("error {0} from server")]
    ResponseError(u16),
}

pub async fn fetch_blob(access_token: &str, url: &str) -> Result<Response, Error> {
    let mut opts = RequestInit::new();
    opts.method("GET");
    opts.mode(RequestMode::Cors);

    let request = Request::new_with_str_and_init(&url, &opts).map_err(|e| Error::JS(e.into()))?;

    request
        .headers()
        .set("Authorization", &format!("Bearer {access_token}"))
        .map_err(|e| Error::JS(e.into()))?;

    let window = web_sys::window().unwrap();
    let resp_value = JsFuture::from(window.fetch_with_request(&request))
        .await
        .map_err(|e| Error::JS(e.into()))?;

    // `resp_value` is a `Response` object.
    assert!(resp_value.is_instance_of::<Response>());
    let resp: Response = resp_value.dyn_into().unwrap();
    if resp.ok() {
        Ok(resp)
    } else {
        Err(Error::ResponseError(resp.status()))
    }
}
