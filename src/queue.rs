//! Durable queue wiring on top of SQLite.
//!
//! Two rules matter here and both are about recovery after a crash:
//! a worker name that is unique per run, and a heartbeat short enough that a
//! relaunch reclaims the dead run's job within seconds instead of a minute.
//! `job_view` is our own read model, so the phone never depends on Apalis's
//! internal tables.

use std::time::Duration;

use apalis::prelude::{TaskBuilder, TaskId, TaskSink};
use apalis_sqlite::{Config, SqliteStorage};
use sqlx::SqlitePool;

use crate::job::Job;

pub const QUEUE: &str = "pocket";

const VIEW: &str = "
CREATE TABLE IF NOT EXISTS job_view (
    id          TEXT PRIMARY KEY,
    text        TEXT NOT NULL,
    status      TEXT NOT NULL,
    attempts    INTEGER NOT NULL DEFAULT 0,
    result      TEXT,
    enqueued_at INTEGER NOT NULL,
    done_at     INTEGER
)";

pub fn storage(pool: &SqlitePool) -> SqliteStorage<Job> {
    SqliteStorage::new(pool).with_config(
        Config::default()
            .queue(QUEUE)
            .batch_size(1)
            .heartbeat_interval(Duration::from_millis(1500))
            .missed_heartbeats(2),
    )
}

/// Migrations, then our own table. Nothing may be served before this returns.
pub async fn prepare(pool: &SqlitePool) -> Result<(), rivium::Error> {
    SqliteStorage::<()>::setup(pool).await.map_err(internal)?;
    sqlx::query(VIEW).execute(pool).await.map_err(internal)?;
    Ok(())
}

pub async fn enqueue(pool: &SqlitePool, job: Job) -> Result<(), rivium::Error> {
    sqlx::query(
        "INSERT INTO job_view (id, text, status, attempts, enqueued_at) \
         VALUES (?1, ?2, 'pending', 0, ?3)",
    )
    .bind(&job.id)
    .bind(&job.text)
    .bind(now_ms())
    .execute(pool)
    .await
    .map_err(internal)?;

    let task = TaskBuilder::new(job.clone())
        .task_id(TaskId::from_string(&job.id))
        .max_attempts(5)
        .build();

    if let Err(error) = storage(pool).push_task(task).await {
        // A view row with no task behind it would claim work that will never
        // run, so remove it before reporting the failure.
        let _ = sqlx::query("DELETE FROM job_view WHERE id = ?1")
            .bind(&job.id)
            .execute(pool)
            .await;
        return Err(internal(error.to_string()));
    }

    Ok(())
}

/// A fresh identity per run. Apalis 1.0 rejects a second worker registering
/// under a name whose previous row is still present, so reusing a fixed name
/// makes a fast relaunch fail to take over the dead worker's job.
pub fn worker_name() -> String {
    format!("pocket-{}", new_id())
}

pub fn new_id() -> String {
    ulid::Ulid::generate().to_string()
}

pub fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or_default()
}

fn internal(error: impl std::fmt::Display) -> rivium::Error {
    rivium::Error::internal(error.to_string())
}
