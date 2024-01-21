use crate::pages::app::App;

mod data;
mod error;
mod model;
mod pages;
mod serde;
mod utils;

#[cfg(not(debug_assertions))]
const LOG_LEVEL: log::Level = log::Level::Info;
#[cfg(debug_assertions)]
const LOG_LEVEL: log::Level = log::Level::Trace;

pub fn main() {
    wasm_logger::init(wasm_logger::Config::new(LOG_LEVEL));
    yew::Renderer::<App>::new().render();
}
