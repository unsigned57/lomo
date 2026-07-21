#[repr(u8)]
#[data]
pub enum Status {
    Ready = 1,
    Busy = 2,
}

#[export]
pub trait StatusMapper {
    fn map_status(&self, status: Status) -> Status;
}

#[export]
pub fn map_status(mapper: impl StatusMapper, status: Status) -> Status {
    mapper.map_status(status)
}
