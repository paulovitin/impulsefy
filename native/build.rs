fn main() {
    println!("cargo:rerun-if-changed=proto/collection2v2.proto");
    protobuf_codegen::Codegen::new()
        .pure()
        .cargo_out_dir("collection_protocol")
        .include("proto")
        .input("proto/collection2v2.proto")
        .run_from_script();
}
