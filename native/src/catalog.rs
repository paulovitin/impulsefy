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

mod collection_protocol {
    include!(concat!(env!("OUT_DIR"), "/collection_protocol/mod.rs"));
}
use collection_protocol::collection2v2;

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

// Persisted operations from Spotify's web-player bundle (2026-10-03).
// https://open.spotifycdn.com/cdn/build/web-player/xpui-routes-search.0197b7d2.js
// https://open.spotifycdn.com/cdn/build/web-player/web-player.06a1e8e8.js
// Keep these isolated: Spotify may rotate them independently of playback.
async fn query(session: &Session, operation: &str, hash: &str, variables: Value) -> Result<Value> {
    let token = session.login5().auth_token().await.map_err(failure)?;
    let client_token = session.spclient().client_token().await.map_err(failure)?;
    let body = serde_json::to_vec(&json!({"operationName":operation,"variables":variables,
        "extensions":{"persistedQuery":{"version":1,"sha256Hash":hash}}}))
    .map_err(failure)?;
    let request = http::Request::builder()
        .method(Method::POST)
        .uri("https://api-partner.spotify.com/pathfinder/v2/query")
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .header("app-platform", "WebPlayer")
        .header("spotify-app-version", "896000000")
        .header(
            "Authorization",
            format!("{} {}", token.token_type, token.access_token),
        )
        .header("client-token", client_token)
        .body(body.into())
        .map_err(failure)?;
    let bytes = session
        .http_client()
        .request_body(request)
        .await
        .map_err(failure)?;
    let response: Value = serde_json::from_slice(&bytes).map_err(failure)?;
    if response["errors"].as_array().is_some_and(|e| !e.is_empty()) || response["data"].is_null() {
        return Err("O Spotify não disponibilizou esta função agora. Tente novamente.".into());
    }
    Ok(response["data"].clone())
}

pub async fn request(session: &Session, method: &str, path: &str, body: &Value) -> Result<Value> {
    if method == "GET" {
        return read(session, path).await;
    }
    if !matches!(method, "PUT" | "DELETE") || path != "/me/tracks" {
        return Err("Operação indisponível.".into());
    }
    let ids = body["ids"]
        .as_array()
        .filter(|ids| !ids.is_empty() && ids.len() <= 50)
        .ok_or("Seleção de músicas inválida.")?;
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_err(failure)?;
    let mut items = Vec::new();
    for value in ids {
        let id =
            SpotifyId::from_base62(value.as_str().ok_or("Música inválida.")?).map_err(failure)?;
        items.push(collection2v2::CollectionItem {
            uri: SpotifyUri::Track { id }.to_uri().map_err(failure)?,
            added_at: if method == "PUT" {
                now.as_secs().try_into().map_err(failure)?
            } else {
                0
            },
            is_removed: method == "DELETE",
            ..Default::default()
        });
    }
    let payload = collection2v2::WriteRequest {
        username: session.username(),
        set: "collection".into(),
        items,
        client_update_id: format!("impulsefy-{}", now.as_nanos()),
        ..Default::default()
    }
    .write_to_bytes()
    .map_err(failure)?;
    session
        .spclient()
        .request(
            &Method::POST,
            "/collection/v2/write",
            Some(collection_headers()),
            Some(&payload),
        )
        .await
        .map_err(failure)?;
    Ok(json!({}))
}

fn collection_headers() -> http::HeaderMap {
    let mut headers = http::HeaderMap::new();
    let content = http::HeaderValue::from_static("application/vnd.collection-v2.spotify.proto");
    headers.insert(http::header::CONTENT_TYPE, content.clone());
    headers.insert(http::header::ACCEPT, content);
    headers
}

async fn saved_window(
    session: &Session,
    kind: &str,
    offset: usize,
    limit: usize,
) -> Result<(Vec<SpotifyUri>, bool)> {
    let mut token = String::new();
    let mut uris = Vec::new();
    let mut seen = 0;
    // Only identifiers are paged here; metadata is loaded for the visible page.
    for _ in 0..200 {
        let request = collection2v2::PageRequest {
            username: session.username(),
            set: if kind == "artist" {
                "artist"
            } else {
                "collection"
            }
            .into(),
            pagination_token: token.clone(),
            limit: 300,
            ..Default::default()
        }
        .write_to_bytes()
        .map_err(failure)?;
        let bytes = session
            .spclient()
            .request(
                &Method::POST,
                "/collection/v2/paging",
                Some(collection_headers()),
                Some(&request),
            )
            .await
            .map_err(failure)?;
        let page = collection2v2::PageResponse::parse_from_bytes(&bytes).map_err(failure)?;
        for item in page.items {
            if item.is_removed || !item.uri.starts_with(&format!("spotify:{kind}:")) {
                continue;
            }
            let Ok(uri) = SpotifyUri::from_uri(&item.uri) else {
                continue;
            };
            if seen >= offset {
                if uris.len() == limit {
                    return Ok((uris, true));
                }
                uris.push(uri);
            }
            seen += 1;
        }
        if page.next_page_token.is_empty() {
            return Ok((uris, false));
        }
        if page.next_page_token == token {
            return Err(failure("cyclic collection cursor"));
        }
        token = page.next_page_token;
    }
    Err("Esta coleção é grande demais para carregar agora.".into())
}

async fn entities(session: &Session, uris: Vec<SpotifyUri>) -> Result<Vec<Value>> {
    let results: Vec<_> = stream::iter(uris).map(|uri| async move {
        let value = match &uri {
            SpotifyUri::Album { id } => {
                let album = librespot_metadata::Album::get(session, &uri).await.map_err(failure)?;
                let artists: Vec<_> = album.artists.iter().map(|a| json!({"name":a.name})).collect();
                json!({"id":id.to_base62().map_err(failure)?,"uri":uri.to_uri().map_err(failure)?,"name":album.name,
                    "artists":artists,"images":images(&album.covers),"total_tracks":album.tracks().count(),"type":"album"})
            }
            SpotifyUri::Artist { id } => {
                let artist = librespot_metadata::Artist::get(session, &uri).await.map_err(failure)?;
                json!({"id":id.to_base62().map_err(failure)?,"uri":uri.to_uri().map_err(failure)?,"name":artist.name,
                    "images":images(&artist.portraits),"type":"artist"})
            }
            _ => return Err(failure("invalid entity")),
        };
        Ok(value)
    }).buffered(6).collect().await;
    if !results.is_empty() && results.iter().all(|r| r.is_err()) {
        return Err(failure("metadata"));
    }
    Ok(results.into_iter().filter_map(|r| r.ok()).collect())
}

fn images(images: &librespot_metadata::image::Images) -> Vec<Value> {
    images
        .iter()
        .filter_map(|i| {
            i.id.to_base16()
                .ok()
                .map(|id| json!({"url":format!("https://i.scdn.co/image/{id}"),"width":i.width}))
        })
        .collect()
}

async fn search_entities(
    session: &Session,
    url: &Url,
    kind: &str,
    term: &str,
    offset: usize,
    limit: usize,
) -> Result<Value> {
    let (operation, hash, key) = if kind == "artist" {
        (
            "searchArtists",
            "7bf95d754fdbe32c8b161fbbe54d1ae50974900df4dce4c8f1afcbcad153224d",
            "artists",
        )
    } else {
        (
            "searchAlbums",
            "202cb3305e31e5a0767ba7925f28bd728cf8f8b0217e6da43909056071cd70e9",
            "albumsV2",
        )
    };
    let data = query(
        session,
        operation,
        hash,
        json!({"searchTerm":term,"offset":offset,"limit":limit,
        "includeAudiobooks":true,"includePreReleases":false,"includeAlbumPreReleases":false,
        "includeAuthors":false,"includeEpisodeContentRatingsV2":false}),
    )
    .await?;
    let hits = &data["searchV2"][key];
    let entries = hits["items"]
        .as_array()
        .ok_or_else(|| failure("missing search results"))?;
    let mut items = Vec::new();
    for hit in entries {
        let entity = &hit["data"];
        let uri = entity["uri"].as_str().unwrap_or_default();
        if !uri.starts_with(&format!("spotify:{kind}:")) || SpotifyUri::from_uri(uri).is_err() {
            continue;
        }
        let name = if kind == "artist" {
            &entity["profile"]["name"]
        } else {
            &entity["name"]
        };
        let artwork = if kind == "artist" {
            &entity["visuals"]["avatarImage"]["sources"]
        } else {
            &entity["coverArt"]["sources"]
        };
        let artists: Vec<_> = entity["artists"]["items"]
            .as_array()
            .into_iter()
            .flatten()
            .map(|a| json!({"name":a["profile"]["name"]}))
            .collect();
        items.push(json!({"id":uri.rsplit(':').next().unwrap_or_default(),"uri":uri,"name":name,"images":artwork,"artists":artists,"type":kind}));
    }
    let total = hits["totalCount"].as_u64();
    let more = total.map_or(entries.len() == limit, |n| {
        (offset + entries.len()) < n as usize
    });
    let mut result = page(items, url, offset + entries.len(), more);
    if let Some(total) = total {
        result["total"] = json!(total);
    }
    Ok(result)
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
        "/v1/me/tracks/contains" => {
            let ids = url
                .query_pairs()
                .find(|(k, _)| k == "ids")
                .map(|(_, v)| v.into_owned())
                .unwrap_or_default();
            let mut uris = Vec::new();
            for id in ids.split(',') {
                let id = SpotifyId::from_base62(id).map_err(failure)?;
                uris.push(SpotifyUri::Track { id }.to_uri().map_err(failure)?);
            }
            if uris.is_empty() || uris.len() > 50 {
                return Err("Seleção inválida.".into());
            }
            let data = query(
                session,
                "areEntitiesInLibrary",
                "134337999233cc6fdd6b1e6dbf94841409f04a946c5c7b744b09ba0dfe5a85ed",
                json!({"uris":uris}),
            )
            .await?;
            let lookup = data["lookup"]
                .as_array()
                .filter(|v| v.len() == uris.len())
                .ok_or_else(|| failure("invalid saved states"))?;
            let saved: Vec<_> = lookup
                .iter()
                .map(|v| {
                    v["data"]["saved"]
                        .as_bool()
                        .ok_or_else(|| failure("missing saved state"))
                })
                .collect::<Result<_>>()?;
            Ok(json!({"saved":saved}))
        }
        "/v1/me/albums" | "/v1/me/artists" => {
            let kind = if url.path().ends_with("albums") {
                "album"
            } else {
                "artist"
            };
            let (uris, more) = saved_window(session, kind, offset, limit).await?;
            Ok(page(
                entities(session, uris).await?,
                &url,
                offset + limit,
                more,
            ))
        }
        path if path.starts_with("/v1/albums/") || path.starts_with("/v1/artists/") => {
            let parts: Vec<_> = path.split('/').collect();
            if parts.len() != 5 || parts[4] != "tracks" {
                return Err("Conteúdo inválido.".into());
            }
            let id = SpotifyId::from_base62(parts[3]).map_err(failure)?;
            let uri = if parts[2] == "albums" {
                SpotifyUri::Album { id }
            } else {
                SpotifyUri::Artist { id }
            };
            let context = session
                .spclient()
                .get_context(&uri.to_uri().map_err(failure)?)
                .await
                .map_err(failure)?;
            let (uris, more) = context_window(session, context.pages, offset, limit).await?;
            Ok(page(
                tracks(session, uris).await?,
                &url,
                offset + limit,
                more,
            ))
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
                let kind = url
                    .query_pairs()
                    .find(|(k, _)| k == "type")
                    .map(|(_, v)| v.into_owned())
                    .unwrap_or_else(|| "track".into());
                if matches!(kind.as_str(), "artist" | "album") {
                    return search_entities(session, &url, &kind, &query, offset, limit).await;
                }
                if kind != "track" {
                    return Err("Tipo de busca inválido.".into());
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
            let mut items = tracks(session, uris).await?;
            if !search {
                for item in &mut items {
                    item["saved"] = json!(true);
                }
            }
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
            let mut result = page(
                items,
                &url,
                next,
                contents.truncated() || next < (list.length().max(0) as usize),
            );
            if list.has_length() {
                result["total"] = json!(list.length().max(0));
            }
            Ok(result)
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
        Ok::<_,String>(json!({"id":uri.to_uri().map_err(failure)?.rsplit(':').next(),"uri":uri.to_uri().map_err(failure)?,"name":track.name,"duration_ms":track.duration,"artists":artists,"album":{"name":track.album.name,"images":images},"type":"track"}))
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
            "/albums/not-an-id/tracks",
            "/artists/not-an-id/tracks",
            "/search?q=test&type=unsupported",
            "/me/tracks/contains?ids=bad-id",
        ] {
            assert!(read(&session, path).await.is_err(), "accepted {path}");
        }
        for (method, path, body) in [
            (
                "POST",
                "/me/tracks",
                json!({"ids":["4uLU6hMCjMI75M1A2tKUQC"]}),
            ),
            ("DELETE", "/me/playlists", json!({})),
            ("PUT", "/me/tracks", json!({"ids":[]})),
            ("PUT", "/me/tracks", json!({"ids":["invalid"]})),
        ] {
            assert!(request(&session, method, path, &body).await.is_err());
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
