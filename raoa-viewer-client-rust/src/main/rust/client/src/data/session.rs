use jwt::{claims::SecondsSinceEpoch, Claims, Header, Token, Unverified};
use log::warn;
use tokio::sync::mpsc;
use tokio::sync::mpsc::Receiver;
use yew::platform::spawn_local;

#[derive(Debug, Clone, Default)]
pub struct UserSessionData {
    jwt: Option<Box<str>>,
    valid_until: Option<SecondsSinceEpoch>,
    pending_notifications: Vec<mpsc::Sender<()>>,
}

impl PartialEq for UserSessionData {
    fn eq(&self, other: &Self) -> bool {
        self.jwt == other.jwt && self.valid_until == other.valid_until
    }
}

impl UserSessionData {
    pub fn update_token(&mut self, token: Box<str>) {
        let option = Token::<Header, Claims, Unverified<'_>>::parse_unverified(&token)
            .ok()
            .and_then(|token| token.claims().registered.expiration);
        if let Some(exp) = option {
            self.jwt = Some(token);
            self.valid_until = Some(exp);
            while let Some(tx) = self.pending_notifications.pop() {
                spawn_local(async move {
                    if let Err(e) = tx.send(()).await {
                        warn!("Cannot notify: {e}");
                    }
                });
            }
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
    pub fn wait_for_token(&mut self) -> Option<Receiver<()>> {
        if self.is_token_valid() {
            return None;
        }
        let (tx, rx) = mpsc::channel(1);
        self.pending_notifications.push(tx);
        Some(rx)
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
