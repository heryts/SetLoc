const FIXUP: &[u8] = b"_ZN3art11ClassLinker22FixupStaticTrampolines";
const GC: &[u8] = b"_ZN3art3jit12JitCodeCache19GarbageCollectCacheEPNS_6ThreadE";
const GC_ALT: &[u8] = b"_ZN3art3jit12JitCodeCache12DoCollectionEPNS_6ThreadE\0";

fn contains(hay: &[u8], needle: &[u8]) -> bool {
    needle.is_empty() || hay.windows(needle.len()).any(|w| w == needle)
}

pub fn alt_symbol(name: &[u8]) -> Option<&'static [u8]> {
    if name == GC {
        Some(GC_ALT)
    } else {
        None
    }
}

pub fn stub_index(name: &[u8]) -> Option<usize> {
    if !name.starts_with(FIXUP) {
        return None;
    }
    if contains(name, b"EPNS_6ThreadE") {
        Some(0)
    } else if contains(name, b"EPNS_6mirror5ClassE") {
        Some(2)
    } else {
        Some(1)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn alt_only_for_gc() {
        assert_eq!(
            alt_symbol(b"_ZN3art3jit12JitCodeCache19GarbageCollectCacheEPNS_6ThreadE"),
            Some(&b"_ZN3art3jit12JitCodeCache12DoCollectionEPNS_6ThreadE\0"[..])
        );
        assert_eq!(alt_symbol(b"_ZN3art3jit12JitCodeCache19GarbageCollectCacheE"), None);
        assert_eq!(alt_symbol(b""), None);
    }

    #[test]
    fn alt_is_nul_terminated() {
        assert_eq!(GC_ALT.last(), Some(&0u8));
        assert_eq!(GC_ALT.iter().filter(|b| **b == 0).count(), 1);
    }

    #[test]
    fn fixup_thread_variant() {
        assert_eq!(
            stub_index(b"_ZN3art11ClassLinker22FixupStaticTrampolinesEPNS_6ThreadE"),
            Some(0)
        );
    }

    #[test]
    fn fixup_class_variant() {
        assert_eq!(
            stub_index(b"_ZN3art11ClassLinker22FixupStaticTrampolinesEPNS_6mirror5ClassE"),
            Some(2)
        );
    }

    #[test]
    fn fixup_other_variant() {
        assert_eq!(stub_index(b"_ZN3art11ClassLinker22FixupStaticTrampolines"), Some(1));
        assert_eq!(stub_index(b"_ZN3art11ClassLinker22FixupStaticTrampolinesEv"), Some(1));
    }

    #[test]
    fn thread_wins_over_class() {
        assert_eq!(
            stub_index(b"_ZN3art11ClassLinker22FixupStaticTrampolinesEPNS_6ThreadEPNS_6mirror5ClassE"),
            Some(0)
        );
    }

    #[test]
    fn non_fixup_has_no_stub() {
        assert_eq!(stub_index(b"_ZN3art11ClassLinker22FixupStaticTrampolin"), None);
        assert_eq!(stub_index(b"_ZN3art6Thread4SelfEv"), None);
        assert_eq!(stub_index(b"x_ZN3art11ClassLinker22FixupStaticTrampolinesEPNS_6ThreadE"), None);
        assert_eq!(stub_index(b""), None);
    }
}
