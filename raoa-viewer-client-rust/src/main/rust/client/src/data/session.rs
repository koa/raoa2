use std::fmt::{Debug, Formatter};

use jwt::{claims::SecondsSinceEpoch, Claims, Header, Token, Unverified};
use self_cell::self_cell;

type ParsedToken<'a> = Option<Token<Header, Claims, Unverified<'a>>>;
self_cell!(
    struct ParsedAndRaw {
        owner: Box<str>,
        #[covariant]
        dependent: ParsedToken,
    }
);
impl Debug for ParsedAndRaw {
    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
        self.borrow_owner().fmt(f)
    }
}
impl Clone for ParsedAndRaw {
    fn clone(&self) -> Self {
        let new_token = self.borrow_owner().clone();
        ParsedAndRaw::new(new_token, parse_token)
    }
}
impl PartialEq for ParsedAndRaw {
    fn eq(&self, other: &Self) -> bool {
        self.borrow_owner() == other.borrow_owner()
    }
}
#[allow(clippy::borrowed_box)]
fn parse_token(t: &Box<str>) -> Option<Token<Header, Claims, Unverified>> {
    Token::<Header, Claims, Unverified<'_>>::parse_unverified(t).ok()
}

#[derive(Debug, Clone, PartialEq)]
pub struct UserSessionData(ParsedAndRaw);

impl UserSessionData {
    pub fn new(token: Box<str>) -> Self {
        UserSessionData(ParsedAndRaw::new(token, parse_token))
    }
    pub fn is_token_valid(&self) -> bool {
        if let (Some(valid_until), Ok(now)) = (
            self.valid_until(),
            wasm_timer::SystemTime::now().duration_since(wasm_timer::SystemTime::UNIX_EPOCH),
        ) {
            now.as_secs_f64() < valid_until as f64
        } else {
            false
        }
    }

    pub fn jwt(&self) -> &str {
        self.0.borrow_owner()
    }
    pub fn token(&self) -> Option<Token<Header, Claims, Unverified<'_>>> {
        Token::<Header, Claims, Unverified<'_>>::parse_unverified(self.jwt()).ok()
    }
    #[allow(dead_code)]
    pub fn valid_until(&self) -> Option<SecondsSinceEpoch> {
        self.0
            .borrow_dependent()
            .as_ref()
            .and_then(|t| t.claims().registered.expiration)
    }
}
