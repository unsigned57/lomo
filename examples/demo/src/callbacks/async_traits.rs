use boltffi::*;

use crate::enums::c_style::Status;
use crate::records::blittable::Point;
use crate::results::error_enums::MathError;

#[export]
#[allow(async_fn_in_trait)]
pub trait AsyncFetcher: Send + Sync {
    async fn fetch_value(&self, key: i32) -> i32;
    async fn fetch_string(&self, input: String) -> String;
    async fn fetch_joined_message(&self, scope: &str, message: &str) -> String;
}

#[export]
pub async fn fetch_with_async_callback(fetcher: impl AsyncFetcher, key: i32) -> i32 {
    fetcher.fetch_value(key).await
}

#[export]
pub async fn fetch_string_with_async_callback(fetcher: impl AsyncFetcher, input: String) -> String {
    fetcher.fetch_string(input).await
}

#[export]
pub async fn fetch_joined_message_with_async_callback(
    fetcher: impl AsyncFetcher,
    scope: String,
    message: String,
) -> String {
    fetcher.fetch_joined_message(&scope, &message).await
}

#[export]
#[allow(async_fn_in_trait)]
pub trait AsyncPointTransformer: Send + Sync {
    async fn transform_point(&self, point: Point) -> Point;
}

#[export]
pub async fn transform_point_with_async_callback(
    transformer: impl AsyncPointTransformer,
    point: Point,
) -> Point {
    transformer.transform_point(point).await
}

#[export]
#[allow(async_fn_in_trait)]
pub trait AsyncOptionFetcher: Send + Sync {
    async fn find(&self, key: i32) -> Option<i64>;
}

#[export]
pub async fn invoke_async_option_fetcher(
    fetcher: impl AsyncOptionFetcher,
    key: i32,
) -> Option<i64> {
    fetcher.find(key).await
}

#[export]
#[allow(async_fn_in_trait)]
pub trait AsyncOptionalMessageFetcher: Send + Sync {
    async fn find_message(&self, key: i32) -> Option<String>;
}

#[export]
pub async fn invoke_async_optional_message_fetcher(
    fetcher: impl AsyncOptionalMessageFetcher,
    key: i32,
) -> Option<String> {
    fetcher.find_message(key).await
}

#[export]
#[allow(async_fn_in_trait)]
pub trait AsyncResultFormatter: Send + Sync {
    async fn render_message(&self, scope: &str, message: &str) -> Result<String, MathError>;
    async fn transform_point(&self, point: Point, status: Status) -> Result<Point, MathError>;
}

#[export]
pub async fn render_message_with_async_result_callback(
    formatter: impl AsyncResultFormatter,
    scope: String,
    message: String,
) -> Result<String, MathError> {
    formatter.render_message(&scope, &message).await
}

#[export]
pub async fn transform_point_with_async_result_callback(
    formatter: impl AsyncResultFormatter,
    point: Point,
    status: Status,
) -> Result<Point, MathError> {
    formatter.transform_point(point, status).await
}
