use std::fs::File;
use std::{
    env, fs,
    fs::{copy, read_dir},
    io::Write,
    path::Path,
};

use anyhow::Result;
use convert_case::{Case, Casing};
use prettyplease::unparse;
use proc_macro2::{Ident, Span};
use syn::token::Brace;
use syn::{parse_quote, ForeignItem, Item, ItemForeignMod, ItemMod, Type, Visibility};

fn main() -> Result<()> {
    copy_graphql_schema()?;
    generate_graphql_package()?;
    build_swiper_wasm();
    Ok(())
}

fn copy_graphql_schema() -> Result<()> {
    copy(
        "../raoa-viewer/src/main/resources/graphql/schema.graphqls",
        "graphql/schema.graphql",
    )?;
    Ok(())
}

fn generate_graphql_package() -> Result<()> {
    let out_dir = env::var_os("OUT_DIR").unwrap();
    let dest_path = Path::new(&out_dir).join("graphql.rs");
    let mut target_file = File::create(dest_path)?;
    writeln!(&mut target_file, "use graphql_client::GraphQLQuery;")?;
    for dir_entry in read_dir("./graphql")? {
        let dir_entry = dir_entry?;
        let string = dir_entry.file_name();
        let filename = string.to_str().expect("Error on filename encoding");
        if filename == "schema.graphql" {
            continue;
        }
        if !filename.ends_with(".graphql") {
            continue;
        }
        let filename = &filename[0..filename.len() - 8];

        writeln!(
            &mut target_file,
            "
#[derive(GraphQLQuery,Clone)]
#[graphql(
    schema_path = \"./graphql/schema.graphql\",
    query_path = \"./graphql/{filename}.graphql\",
    response_derives = \"Debug, PartialEq, Clone\",
    variables_derives = \"Debug, PartialEq, Clone\",
    extern_enums()
)]
pub struct {filename};"
        )?;
    }
    Ok(())
}

fn build_swiper_wasm() {
    let mut main: ItemForeignMod = parse_quote!(
        #[wasm_bindgen(module = "/target/classes/resources/debug/swiper.js")]
        extern "C" {
            #[wasm_bindgen(js_name = "Swiper")]
            pub type JsSwiper;
            #[wasm_bindgen]
            pub type JsSwiperZoom;
            #[wasm_bindgen(catch, js_class = "Swiper")]
            pub fn swiper_of_element(element: HtmlElement) -> Result<JsSwiper, JsValue>;
            #[wasm_bindgen(catch, constructor, js_class = "Swiper")]
            pub fn init(element: HtmlElement) -> Result<JsSwiper, JsValue>;
            #[wasm_bindgen(catch,method, js_name = attachEvents)]
            pub fn attach_events(this: &JsSwiper) -> Result<(), JsValue>;
            #[wasm_bindgen(catch,method, js_name = changeDirection)]
            pub fn change_direction(
                this: &JsSwiper,
                direction: Direction,
                need_update: bool,
            ) -> Result<(), JsValue>;
            #[wasm_bindgen(catch, method)]
            pub fn destroy(
                this: &JsSwiper,
                delete_instance: bool,
                clean_styles: bool,
            ) -> Result<(), JsValue>;
            #[wasm_bindgen(catch, method)]
            pub fn disable(this: &JsSwiper) -> Result<(), JsValue>;
            #[wasm_bindgen(catch, method)]
            pub fn enable(this: &JsSwiper) -> Result<(), JsValue>;
            #[wasm_bindgen(catch, method, js_name = slideNext)]
            pub fn slide_next(this: &JsSwiper) -> Result<(), JsValue>;
            #[wasm_bindgen(catch,method, js_name = slidePrev)]
            pub fn slide_prev(this: &JsSwiper) -> Result<(), JsValue>;
            #[wasm_bindgen(catch,method, js_name = slideTo)]
            pub fn slide_to(this: &JsSwiper, index: u32) -> Result<(), JsValue>;
            #[wasm_bindgen(catch, method)]
            pub fn update(this: &JsSwiper) -> Result<(), JsValue>;
            #[wasm_bindgen(catch, method)]
            pub fn enable(this: &JsSwiperZoom) -> Result<(), JsValue>;
            #[wasm_bindgen(catch, method)]
            pub fn disable(this: &JsSwiperZoom) -> Result<(), JsValue>;
            #[wasm_bindgen(catch, method)]
            pub fn out(this: &JsSwiperZoom) -> Result<(), JsValue>;
        }
    );

    for (name, ty) in [
        ("params", parse_quote!(JsSwiperOptions)),
        ("active_index", parse_quote!(u32)),
        ("allow_slide_next", parse_quote!(bool)),
        ("allow_slide_prev", parse_quote!(bool)),
        ("allow_touch_move", parse_quote!(bool)),
        ("animating", parse_quote!(bool)),
        ("clicked_index", parse_quote!(u32)),
        ("clicked_slide", parse_quote!(HtmlElement)),
        ("swipe_direction", parse_quote!(SwipeDirection)),
        ("zoom", parse_quote!(JsSwiperZoom)),
    ] {
        append_field(&mut main.items, parse_quote!(JsSwiper), name, ty);
    }
    for (name, ty) in [
        ("enabled", parse_quote!(bool)),
        ("scale", parse_quote!(f32)),
    ] {
        append_field(&mut main.items, parse_quote!(JsSwiperZoom), name, ty);
    }

    let mut swiper_options: ItemForeignMod = parse_quote!(
        #[wasm_bindgen(module = "/target/classes/resources/debug/swiper.js")]
        extern "C" {
            #[wasm_bindgen(js_name = "SwiperOptions")]
            pub type JsSwiperOptions;
        }
    );
    for (name, ty) in [
        ("init", parse_quote!(Option<bool>)),
        ("enabled", parse_quote!(Option<bool>)),
    ] {
        append_field(
            &mut swiper_options.items,
            parse_quote!(JsSwiperOptions),
            &name,
            ty,
        );
    }
    let out_dir = env::var_os("OUT_DIR").unwrap();
    let dest_path = Path::new(&out_dir).join("swiper_bindings.rs");
    let swiper_options_mod = create_module(
        vec![
            parse_quote!(
                use crate::utils::swiper::swiper::JsSwiper;
            ),
            Item::ForeignMod(swiper_options),
        ],
        parse_quote!(swiper_options),
    );
    let swiper_mod = create_module(
        vec![
            parse_quote!(
                use web_sys::HtmlElement;
            ),
            parse_quote!(
                use wasm_bindgen::JsValue;
            ),
            parse_quote!(
                use crate::utils::swiper::Direction;
            ),
            parse_quote!(
                use crate::utils::swiper::SwipeDirection;
            ),
            parse_quote!(
                use crate::utils::swiper::JsSwiperOptions;
            ),
            parse_quote!(
                use std::fmt::Debug;
            ),
            parse_quote!(
                use core::fmt::Formatter;
            ),
            Item::ForeignMod(main),
            parse_quote!(
                impl Debug for JsSwiper {
                    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
                        self.obj.fmt(f)
                    }
                }
            ),
        ],
        parse_quote!(swiper),
    );
    let file = syn::File {
        shebang: None,
        attrs: vec![],
        items: vec![swiper_mod, swiper_options_mod],
    };
    fs::write(dest_path, unparse(&file)).expect("Cannot write source file");
}

fn create_module(mut items: Vec<Item>, name: Ident) -> Item {
    items.insert(
        0,
        parse_quote!(
            use wasm_bindgen::prelude::wasm_bindgen;
        ),
    );
    let swiper_options_mod = Item::Mod(ItemMod {
        attrs: Default::default(),
        vis: Visibility::Inherited,
        unsafety: None,
        mod_token: Default::default(),
        ident: name,
        content: Some((Brace::default(), items)),
        semi: None,
    });
    swiper_options_mod
}

fn append_field(items: &mut Vec<ForeignItem>, parent_type: Type, name: &str, ty: Type) {
    let js_name = Ident::new(&name.to_case(Case::Camel), Span::call_site());
    let setter = Ident::new(&format!("set_{name}"), Span::call_site());
    let name = Ident::new(&name, Span::call_site());
    items.push(parse_quote!(
        #[wasm_bindgen(method, getter,js_name=#js_name)]
        pub fn #name(this: &#parent_type) -> #ty;
    ));
    items.push(parse_quote!(
        #[wasm_bindgen(method, setter,js_name=#js_name)]
        pub fn #setter(this: &#parent_type, val: #ty);
    ));
}
