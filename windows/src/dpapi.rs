//! Windows DPAPI(CurrentUser)加密存储邮箱密码。

use base64::Engine as _;
use windows::core::w;
use windows::Win32::Foundation::{LocalFree, HLOCAL};
use windows::Win32::Security::Cryptography::{
    CryptProtectData, CryptUnprotectData, CRYPTPROTECT_UI_FORBIDDEN, CRYPT_INTEGER_BLOB,
};

pub fn protect(plain: &str) -> anyhow::Result<String> {
    unsafe {
        let input = CRYPT_INTEGER_BLOB {
            cbData: plain.len() as u32,
            pbData: plain.as_ptr() as *mut u8,
        };
        let mut out = CRYPT_INTEGER_BLOB::default();
        CryptProtectData(
            &input,
            w!("w-mail"),
            None,
            None,
            None,
            CRYPTPROTECT_UI_FORBIDDEN,
            &mut out,
        )?;
        let bytes = std::slice::from_raw_parts(out.pbData, out.cbData as usize).to_vec();
        let _ = LocalFree(Some(HLOCAL(out.pbData as *mut _)));
        Ok(base64::engine::general_purpose::STANDARD.encode(bytes))
    }
}

pub fn unprotect(protected_base64: &str) -> anyhow::Result<String> {
    use base64::engine::general_purpose::STANDARD;
    let bytes = STANDARD.decode(protected_base64)?;
    unsafe {
        let input = CRYPT_INTEGER_BLOB {
            cbData: bytes.len() as u32,
            pbData: bytes.as_ptr() as *mut u8,
        };
        let mut out = CRYPT_INTEGER_BLOB::default();
        CryptUnprotectData(
            &input,
            None,
            None,
            None,
            None,
            CRYPTPROTECT_UI_FORBIDDEN,
            &mut out,
        )?;
        let s = String::from_utf8_lossy(std::slice::from_raw_parts(out.pbData, out.cbData as usize))
            .into_owned();
        let _ = LocalFree(Some(HLOCAL(out.pbData as *mut _)));
        Ok(s)
    }
}
