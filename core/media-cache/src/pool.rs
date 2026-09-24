//! Read-only connection pool, a plain blocking pool (`Mutex` + `Condvar`) since browse
//! queries run sync on the UI's thread pool.

use std::path::Path;
use std::sync::{Condvar, Mutex};

use rusqlite::Connection;

use crate::CacheError;

pub(crate) struct ReadPool {
    conns: Mutex<Vec<Connection>>,
    cond: Condvar,
}

impl ReadPool {
    pub(crate) fn open(path: &Path, size: usize) -> Result<Self, CacheError> {
        let mut conns = Vec::with_capacity(size);
        for _ in 0..size.max(1) {
            conns.push(crate::schema::open_reader(path)?);
        }
        Ok(Self {
            conns: Mutex::new(conns),
            cond: Condvar::new(),
        })
    }

    /// Blocks until a connection is available; queries are indexed and fast (<50ms), so contention
    /// is brief.
    pub(crate) fn acquire(&self) -> PooledConn<'_> {
        let mut guard = self.conns.lock().unwrap_or_else(|e| e.into_inner());
        loop {
            if let Some(conn) = guard.pop() {
                return PooledConn {
                    pool: self,
                    conn: Some(conn),
                };
            }
            guard = self.cond.wait(guard).unwrap_or_else(|e| e.into_inner());
        }
    }

    fn release(&self, conn: Connection) {
        let mut guard = self.conns.lock().unwrap_or_else(|e| e.into_inner());
        guard.push(conn);
        drop(guard);
        self.cond.notify_one();
    }
}

pub(crate) struct PooledConn<'a> {
    pool: &'a ReadPool,
    conn: Option<Connection>,
}

impl std::ops::Deref for PooledConn<'_> {
    type Target = Connection;
    fn deref(&self) -> &Connection {
        self.conn
            .as_ref()
            .expect("PooledConn always holds a connection until Drop")
    }
}

impl Drop for PooledConn<'_> {
    fn drop(&mut self) {
        if let Some(conn) = self.conn.take() {
            self.pool.release(conn);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn acquire_and_release_recycles_connections() {
        let dir = tempfile::tempdir().expect("tempdir");
        let path = dir.path().join("mirror.db");
        let (_conn, _) = crate::schema::open_and_prepare(&path).expect("open");

        let pool = ReadPool::open(&path, 2).expect("pool");
        {
            let a = pool.acquire();
            let _n: i64 = a.query_row("SELECT 1", [], |r| r.get(0)).expect("query");
        }
        // Should not block: released back to the pool above.
        let _b = pool.acquire();
        let _c = pool.acquire();
    }
}
