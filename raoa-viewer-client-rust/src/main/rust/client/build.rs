use std::{
    env,
    fs::{copy, read_dir, File},
    io::Write,
    path::Path,
};

use anyhow::Result;

fn main() -> Result<()> {
    copy_graphql_schema()?;
    generate_graphql_package()?;
    Ok(())
}

fn copy_graphql_schema() -> Result<()> {
    copy(
        "../../../../../raoa-viewer/src/main/resources/graphql/schema.graphqls",
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
#[derive(GraphQLQuery)]
#[graphql(
    schema_path = \"./graphql/schema.graphql\",
    query_path = \"./graphql/{filename}.graphql\",
    response_derives = \"Debug, PartialEq, Clone\",
    extern_enums()
)]
pub struct {filename};"
        )?;
    }
    Ok(())
}
