use std::str;

const MAX_DEPTH: usize = 64;

#[derive(Debug, Clone, PartialEq)]
pub struct Num {
    pub f: f64,
    pub i: Option<i64>,
}

#[derive(Debug, Clone, PartialEq)]
pub enum Json {
    Null,
    Bool(bool),
    Num(Num),
    Str(String),
    Arr(Vec<Json>),
    Obj(Vec<(String, Json)>),
}

impl Json {
    pub fn as_arr(&self) -> Option<&[Json]> {
        match self {
            Json::Arr(a) => Some(a.as_slice()),
            _ => None,
        }
    }

    pub fn as_obj(&self) -> Option<&[(String, Json)]> {
        match self {
            Json::Obj(o) => Some(o.as_slice()),
            _ => None,
        }
    }
}

pub fn parse(s: &str) -> Option<Json> {
    let mut p = Parser { b: s.as_bytes(), p: 0, depth: 0 };
    let v = p.value()?;
    p.ws();
    if p.p == p.b.len() {
        Some(v)
    } else {
        None
    }
}

struct Parser<'a> {
    b: &'a [u8],
    p: usize,
    depth: usize,
}

impl<'a> Parser<'a> {
    fn ws(&mut self) {
        while self.p < self.b.len() && matches!(self.b[self.p], b' ' | b'\t' | b'\n' | b'\r') {
            self.p += 1;
        }
    }

    fn peek(&self) -> Option<u8> {
        self.b.get(self.p).copied()
    }

    fn lit(&mut self, word: &[u8]) -> bool {
        if self.b[self.p..].starts_with(word) {
            self.p += word.len();
            true
        } else {
            false
        }
    }

    fn value(&mut self) -> Option<Json> {
        self.ws();
        match self.peek()? {
            b'{' => self.object(),
            b'[' => self.array(),
            b'"' => self.string().map(Json::Str),
            b't' => {
                if self.lit(b"true") {
                    Some(Json::Bool(true))
                } else {
                    None
                }
            }
            b'f' => {
                if self.lit(b"false") {
                    Some(Json::Bool(false))
                } else {
                    None
                }
            }
            b'n' => {
                if self.lit(b"null") {
                    Some(Json::Null)
                } else {
                    None
                }
            }
            b'-' | b'0'..=b'9' => self.number(),
            _ => None,
        }
    }

    fn array(&mut self) -> Option<Json> {
        self.depth += 1;
        if self.depth > MAX_DEPTH {
            return None;
        }
        self.p += 1;
        let mut items = Vec::new();
        self.ws();
        if self.peek()? == b']' {
            self.p += 1;
            self.depth -= 1;
            return Some(Json::Arr(items));
        }
        loop {
            items.push(self.value()?);
            self.ws();
            match self.peek()? {
                b',' => self.p += 1,
                b']' => {
                    self.p += 1;
                    break;
                }
                _ => return None,
            }
        }
        self.depth -= 1;
        Some(Json::Arr(items))
    }

    fn object(&mut self) -> Option<Json> {
        self.depth += 1;
        if self.depth > MAX_DEPTH {
            return None;
        }
        self.p += 1;
        let mut items = Vec::new();
        self.ws();
        if self.peek()? == b'}' {
            self.p += 1;
            self.depth -= 1;
            return Some(Json::Obj(items));
        }
        loop {
            self.ws();
            if self.peek()? != b'"' {
                return None;
            }
            let key = self.string()?;
            self.ws();
            if self.peek()? != b':' {
                return None;
            }
            self.p += 1;
            let v = self.value()?;
            items.push((key, v));
            self.ws();
            match self.peek()? {
                b',' => self.p += 1,
                b'}' => {
                    self.p += 1;
                    break;
                }
                _ => return None,
            }
        }
        self.depth -= 1;
        Some(Json::Obj(items))
    }

    fn hex4(&mut self) -> Option<u32> {
        let s = self.b.get(self.p..self.p + 4)?;
        if !s.iter().all(|b| b.is_ascii_hexdigit()) {
            return None;
        }
        let v = u32::from_str_radix(str::from_utf8(s).ok()?, 16).ok()?;
        self.p += 4;
        Some(v)
    }

    fn string(&mut self) -> Option<String> {
        self.p += 1;
        let mut buf: Vec<u8> = Vec::new();
        loop {
            let c = *self.b.get(self.p)?;
            self.p += 1;
            match c {
                b'"' => break,
                b'\\' => {
                    let e = *self.b.get(self.p)?;
                    self.p += 1;
                    match e {
                        b'"' => buf.push(b'"'),
                        b'\\' => buf.push(b'\\'),
                        b'/' => buf.push(b'/'),
                        b'b' => buf.push(8),
                        b'f' => buf.push(12),
                        b'n' => buf.push(b'\n'),
                        b'r' => buf.push(b'\r'),
                        b't' => buf.push(b'\t'),
                        b'u' => {
                            let hi = self.hex4()?;
                            let cp = if (0xD800..0xDC00).contains(&hi) {
                                if self.b.get(self.p) != Some(&b'\\') || self.b.get(self.p + 1) != Some(&b'u') {
                                    return None;
                                }
                                self.p += 2;
                                let lo = self.hex4()?;
                                if !(0xDC00..0xE000).contains(&lo) {
                                    return None;
                                }
                                0x10000 + ((hi - 0xD800) << 10) + (lo - 0xDC00)
                            } else {
                                hi
                            };
                            let ch = char::from_u32(cp)?;
                            let mut tmp = [0u8; 4];
                            buf.extend_from_slice(ch.encode_utf8(&mut tmp).as_bytes());
                        }
                        _ => return None,
                    }
                }
                _ => buf.push(c),
            }
        }
        String::from_utf8(buf).ok()
    }

    fn number(&mut self) -> Option<Json> {
        let start = self.p;
        while let Some(c) = self.peek() {
            if matches!(c, b'0'..=b'9' | b'+' | b'-' | b'.' | b'e' | b'E') {
                self.p += 1;
            } else {
                break;
            }
        }
        let t = str::from_utf8(&self.b[start..self.p]).ok()?;
        let f: f64 = t.parse().ok()?;
        if !f.is_finite() {
            return None;
        }
        Some(Json::Num(Num { f, i: t.parse::<i64>().ok() }))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn s(v: &Json) -> &str {
        match v {
            Json::Str(x) => x.as_str(),
            _ => panic!("bukan string"),
        }
    }

    #[test]
    fn scalars() {
        assert_eq!(parse("null"), Some(Json::Null));
        assert_eq!(parse(" true "), Some(Json::Bool(true)));
        assert_eq!(parse("false"), Some(Json::Bool(false)));
        assert_eq!(parse("12"), Some(Json::Num(Num { f: 12.0, i: Some(12) })));
        assert_eq!(parse("-1.5"), Some(Json::Num(Num { f: -1.5, i: None })));
        assert_eq!(parse("1e2"), Some(Json::Num(Num { f: 100.0, i: None })));
    }

    #[test]
    fn large_integer_keeps_exact() {
        match parse("9007199254740993") {
            Some(Json::Num(n)) => assert_eq!(n.i, Some(9007199254740993)),
            _ => panic!(),
        }
    }

    #[test]
    fn strings_and_escapes() {
        assert_eq!(s(&parse("\"a\\nb\\t\\\"\\\\\\/\"").unwrap()), "a\nb\t\"\\/");
        assert_eq!(s(&parse("\"\\u0041\\u00e9\"").unwrap()), "Aé");
        assert_eq!(s(&parse("\"\\ud83d\\ude00\"").unwrap()), "😀");
        assert_eq!(s(&parse("\"Jalan Merdeka é 😀\"").unwrap()), "Jalan Merdeka é 😀");
    }

    #[test]
    fn bad_strings() {
        assert_eq!(parse("\"abc"), None);
        assert_eq!(parse("\"\\ud83d\""), None);
        assert_eq!(parse("\"\\ude00\""), None);
        assert_eq!(parse("\"\\u12\""), None);
        assert_eq!(parse("\"\\u+123\""), None);
        assert_eq!(parse("\"\\x\""), None);
    }

    #[test]
    fn containers() {
        let v = parse("{\"a\":[1,2,{\"b\":null}],\"c\":\"d\"}").unwrap();
        let o = v.as_obj().unwrap();
        assert_eq!(o.len(), 2);
        assert_eq!(o[0].0, "a");
        assert_eq!(o[0].1.as_arr().unwrap().len(), 3);
        assert_eq!(parse("[]"), Some(Json::Arr(vec![])));
        assert_eq!(parse("{}"), Some(Json::Obj(vec![])));
    }

    #[test]
    fn rejects_malformed() {
        for t in [
            "", "garbage", "[", "[1,", "[1,]", "[1 2]", "{\"a\"}", "{\"a\":}", "{a:1}", "{\"a\":1,}", "[1]x", "-", "1-2", "tru", "nul",
        ] {
            assert_eq!(parse(t), None, "{}", t);
        }
    }

    #[test]
    fn depth_limit() {
        let deep = "[".repeat(200) + &"]".repeat(200);
        assert_eq!(parse(&deep), None);
        let ok = "[".repeat(60) + &"]".repeat(60);
        assert!(parse(&ok).is_some());
    }
}
