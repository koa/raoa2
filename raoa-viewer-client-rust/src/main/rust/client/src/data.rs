use jwt::claims::SecondsSinceEpoch;
use jwt::{Claims, Header, Token, Unverified};

#[derive(Debug, PartialEq, Clone)]
pub struct UserSessionData {
    jwt: Option<Box<str>>,
    valid_until: Option<SecondsSinceEpoch>,
    auto_select_user: bool,
}

impl Default for UserSessionData {
    fn default() -> Self {
        Self {
            jwt: None,
            valid_until: None,
            auto_select_user: true,
        }
    }
}
impl UserSessionData {
    pub fn from_token(token: Box<str>) -> Self {
        let expiration =
            if let Ok(token) = Token::<Header, Claims, Unverified<'_>>::parse_unverified(&token) {
                token.claims().registered.expiration
            } else {
                None
            };
        if let Some(exp) = expiration {
            Self {
                jwt: Some(token),
                valid_until: Some(exp),
                auto_select_user: false,
            }
        } else {
            Self {
                jwt: None,
                valid_until: None,
                auto_select_user: true,
            }
        }
    }
    pub fn is_token_valid(&self) -> bool {
        if let (Some(expire), Ok(now)) = (
            &self.valid_until,
            wasm_timer::SystemTime::now().duration_since(wasm_timer::SystemTime::UNIX_EPOCH),
        ) {
            now.as_secs_f64() < *expire as f64
        } else {
            false
        }
    }
    #[allow(dead_code)]
    pub fn logout(&mut self) {
        self.auto_select_user = false;
        self.jwt = None;
        self.valid_until = None;
        //prompt(None);
    }

    pub fn jwt(&self) -> Option<&str> {
        self.jwt.as_deref()
    }
    #[allow(dead_code)]
    pub fn valid_until(&self) -> Option<&SecondsSinceEpoch> {
        self.valid_until.as_ref()
    }
    pub fn auto_select_user(&self) -> bool {
        self.auto_select_user
    }
}
