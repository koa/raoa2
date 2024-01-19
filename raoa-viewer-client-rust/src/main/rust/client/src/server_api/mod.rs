use lazy_static::lazy_static;

use crate::error::FrontendError;
use crate::model::ClientProperties;
use crate::utils::host;

pub mod graphql;
lazy_static! {
    static ref CONFIG_URL: String = format!("{}/config", host());
}
pub async fn fetch_settings() -> Result<ClientProperties, FrontendError> {
    Ok(reqwest::get(CONFIG_URL.as_str()).await?.json().await?)
}
