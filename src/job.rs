//! The one job this app knows how to do: describe a piece of text.

use std::time::{Duration, Instant};

use apalis::prelude::{BoxDynError, Data};
use crate::media::{self, Downloads};
use serde::{Deserialize, Serialize};
use sqlx::SqlitePool;

/// Text to describe. `work_ms` only exists so a job can be made slow on
/// purpose while testing shutdown and crash recovery.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Job {
    pub id: String,
    pub text: String,
    #[serde(default)]
    pub work_ms: Option<u64>,
    /// What to do with the text: nothing means describe it, "video" means the
    /// text is a link to archive.
    #[serde(default)]
    pub action: Option<String>,
}

/// What the phone shows when the job is done.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Outcome {
    pub chars: usize,
    pub words: usize,
    pub unique_words: usize,
    pub hash: String,
    pub elapsed_ms: u64,
}

impl Job {
    pub fn run(&self) -> Outcome {
        let started = Instant::now();
        let chars = self.text.chars().count();
        let words = self.text.split_whitespace().count();
        let mut seen: Vec<String> = self
            .text
            .split_whitespace()
            .map(|word| word.trim_matches(|c: char| !c.is_alphanumeric()).to_lowercase())
            .filter(|word| !word.is_empty())
            .collect();
        seen.sort();
        seen.dedup();

        Outcome {
            chars,
            words,
            unique_words: seen.len(),
            hash: format!("{:016x}", fnv1a64(self.text.as_bytes())),
            elapsed_ms: started.elapsed().as_millis() as u64,
        }
    }
}

/// FNV-1a: short, stable, and identical on every platform.
fn fnv1a64(bytes: &[u8]) -> u64 {
    let mut hash = 0xcbf2_9ce4_8422_2325u64;
    for byte in bytes {
        hash ^= u64::from(*byte);
        hash = hash.wrapping_mul(0x100_0000_01b3);
    }
    hash
}

/// Apalis calls this once per attempt. It is idempotent: re-running a job
/// overwrites the same row instead of adding a second result.
///
/// Every exit path leaves `job_view` telling the truth. A job that cannot run
/// must never sit at `running` forever, which is what happens if the only
/// writes are "running" and "done".
pub async fn handle(
    job: Job,
    pool: Data<SqlitePool>,
    downloads: Data<Downloads>,
) -> Result<(), BoxDynError> {
    mark_running(&pool, &job).await?;

    let dir: &Downloads = &downloads;
    match job.action.as_deref() {
        Some("video") => return archive(&pool, &job, &dir.0).await,
        Some("card") => return capture_card(&pool, &job, &dir.0).await,
        _ => {}
    }

    // Nothing to describe is permanent: retrying cannot fix it, so record the
    // reason and finish rather than burning five attempts.
    if job.text.trim().is_empty() {
        record_failed(&pool, &job, "nothing to describe").await?;
        return Ok(());
    }

    if let Some(ms) = job.work_ms {
        tokio::time::sleep(Duration::from_millis(ms)).await;
    }

    // A real error is transient: say why, then let Apalis retry. The next
    // attempt puts the row back to `running`; if the attempts run out, the row
    // rests at `failed` with the reason, which is still the truth.
    if let Err(error) = record_done(&pool, &job).await {
        let _ = record_failed(&pool, &job, &error.to_string()).await;
        return Err(error);
    }

    Ok(())
}

/// How many interruptions a single transfer may survive before it is dropped.
const MAX_INTERRUPTIONS: i64 = 6;

/// Resolve the shared link and save its best stream, then record where it went.
async fn archive(
    pool: &SqlitePool,
    job: &Job,
    into: &std::path::Path,
) -> Result<(), BoxDynError> {
    // Interruptions do not spend the queue's attempt budget, so a transfer that
    // keeps dying would resume forever and hold the worker. Give up eventually.
    let attempts: i64 = sqlx::query_scalar("SELECT attempts FROM job_view WHERE id = ?1")
        .bind(&job.id)
        .fetch_optional(pool)
        .await
        .ok()
        .flatten()
        .unwrap_or(0);
    if attempts > MAX_INTERRUPTIONS {
        record_failed(pool, job, "abandoned after repeated interruptions").await?;
        return Ok(());
    }

    match media::save_video(&job.text, into).await {
        Ok(saved) => record_json(pool, job, saved).await?,
        // A dead link or a post with no video will never succeed: record why
        // and stop burning attempts.
        Err(media::MediaError::Permanent(reason)) => record_failed(pool, job, &reason).await?,
        // A dropped connection is worth retrying: the next attempt resumes from
        // the bytes already on disk. Recording first keeps the row honest.
        Err(media::MediaError::Transient(reason)) => {
            record_failed(pool, job, &reason).await?;
            return Err(reason.into());
        }
    }
    Ok(())
}

/// Gather the post's public metadata and its images for the UI to draw.
async fn capture_card(
    pool: &SqlitePool,
    job: &Job,
    into: &std::path::Path,
) -> Result<(), BoxDynError> {
    match media::save_card(&job.text, into).await {
        Ok(card) => record_json(pool, job, card).await?,
        Err(media::MediaError::Permanent(reason)) => record_failed(pool, job, &reason).await?,
        Err(media::MediaError::Transient(reason)) => {
            record_failed(pool, job, &reason).await?;
            return Err(reason.into());
        }
    }
    Ok(())
}

async fn mark_running(pool: &SqlitePool, job: &Job) -> Result<(), sqlx::Error> {
    sqlx::query(
        "UPDATE job_view SET status = 'running', attempts = attempts + 1, done_at = NULL \
         WHERE id = ?1",
    )
    .bind(&job.id)
    .execute(pool)
    .await
    .map(|_| ())
}

async fn record_done(pool: &SqlitePool, job: &Job) -> Result<(), BoxDynError> {
    record_json(pool, job, serde_json::to_value(job.run())?).await
}

async fn record_json(
    pool: &SqlitePool,
    job: &Job,
    value: serde_json::Value,
) -> Result<(), BoxDynError> {
    let outcome = value.to_string();
    sqlx::query("UPDATE job_view SET status = 'done', result = ?2, done_at = ?3 WHERE id = ?1")
        .bind(&job.id)
        .bind(outcome)
        .bind(crate::queue::now_ms())
        .execute(pool)
        .await?;
    Ok(())
}

async fn record_failed(pool: &SqlitePool, job: &Job, reason: &str) -> Result<(), sqlx::Error> {
    let result = serde_json::json!({ "error": reason }).to_string();
    sqlx::query("UPDATE job_view SET status = 'failed', result = ?2, done_at = ?3 WHERE id = ?1")
        .bind(&job.id)
        .bind(result)
        .bind(crate::queue::now_ms())
        .execute(pool)
        .await
        .map(|_| ())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn job(text: &str) -> Job {
        Job {
            id: "t".into(),
            text: text.into(),
            work_ms: None,
            action: None,
        }
    }

    #[test]
    fn counts_words_and_characters() {
        let out = job("the quick brown fox").run();
        assert_eq!(out.chars, 19);
        assert_eq!(out.words, 4);
        assert_eq!(out.unique_words, 4);
    }

    #[test]
    fn repeated_words_collapse_and_punctuation_is_ignored() {
        let out = job("fox. Fox, FOX! dog").run();
        assert_eq!(out.words, 4);
        assert_eq!(out.unique_words, 2);
    }

    #[test]
    fn hash_is_stable_and_text_dependent() {
        assert_eq!(job("hello").run().hash, job("hello").run().hash);
        assert_ne!(job("hello").run().hash, job("hello!").run().hash);
        assert_eq!(job("hello").run().hash.len(), 16);
    }
}
