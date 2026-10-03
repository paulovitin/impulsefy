//! Existing library/search screens read through the authenticated playback session.
use futures_util::{stream, StreamExt};
use http::Method;
use librespot_core::{spotify_id::SpotifyId, Session, SpotifyUri};
use librespot_metadata::{Metadata, Track};
use librespot_protocol::{context_page::ContextPage, playlist4_external::SelectedListContent};
use protobuf::Message;
use serde_json::{json, Value};
use std::collections::VecDeque;
use url::Url;

type Result<T> = std::result::Result<T, String>;
fn failure(_: impl std::fmt::Debug) -> String {
    "Não foi possível carregar sua música. Tente novamente.".into()
}

fn page(items: Vec<Value>, path: &Url, offset: usize, more: bool) -> Value {
    let next = if more {
        let mut url = path.clone();
        let pairs: Vec<_> = url
            .query_pairs()
            .filter(|(k, _)| k != "offset")
            .map(|(k, v)| (k.into_owned(), v.into_owned()))
            .collect();
        url.query_pairs_mut()
            .clear()
            .extend_pairs(pairs)
            .append_pair("offset", &offset.to_string());
        Some(url.to_string())
    } else {
        None
    };
    json!({"items":items,"next":next})
}

pub async fn read(session: &Session, path: &str) -> Result<Value> {
    if !path.starts_with('/') || path.starts_with("//") || path.len() > 2048 {
        return Err("Caminho inválido.".into());
    }
    let url = Url::parse(&format!("https://api.spotify.com/v1{path}")).map_err(failure)?;
    if url.fragment().is_some() {
        return Err("Caminho inválido.".into());
    }
    let offset = url
        .query_pairs()
        .find(|(k, _)| k == "offset")
        .and_then(|(_, v)| v.parse::<usize>().ok())
        .unwrap_or(0);
    let limit = url
        .query_pairs()
        .find(|(k, _)| k == "limit")
        .and_then(|(_, v)| v.parse::<usize>().ok())
        .unwrap_or(30)
        .clamp(1, 50);
    if offset > 10_000 {
        return Err("Esta lista é grande demais para carregar agora.".into());
    }
    match url.path() {
        "/v1/me" => {
            let bytes = session
                .spclient()
                .get_user_profile(&session.username(), Some(0), Some(0))
                .await
                .map_err(failure)?;
            let profile: Value = serde_json::from_slice(&bytes).map_err(failure)?;
            Ok(
                json!({"id":session.username(),"display_name":profile["name"].as_str().unwrap_or("Sua conta"),"product":session.get_user_attribute("type").unwrap_or_default()}),
            )
        }
        "/v1/me/playlists" => {
            let bytes = session
                .spclient()
                .get_rootlist(offset, Some(limit))
                .await
                .map_err(failure)?;
            let list = SelectedListContent::parse_from_bytes(&bytes).map_err(failure)?;
            let contents = list.contents.get_or_default();
            let mut items = Vec::new();
            for (index, item) in contents.items.iter().enumerate() {
                let Ok(SpotifyUri::Playlist { id, .. }) = SpotifyUri::from_uri(item.uri()) else {
                    continue;
                };
                let Some(meta) = contents.meta_items.get(index) else {
                    continue;
                };
                let attrs = meta.attributes.get_or_default();
                let image = attrs
                    .picture_size
                    .iter()
                    .find(|i| !i.url().is_empty())
                    .map(|i| i.url().to_owned())
                    .unwrap_or_default();
                items.push(json!({"id":id.to_base62().map_err(failure)?,"uri":item.uri(),"name":attrs.name(),"owner":{"display_name":meta.owner_username()},"images":[{"url":image}]}));
            }
            Ok(page(
                items,
                &url,
                offset + contents.items.len(),
                contents.truncated(),
            ))
        }
        "/v1/me/tracks" | "/v1/search" => {
            let search = url.path() == "/v1/search";
            let uri = if search {
                let query = url
                    .query_pairs()
                    .find(|(k, _)| k == "q")
                    .map(|(_, v)| v.into_owned())
                    .unwrap_or_default();
                if query.trim().is_empty() || query.len() > 512 {
                    return Err("Digite uma busca válida.".into());
                }
                // Form encoding also prevents search text from changing the context path/query.
                let encoded: String =
                    url::form_urlencoded::byte_serialize(query.as_bytes()).collect();
                format!("spotify:search:{encoded}")
            } else {
                format!("spotify:user:{}:collection", session.username())
            };
            let context = session
                .spclient()
                .get_context(&uri)
                .await
                .map_err(failure)?;
            let (uris, more) = context_window(session, context.pages, offset, limit).await?;
            let items = tracks(session, uris).await?;
            let result = page(items, &url, offset + limit, more);
            Ok(if search {
                json!({"tracks":result})
            } else {
                result
            })
        }
        path if path.starts_with("/v1/playlists/") => {
            let parts: Vec<_> = path.split('/').collect();
            if parts.len() != 5 || !matches!(parts[4], "items" | "tracks") {
                return Err("Playlist inválida.".into());
            }
            let id = SpotifyId::from_base62(parts[3])
                .map_err(failure)?
                .to_base62()
                .map_err(failure)?;
            let endpoint = format!("/playlist/v2/playlist/{id}?from={offset}&length={limit}");
            let bytes = session
                .spclient()
                .request(&Method::GET, &endpoint, None, None)
                .await
                .map_err(failure)?;
            let list = SelectedListContent::parse_from_bytes(&bytes).map_err(failure)?;
            let contents = list.contents.get_or_default();
            let uris = contents
                .items
                .iter()
                .filter_map(|i| SpotifyUri::from_uri(i.uri()).ok())
                .collect();
            let items = tracks(session, uris).await?;
            let next = offset + contents.items.len();
            Ok(page(
                items,
                &url,
                next,
                contents.truncated() || next < (list.length().max(0) as usize),
            ))
        }
        _ => Err("Este conteúdo não está disponível.".into()),
    }
}

async fn context_window(
    session: &Session,
    pages: Vec<ContextPage>,
    offset: usize,
    limit: usize,
) -> Result<(Vec<SpotifyUri>, bool)> {
    let mut pages: VecDeque<_> = pages.into();
    let mut seen = 0;
    let mut result = Vec::new();
    // Bounded even if the service accidentally returns a cyclic cursor.
    for _ in 0..200 {
        let Some(mut page) = pages.pop_front() else {
            return Ok((result, false));
        };
        if page.tracks.is_empty() && !page.page_url().is_empty() {
            page = fetch_page(session, page.page_url()).await?;
        }
        for track in &page.tracks {
            let uri = SpotifyUri::from_uri(track.uri()).ok().or_else(|| {
                SpotifyId::from_raw(track.gid())
                    .ok()
                    .map(|id| SpotifyUri::Track { id })
            });
            if let Some(uri @ SpotifyUri::Track { .. }) = uri {
                if seen >= offset {
                    if result.len() == limit {
                        return Ok((result, true));
                    }
                    result.push(uri);
                }
                seen += 1;
            }
        }
        if !page.next_page_url().is_empty() {
            pages.push_front(ContextPage {
                page_url: Some(page.next_page_url().to_owned()),
                ..Default::default()
            });
        }
    }
    Err("Não foi possível concluir a paginação.".into())
}
async fn fetch_page(session: &Session, path: &str) -> Result<ContextPage> {
    let bytes = session
        .spclient()
        .get_next_page(path)
        .await
        .map_err(failure)?;
    protobuf_json_mapping::parse_from_str(std::str::from_utf8(&bytes).map_err(failure)?)
        .map_err(failure)
}
async fn tracks(session: &Session, uris: Vec<SpotifyUri>) -> Result<Vec<Value>> {
    let results:Vec<_>=stream::iter(uris).map(|uri|async move {
        let track=Track::get(session,&uri).await.map_err(failure)?;
        let images:Vec<_>=track.album.covers.iter().filter_map(|i|i.id.to_base16().ok().map(|id|json!({"url":format!("https://i.scdn.co/image/{id}"),"width":i.width}))).collect();
        let artists:Vec<_>=track.artists.iter().map(|a|json!({"name":a.name})).collect();
        Ok::<_,String>(json!({"uri":uri.to_uri().map_err(failure)?,"name":track.name,"duration_ms":track.duration,"artists":artists,"album":{"images":images},"type":"track"}))
    }).buffered(8).collect().await;
    // A metadata outage must not silently turn a nonempty library into an empty one.
    if !results.is_empty() && results.iter().all(|r| r.is_err()) {
        return Err(failure("metadata"));
    }
    Ok(results.into_iter().filter_map(|r| r.ok()).collect())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test]
    async fn catalogue_rejects_external_paths_and_unbounded_paging_before_network() {
        let session = Session::new(Default::default(), None);
        for path in [
            "https://example.com",
            "//example.com",
            "/me#token",
            "/me/tracks?offset=10001",
            "/search?q=",
            "/playlists/id/items/extra",
        ] {
            assert!(read(&session, path).await.is_err(), "accepted {path}");
        }
        session.shutdown();
    }
    #[test]
    fn pagination_preserves_search_text_and_replaces_previous_offset() {
        let url = Url::parse(
            "https://api.spotify.com/v1/search?q=AC%2FDC+%26+Beyonc%C3%A9&limit=10&offset=10",
        )
        .unwrap();
        let value = page(vec![], &url, 20, true);
        let next = Url::parse(value["next"].as_str().unwrap()).unwrap();
        let pairs: Vec<_> = next.query_pairs().collect();
        assert_eq!(
            pairs.iter().find(|(k, _)| k == "q").unwrap().1,
            "AC/DC & Beyoncé"
        );
        assert_eq!(pairs.iter().filter(|(k, _)| k == "offset").count(), 1);
        assert_eq!(pairs.iter().find(|(k, _)| k == "offset").unwrap().1, "20");
        assert!(page(vec![], &url, 20, false)["next"].is_null());
    }
}
