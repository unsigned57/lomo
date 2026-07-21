use boltffi::__private::wire::{WireDecode, WireEncode};

mod result_wire_encoding {
    use super::*;

    #[test]
    fn ok_encoded_with_tag_zero() {
        let result: Result<i32, i32> = Ok(42);
        let mut buf = vec![0u8; result.wire_size()];
        result.encode_to(&mut buf);

        assert_eq!(buf[0], 0, "Ok variant has tag 0");
    }

    #[test]
    fn err_encoded_with_tag_one() {
        let result: Result<i32, i32> = Err(99);
        let mut buf = vec![0u8; result.wire_size()];
        result.encode_to(&mut buf);

        assert_eq!(buf[0], 1, "Err variant has tag 1");
    }

    #[test]
    fn ok_value_preserved_through_encode_decode() {
        let original: Result<i32, i32> = Ok(12345);
        let mut buf = vec![0u8; original.wire_size()];
        original.encode_to(&mut buf);

        let (decoded, _): (Result<i32, i32>, _) = Result::decode_from(&buf).unwrap();

        assert_eq!(decoded, Ok(12345));
    }

    #[test]
    fn err_value_preserved_through_encode_decode() {
        let original: Result<i32, i32> = Err(-999);
        let mut buf = vec![0u8; original.wire_size()];
        original.encode_to(&mut buf);

        let (decoded, _): (Result<i32, i32>, _) = Result::decode_from(&buf).unwrap();

        assert_eq!(decoded, Err(-999));
    }

    #[test]
    fn complex_ok_type_preserved() {
        let original: Result<String, String> = Ok("hello world".to_string());
        let mut buf = vec![0u8; original.wire_size()];
        original.encode_to(&mut buf);

        let (decoded, _): (Result<String, String>, _) = Result::decode_from(&buf).unwrap();

        assert_eq!(decoded, Ok("hello world".to_string()));
    }

    #[test]
    fn complex_err_type_preserved() {
        let original: Result<String, String> = Err("error message".to_string());
        let mut buf = vec![0u8; original.wire_size()];
        original.encode_to(&mut buf);

        let (decoded, _): (Result<String, String>, _) = Result::decode_from(&buf).unwrap();

        assert_eq!(decoded, Err("error message".to_string()));
    }
}

mod option_wire_encoding {
    use super::*;

    #[test]
    fn none_encoded_with_tag_zero() {
        let option: Option<i32> = None;
        let mut buf = vec![0u8; option.wire_size()];
        option.encode_to(&mut buf);

        assert_eq!(buf[0], 0, "None has tag 0");
    }

    #[test]
    fn some_encoded_with_tag_one() {
        let option: Option<i32> = Some(42);
        let mut buf = vec![0u8; option.wire_size()];
        option.encode_to(&mut buf);

        assert_eq!(buf[0], 1, "Some has tag 1");
    }

    #[test]
    fn none_preserved_through_encode_decode() {
        let original: Option<i32> = None;
        let mut buf = vec![0u8; original.wire_size()];
        original.encode_to(&mut buf);

        let (decoded, _): (Option<i32>, _) = Option::decode_from(&buf).unwrap();

        assert_eq!(decoded, None);
    }

    #[test]
    fn some_value_preserved_through_encode_decode() {
        let original: Option<i32> = Some(12345);
        let mut buf = vec![0u8; original.wire_size()];
        original.encode_to(&mut buf);

        let (decoded, _): (Option<i32>, _) = Option::decode_from(&buf).unwrap();

        assert_eq!(decoded, Some(12345));
    }
}

mod nested_result_option_encoding {
    use super::*;

    #[test]
    fn ok_some_preserved() {
        let original: Result<Option<i32>, String> = Ok(Some(42));
        let mut buf = vec![0u8; original.wire_size()];
        original.encode_to(&mut buf);

        let (decoded, _): (Result<Option<i32>, String>, _) = Result::decode_from(&buf).unwrap();

        assert_eq!(decoded, Ok(Some(42)));
    }

    #[test]
    fn ok_none_preserved() {
        let original: Result<Option<i32>, String> = Ok(None);
        let mut buf = vec![0u8; original.wire_size()];
        original.encode_to(&mut buf);

        let (decoded, _): (Result<Option<i32>, String>, _) = Result::decode_from(&buf).unwrap();

        assert_eq!(decoded, Ok(None));
    }

    #[test]
    fn err_preserved() {
        let original: Result<Option<i32>, String> = Err("failed".to_string());
        let mut buf = vec![0u8; original.wire_size()];
        original.encode_to(&mut buf);

        let (decoded, _): (Result<Option<i32>, String>, _) = Result::decode_from(&buf).unwrap();

        assert_eq!(decoded, Err("failed".to_string()));
    }
}

mod wire_size_calculation {
    use super::*;

    #[test]
    fn result_ok_size_is_tag_plus_value() {
        let result: Result<i32, i32> = Ok(42);
        assert_eq!(result.wire_size(), 1 + 4);
    }

    #[test]
    fn result_err_size_is_tag_plus_error() {
        let result: Result<i32, i32> = Err(42);
        assert_eq!(result.wire_size(), 1 + 4);
    }

    #[test]
    fn option_none_size_is_just_tag() {
        let option: Option<i32> = None;
        assert_eq!(option.wire_size(), 1);
    }

    #[test]
    fn option_some_size_is_tag_plus_value() {
        let option: Option<i32> = Some(42);
        assert_eq!(option.wire_size(), 1 + 4);
    }
}
