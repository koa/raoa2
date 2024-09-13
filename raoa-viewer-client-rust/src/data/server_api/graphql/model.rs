use chrono::Utc;

use crate::serde::date_time_formatter;

include!(concat!(env!("OUT_DIR"), "/graphql.rs"));
#[derive(Debug, PartialEq, Clone, serde::Deserialize, serde::Serialize, Ord, PartialOrd, Eq)]
#[serde(transparent)]
pub struct DateTime {
    #[serde(with = "date_time_formatter", flatten)]
    time: chrono::DateTime<Utc>,
}

impl From<chrono::DateTime<Utc>> for DateTime {
    fn from(value: chrono::DateTime<Utc>) -> Self {
        Self { time: value }
    }
}

impl AsRef<chrono::DateTime<Utc>> for DateTime {
    fn as_ref(&self) -> &chrono::DateTime<Utc> {
        &self.time
    }
}
