use std::fmt::Debug;

use crate::utils::swiper::{
    swiper::{swiper_of_element, JsSwiper},
    swiper_options::JsSwiperOptions,
};
use paste::paste;
use thiserror::Error;
use wasm_bindgen::{prelude::wasm_bindgen, JsValue};
use web_sys::HtmlElement;

#[derive(Debug)]
pub struct Swiper {
    delegate: JsSwiper,
}

pub struct SwiperOptions {
    delegate: JsSwiperOptions,
}

macro_rules! delegate_field {
    ($name: ident, $ret:ty) => {
        pub fn $name(&self) -> $ret {
            self.delegate.$name()
        }
        paste! {
            pub fn [<set_ $name>](&self, value: $ret) {
                self.delegate.[<set_ $name>](value)
            }
        }
    };
}
macro_rules! delegate_method {
    ($name: ident) => {
        pub fn $name(&self) {
            self.delegate.$name().unwrap()
        }
    };
    ($name: ident, $ret:ty) => {
        pub fn $name(&self) -> $ret {
            self.delegate.$name().unwrap()
        }
    };
    ($name: ident, $ret:ty, $p1:ty) => {
        pub fn $name(&self, p1: $p1) -> $ret {
            self.delegate.$name(p1).unwrap()
        }
    };
}

impl Swiper {
    pub fn new(element: HtmlElement) -> Result<Self, Error> {
        let delegate = swiper_of_element(element)?;
        Ok(Self { delegate })
    }
    delegate_field!(active_index, u32);
    delegate_field!(allow_slide_next, bool);
    delegate_field!(allow_slide_prev, bool);
    delegate_field!(allow_touch_move, bool);
    delegate_field!(animating, bool);
    delegate_field!(clicked_index, u32);
    delegate_field!(clicked_slide, HtmlElement);
    delegate_field!(swipe_direction, SwipeDirection);

    delegate_method!(attach_events);
    delegate_method!(disable);
    delegate_method!(enable);
    delegate_method!(slide_next);
    delegate_method!(slide_prev);
    delegate_method!(slide_to, (), u32);
    delegate_method!(update);

    pub fn change_direction(&self, direction: Direction, need_update: bool) {
        self.delegate
            .change_direction(direction, need_update)
            .unwrap();
    }
    pub fn enable_zoom(&self) {
        self.delegate.zoom().enable().unwrap()
    }
    pub fn zoom_out(&self) {
        self.delegate.zoom().out().unwrap()
    }
}
/*impl Drop for Swiper {
    fn drop(&mut self) {
        self.swiper.destroy(true, true);
    }
}*/
#[derive(Error, Debug)]
pub enum Error {
    #[error("Error fom javascript: {0:?}")]
    Javascript(JsValue),
}

impl From<JsValue> for Error {
    fn from(value: JsValue) -> Self {
        Error::Javascript(value)
    }
}

#[wasm_bindgen]
#[derive(Copy, Clone, Debug)]
pub enum Direction {
    Horizontal = "horizontal",
    Vertical = "vertical",
}
#[wasm_bindgen]
#[derive(Copy, Clone, Debug)]
pub enum SwipeDirection {
    Prev = "prev",
    Next = "next",
}

include!(concat!(env!("OUT_DIR"), "/swiper_bindings.rs"));
