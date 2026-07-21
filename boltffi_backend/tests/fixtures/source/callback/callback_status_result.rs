#[export]
pub trait StatusMapper {
    fn map_status(&self, status: i32) -> Result<i32, String>;
}
