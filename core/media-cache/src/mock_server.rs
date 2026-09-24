//! Minimal loopback HTTP/1.1 mock server, `#[cfg(test)]`-only, so sync-engine
//! tests exercise the real `JellyfinClient` against canned responses. GET-only.

use std::collections::BTreeMap;
use std::sync::{Arc, Mutex};

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;

struct Route {
    path: String,
    /// Params that must match exactly; unlisted request params are ignored.
    query: BTreeMap<String, String>,
    body: serde_json::Value,
    /// Delay before writing the response, to simulate a slow page fetch mid-flight.
    delay: Option<std::time::Duration>,
}

pub(crate) struct MockServer {
    pub(crate) base_url: String,
    routes: Arc<Mutex<Vec<Route>>>,
    requests: Arc<Mutex<Vec<String>>>,
    _handle: tokio::task::JoinHandle<()>,
}

impl MockServer {
    pub(crate) async fn start() -> Self {
        let listener = TcpListener::bind("127.0.0.1:0")
            .await
            .expect("bind loopback");
        let addr = listener.local_addr().expect("local_addr");
        let routes: Arc<Mutex<Vec<Route>>> = Arc::new(Mutex::new(Vec::new()));
        let requests: Arc<Mutex<Vec<String>>> = Arc::new(Mutex::new(Vec::new()));

        let routes_for_task = routes.clone();
        let requests_for_task = requests.clone();
        let handle = tokio::spawn(async move {
            loop {
                let Ok((mut stream, _)) = listener.accept().await else {
                    break;
                };
                let routes = routes_for_task.clone();
                let requests = requests_for_task.clone();
                tokio::spawn(async move {
                    let mut buf = vec![0u8; 16384];
                    let n = match stream.read(&mut buf).await {
                        Ok(n) if n > 0 => n,
                        _ => return,
                    };
                    let request = String::from_utf8_lossy(&buf[..n]);
                    let Some(first_line) = request.lines().next() else {
                        return;
                    };
                    let mut parts = first_line.split_whitespace();
                    let _method = parts.next().unwrap_or("");
                    let target = parts.next().unwrap_or("/").to_string();

                    {
                        let mut log = requests.lock().unwrap_or_else(|e| e.into_inner());
                        log.push(target.clone());
                    }

                    let (path, query) = match target.split_once('?') {
                        Some((p, q)) => (p.to_string(), parse_query(q)),
                        None => (target, BTreeMap::new()),
                    };

                    let matched = {
                        let routes = routes.lock().unwrap_or_else(|e| e.into_inner());
                        routes
                            .iter()
                            .find(|r| {
                                r.path == path
                                    && r.query.iter().all(|(k, v)| query.get(k) == Some(v))
                            })
                            .map(|r| (r.body.to_string(), r.delay))
                    };
                    if let Some((_, Some(delay))) = &matched {
                        tokio::time::sleep(*delay).await;
                    }
                    let body = matched.map(|(body, _)| body);

                    let (status, body) = match body {
                        Some(b) => ("200 OK", b),
                        None => (
                            "404 Not Found",
                            format!("{{\"error\":\"no route for {path}\"}}"),
                        ),
                    };
                    let response = format!(
                        "HTTP/1.1 {status}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                        body.len()
                    );
                    let _ = stream.write_all(response.as_bytes()).await;
                    let _ = stream.shutdown().await;
                });
            }
        });

        Self {
            base_url: format!("http://{addr}"),
            routes,
            requests,
            _handle: handle,
        }
    }

    pub(crate) fn route(&self, path: &str, query: &[(&str, &str)], body: serde_json::Value) {
        self.route_inner(path, query, body, None);
    }

    /// Same as [`Self::route`], but delays the response by `delay` to simulate a slow page fetch.
    pub(crate) fn route_delayed(
        &self,
        path: &str,
        query: &[(&str, &str)],
        body: serde_json::Value,
        delay: std::time::Duration,
    ) {
        self.route_inner(path, query, body, Some(delay));
    }

    fn route_inner(
        &self,
        path: &str,
        query: &[(&str, &str)],
        body: serde_json::Value,
        delay: Option<std::time::Duration>,
    ) {
        let mut routes = self.routes.lock().unwrap_or_else(|e| e.into_inner());
        routes.push(Route {
            path: path.to_string(),
            query: query
                .iter()
                .map(|(k, v)| (k.to_string(), v.to_string()))
                .collect(),
            body,
            delay,
        });
    }

    pub(crate) fn request_count(&self, path_prefix: &str) -> usize {
        let log = self.requests.lock().unwrap_or_else(|e| e.into_inner());
        log.iter().filter(|t| t.starts_with(path_prefix)).count()
    }

    /// Counts requests whose full target (path + query) contains `needle`, e.g. to
    /// assert a sweep used ids-only vs. full-DTO fields.
    pub(crate) fn request_count_matching(&self, needle: &str) -> usize {
        let log = self.requests.lock().unwrap_or_else(|e| e.into_inner());
        log.iter().filter(|t| t.contains(needle)).count()
    }
}

fn parse_query(q: &str) -> BTreeMap<String, String> {
    q.split('&')
        .filter_map(|pair| pair.split_once('='))
        .map(|(k, v)| (urldecode(k), urldecode(v)))
        .collect()
}

fn urldecode(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut chars = s.chars();
    while let Some(c) = chars.next() {
        match c {
            '%' => {
                let hex: String = chars.by_ref().take(2).collect();
                if let Ok(byte) = u8::from_str_radix(&hex, 16) {
                    out.push(byte as char);
                } else {
                    out.push('%');
                    out.push_str(&hex);
                }
            }
            '+' => out.push(' '),
            other => out.push(other),
        }
    }
    out
}
