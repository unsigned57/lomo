use boltffi::export;

#[export]
pub trait MessageMapper {
    fn map_message(&self, key: i32) -> Result<String, String>;
}
