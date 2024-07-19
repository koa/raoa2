use ::reqwest::{header::HeaderMap, Client};
use graphql_client::{reqwest::post_graphql, GraphQLQuery};
use lazy_static::lazy_static;
use log::warn;
use reqwest::header::{InvalidHeaderValue, AUTHORIZATION};
use std::any::type_name;
use std::fmt::{Debug, Display, Formatter};
use std::rc::Rc;
use thiserror::Error;
use yew::Component;

use crate::utils::host;

pub mod model;

lazy_static! {
    static ref GRAPHQL_URL: String = format!("{}/graphql", host());
}
#[derive(Error, Clone)]
pub struct GraphqlAccessError<Q: GraphQLQuery> {
    request: Q::Variables,
    detail: GraphqlAccessErrorDetail,
}

impl<Q: GraphQLQuery> GraphqlAccessError<Q> {
    fn type_name() -> &'static str {
        type_name::<Q>()
    }
}

impl<Q> Debug for GraphqlAccessError<Q>
where
    Q: GraphQLQuery,
    Q::Variables: Debug,
{
    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "GraphqlAccessError(request: {}, variables: {:?}, detail: {:?})",
            type_name::<Q>(),
            self.request,
            self.detail
        )?;
        Ok(())
    }
}
impl<Q: GraphQLQuery> Display for GraphqlAccessError<Q> {
    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "Error Querying graphql {}: detail: {:})",
            type_name::<Q>(),
            self.detail
        )?;
        Ok(())
    }
}

#[derive(Debug, Error, Clone)]
pub enum GraphqlAccessErrorDetail {
    InvalidHeaderValue(Rc<InvalidHeaderValue>),
    Reqwest(Rc<reqwest::Error>),
    Response(Box<[graphql_client::Error]>),
}
impl From<InvalidHeaderValue> for GraphqlAccessErrorDetail {
    fn from(value: InvalidHeaderValue) -> Self {
        GraphqlAccessErrorDetail::InvalidHeaderValue(Rc::new(value))
    }
}
impl From<reqwest::Error> for GraphqlAccessErrorDetail {
    fn from(value: reqwest::Error) -> Self {
        GraphqlAccessErrorDetail::Reqwest(Rc::new(value))
    }
}

impl Display for GraphqlAccessErrorDetail {
    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
        match self {
            GraphqlAccessErrorDetail::InvalidHeaderValue(error) => {
                write!(f, "Invalid http header: ")?;
                std::fmt::Display::fmt(&error, f)
            }
            GraphqlAccessErrorDetail::Reqwest(error) => {
                write!(f, "Error on http layer: ")?;
                std::fmt::Display::fmt(&error, f)
            }
            GraphqlAccessErrorDetail::Response(errors) => {
                write!(f, "Error from server:")?;
                for error in errors.iter() {
                    write!(f, " ")?;
                    std::fmt::Display::fmt(&error, f)?;
                }
                Ok(())
            }
        }
    }
}

/// Send Graphql-Query to server
pub async fn query<Q>(
    access_token: &str,
    request: Q::Variables,
) -> Result<Q::ResponseData, GraphqlAccessError<Q>>
where
    Q: GraphQLQuery,
    Q::Variables: Clone,
{
    /*
    let (session_data, _) = scope
        .context::<UserSessionData>(Default::default())
        .expect("Missing Session in Context");
    let access_token = session_data.jwt().ok_or(FrontendError::NotLoggedIn)?;*/
    let mut headers = HeaderMap::new();
    headers.insert(
        AUTHORIZATION,
        format!("Bearer {access_token}")
            .parse()
            .map_err(|e: InvalidHeaderValue| GraphqlAccessError {
                request: request.clone(),
                detail: e.into(),
            })?,
    );
    let client = Client::builder()
        .default_headers(headers)
        .build()
        .map_err(|error| GraphqlAccessError {
            request: request.clone(),
            detail: error.into(),
        })?;
    let response = post_graphql::<Q, _>(&client, GRAPHQL_URL.as_str(), request.clone())
        .await
        .map_err(|error| GraphqlAccessError {
            request: request.clone(),
            detail: error.into(),
        })?;
    if let Some(data) = response.data {
        if let Some(errors) = response.errors {
            let query_type_name = type_name::<Q>();
            for error in errors {
                warn!("Graphql-Error on {query_type_name}: {error}");
            }
        }
        Ok(data)
    } else {
        Err(GraphqlAccessError {
            request,
            detail: GraphqlAccessErrorDetail::Response(
                response
                    .errors
                    .map(Vec::into_boxed_slice)
                    .unwrap_or_default(),
            ),
        })
    }
}

#[cfg(test)]
mod test {
    use chrono::{TimeZone, Utc};
    use graphql_client::Response;

    use crate::data::server_api::graphql::model::{get_album_details, DateTime};

    #[test]
    fn test_deserialize() {
        let value = r#"{
    "data": {
        "albumById": {
            "id": "ca0d0f61-dde7-5949-d941-691d352f4537",
            "name": "Donaueschingen 2013",
            "entryCount": 387,
            "albumTime": "2013-09-14T11:47:04.000Z",
            "version": "80e87ad232bde5989f6a813915e1368499c7d4c4",
            "labels": []
        }
    }
}"#;
        let result: Response<get_album_details::ResponseData> =
            serde_json::from_str(value).unwrap();
        //println!("Result: {result:?}");
        let album_by_id = result.data.unwrap().album_by_id.unwrap();
        assert_eq!(album_by_id.name.as_deref(), Some("Donaueschingen 2013"));
        assert_eq!(
            album_by_id.album_time,
            Some(DateTime::from(
                Utc.with_ymd_and_hms(2013, 9, 14, 11, 47, 4).unwrap()
            ))
        );
    }
}
