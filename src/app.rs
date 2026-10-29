//! The application: one queue worker, one loopback HTTP listener.

use std::{net::SocketAddr, path::Path, sync::Arc};

use apalis::prelude::{WorkerBuilder, WorkerBuilderExt, WorkerError};
use rivium::{Error, Health, Kind, Result, Service, service};
use rivium_host::{App, Context};
use rivium_http::HttpServer;
use serde::{Deserialize, Serialize};
use sqlx::SqlitePool;
use tokio::sync::OnceCell;

use crate::{http, job, media, queue};

/// Shared handle to the database.
///
/// `App::services` runs synchronously and outside any Tokio runtime, so the
/// pool cannot be created there: building one spawns a maintenance task. The
/// queue service fills this cell before it reports ready, and the HTTP service
/// can therefore rely on it being present for the whole time it serves.
pub type Db = Arc<OnceCell<SqlitePool>>;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Config {
    pub listen: SocketAddr,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            listen: SocketAddr::from(([127, 0, 0, 1], 8787)),
        }
    }
}

pub struct Pocket;

impl App for Pocket {
    const NAME: &'static str = "pocket";
    const VERSION: &'static str = env!("CARGO_PKG_VERSION");
    type Config = Config;

    fn services(config: &Config, context: &Context<Config>) -> Result<Vec<Box<dyn Service>>> {
        let root = context.paths().root().to_path_buf();
        let db: Db = Arc::new(OnceCell::new());
        let health = context.health().register("queue")?;

        // Background tier: on shutdown the frontline listener stops accepting
        // first, then this worker drains what it already took.
        let worker = {
            let db = db.clone();
            service("queue", Kind::Background, move |ctx| async move {
                std::fs::create_dir_all(&root).map_err(internal_fmt)?;
                let pool = db
                    .get_or_try_init(|| async { open(&root).await })
                    .await?
                    .clone();

                // Migrations and our own table must exist before ready:
                // the supervisor enters Running only when every root is ready,
                // and the HTTP listener serves only after that. Readiness is
                // the migration barrier.
                queue::prepare(&pool).await?;
                health.set(Health::Healthy);
                ctx.ready();

                let downloads = media::Downloads(root.join("downloads"));
                let worker = WorkerBuilder::new(queue::worker_name())
                    .backend(queue::storage(&pool))
                    .data(pool.clone())
                    .data(downloads)
                    .concurrency(1)
                    .build(job::handle);

                let stop = ctx.stop();
                worker
                    .run_until(async move {
                        stop.stopped().await;
                        Ok::<(), WorkerError>(())
                    })
                    .await
                    .map_err(internal_fmt)
            })
        };

        let listener = HttpServer::new("http", config.listen, http::router(db));

        Ok(vec![worker, Box::new(listener)])
    }
}

async fn open(root: &Path) -> Result<SqlitePool> {
    let url = format!("sqlite://{}?mode=rwc", root.join("jobs.sqlite3").display());
    SqlitePool::connect(&url).await.map_err(internal_fmt)
}

fn internal_fmt(error: impl std::fmt::Display) -> Error {
    Error::internal(error.to_string())
}
