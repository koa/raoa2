use std::any::type_name;

use ::reqwest::{header::HeaderMap, Client};
use graphql_client::{reqwest::post_graphql, GraphQLQuery};
use lazy_static::lazy_static;
use log::warn;
use reqwest::header::AUTHORIZATION;
use yew::html::Scope;
use yew::Component;

use crate::data::UserSessionData;
use crate::{error::FrontendError, utils::host};

pub mod model;

lazy_static! {
    static ref GRAPHQL_URL: String = format!("{}/graphql", host());
}

/// Send Graphql-Query to server
pub async fn query<Q: GraphQLQuery, C: Component>(
    scope: &Scope<C>,
    request: Q::Variables,
) -> Result<Q::ResponseData, FrontendError> {
    //let access_token = context.get_token().await;
    let mut headers = HeaderMap::new();
    let (session_data, _) = scope
        .context::<UserSessionData>(Default::default())
        .expect("Missing Session in Context");
    let access_token = session_data.jwt().ok_or(FrontendError::NotLoggedIn)?;
    headers.insert(AUTHORIZATION, format!("Bearer {access_token}").parse()?);
    let client = Client::builder().default_headers(headers).build()?;
    let response = post_graphql::<Q, _>(&client, GRAPHQL_URL.as_str(), request).await?;
    if let Some(data) = response.data {
        if let Some(errors) = response.errors {
            let query_type_name = type_name::<Q>();
            for error in errors {
                warn!("Graphql-Error on {query_type_name}: {error}");
            }
        }
        Ok(data)
    } else {
        Err(FrontendError::Graphql {
            type_name: type_name::<Q>(),
            errors: response.errors.unwrap_or_default(),
        })
    }
}

#[cfg(test)]
mod test {
    use graphql_client::Response;

    use crate::server_api::graphql::model::get_album_details;

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
        println!("Result: {result:?}");
    }
}
