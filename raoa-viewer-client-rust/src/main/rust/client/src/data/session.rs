use jwt::{claims::SecondsSinceEpoch, Claims, Header, Token, Unverified};

#[derive(Debug, PartialEq, Clone, Default)]
pub struct UserSessionData {
    jwt: Option<Box<str>>,
    valid_until: Option<SecondsSinceEpoch>,
}

impl UserSessionData {
    pub fn update_token(&mut self, token: Box<str>) {
        let option = Token::<Header, Claims, Unverified<'_>>::parse_unverified(&token)
            .ok()
            .and_then(|token| token.claims().registered.expiration);
        if let Some(exp) = option {
            self.jwt = Some(token);
            self.valid_until = Some(exp);
        } else {
            self.jwt = None;
            self.valid_until = None;
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
        self.jwt = None;
        self.valid_until = None;
    }

    pub fn jwt(&self) -> Option<&str> {
        self.jwt.as_deref()
    }
    pub fn token(&self) -> Option<Token<Header, Claims, Unverified<'_>>> {
        self.jwt()
            .and_then(|token| Token::<Header, Claims, Unverified<'_>>::parse_unverified(token).ok())
    }
    #[allow(dead_code)]
    pub fn valid_until(&self) -> Option<&SecondsSinceEpoch> {
        self.valid_until.as_ref()
    }
}
