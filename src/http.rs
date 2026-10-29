//! Loopback HTTP. The phone talks to this; so does curl on a laptop.

use axum::{
    Json, Router,
    extract::{Path, State},
    http::StatusCode,
    response::{IntoResponse, Response},
    routing::{get, post},
};
use serde::{Deserialize, Serialize};
use sqlx::Row;

use crate::{app::Db, job::Job, queue};

#[derive(Debug, Deserialize)]
pub struct NewJob {
    pub text: String,
    #[serde(default)]
    pub work_ms: Option<u64>,
    #[serde(default)]
    pub action: Option<String>,
}

#[derive(Debug, Serialize)]
pub struct Accepted {
    pub id: String,
}

#[derive(Debug, Serialize)]
pub struct JobView {
    pub id: String,
    pub text: String,
    pub status: String,
    pub attempts: i64,
    pub result: Option<serde_json::Value>,
    pub enqueued_at: i64,
    pub done_at: Option<i64>,
}

pub fn router(db: Db) -> Router {
    Router::new()
        .route("/jobs", post(enqueue).get(list))
        .route("/jobs/{id}", get(one))
        .with_state(db)
}

async fn enqueue(
    State(db): State<Db>,
    Json(body): Json<NewJob>,
) -> Result<(StatusCode, Json<Accepted>), ApiError> {
    let job = Job {
        id: queue::new_id(),
        text: body.text,
        work_ms: body.work_ms,
        action: body.action,
    };
    let id = job.id.clone();
    let pool = ready(&db)?;

    queue::enqueue(&pool, job)
        .await
        .map_err(|error| ApiError::internal(error.to_string()))?;

    Ok((StatusCode::CREATED, Json(Accepted { id })))
}

async fn list(State(db): State<Db>) -> Result<Json<Vec<JobView>>, ApiError> {
    let pool = ready(&db)?;
    let rows = sqlx::query(
        "SELECT id, text, status, attempts, result, enqueued_at, done_at \
         FROM job_view ORDER BY enqueued_at DESC LIMIT 50",
    )
        .fetch_all(&pool)
        .await
        .map_err(ApiError::from)?;

    Ok(Json(rows.iter().map(row_to_view).collect()))
}

async fn one(
    State(db): State<Db>,
    Path(id): Path<String>,
) -> Result<Json<JobView>, ApiError> {
    let pool = ready(&db)?;
    let row = sqlx::query(
        "SELECT id, text, status, attempts, result, enqueued_at, done_at \
         FROM job_view WHERE id = ?1",
    )
        .bind(id)
        .fetch_optional(&pool)
        .await
        .map_err(ApiError::from)?;

    row.as_ref().map(row_to_view).map(Json).ok_or(ApiError::not_found())
}

fn row_to_view(row: &sqlx::sqlite::SqliteRow) -> JobView {
    let result: Option<String> = row.get("result");
    JobView {
        id: row.get("id"),
        text: row.get("text"),
        status: row.get("status"),
        attempts: row.get("attempts"),
        result: result.and_then(|text| serde_json::from_str(&text).ok()),
        enqueued_at: row.get("enqueued_at"),
        done_at: row.get("done_at"),
    }
}

/// The cell is filled before the listener serves, so a missing pool means the
/// caller raced the supervisor rather than a normal request.
fn ready(db: &Db) -> Result<sqlx::SqlitePool, ApiError> {
    db.get()
        .cloned()
        .ok_or_else(|| ApiError::internal("database is not open yet".into()))
}

pub struct ApiError {
    status: StatusCode,
    message: String,
}

impl ApiError {
    fn not_found() -> Self {
        Self {
            status: StatusCode::NOT_FOUND,
            message: "no such job".into(),
        }
    }

    fn internal(message: String) -> Self {
        Self {
            status: StatusCode::INTERNAL_SERVER_ERROR,
            message,
        }
    }
}

impl From<sqlx::Error> for ApiError {
    fn from(error: sqlx::Error) -> Self {
        Self::internal(error.to_string())
    }
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        (self.status, Json(serde_json::json!({ "error": self.message }))).into_response()
    }
}
