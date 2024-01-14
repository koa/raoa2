use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Debug, Clone, Hash, Eq, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ClientProperties {
    pub google_client_id: Box<str>,
}
