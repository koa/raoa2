use std::any::type_name;

use ::reqwest::{header::HeaderMap, Client};
use graphql_client::{reqwest::post_graphql, GraphQLQuery};
use lazy_static::lazy_static;
use log::warn;

use crate::{error::FrontendError, utils::host};

lazy_static! {
    static ref GRAPHQL_URL: String = format!("{}/graphql", host());
}

/// Send Graphql-Query to server
pub async fn query<Q: GraphQLQuery>(
    //context: &Rc<DragonflyContext>,
    request: Q::Variables,
) -> Result<Q::ResponseData, FrontendError> {
    //let access_token = context.get_token().await;
    let mut headers = HeaderMap::new();
    //headers.insert(AUTHORIZATION, format!("Bearer {access_token}").parse()?);
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
