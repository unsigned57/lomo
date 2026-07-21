#[data]
pub struct Response {
    pub result: Result<i32, String>,
}

#[export]
pub fn result_to_string(value: Result<i32, String>) -> String {
    match value {
        Ok(value) => format!("ok:{value}"),
        Err(error) => format!("err:{error}"),
    }
}

#[export]
pub fn echo_response(response: Response) -> Response {
    response
}
