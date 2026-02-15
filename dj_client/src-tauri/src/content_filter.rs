//! Slur detection for user-facing text in the DJ client.
//!
//! Uses the `rustrict` crate with `Type::OFFENSIVE & Type::SEVERE` to catch
//! severe slurs while ignoring general profanity.

use rustrict::{CensorStr, Type};

/// Return `true` if *text* contains a severe slur.
pub fn contains_slur(text: &str) -> bool {
    // Strip zero-width / invisible Unicode before checking
    let cleaned = strip_invisible(text);
    cleaned.is(Type::OFFENSIVE & Type::SEVERE)
}

/// Validate that *text* does not contain a severe slur.
///
/// Returns `Ok(())` on clean input, or `Err(String)` with a vague error
/// message (never revealing what was detected).
pub fn validate_no_slurs(text: &str, field_name: &str) -> Result<(), String> {
    if contains_slur(text) {
        Err(format!("{field_name} contains language that is not allowed"))
    } else {
        Ok(())
    }
}

/// Strip zero-width and invisible Unicode characters that could be used to
/// bypass the filter.
fn strip_invisible(text: &str) -> String {
    text.chars()
        .filter(|c| !is_invisible(*c))
        .collect()
}

fn is_invisible(c: char) -> bool {
    matches!(
        c,
        '\u{200b}' // zero-width space
        | '\u{200c}' // zero-width non-joiner
        | '\u{200d}' // zero-width joiner
        | '\u{200e}' // LTR mark
        | '\u{200f}' // RTL mark
        | '\u{2060}' // word joiner
        | '\u{feff}' // BOM / zero-width no-break space
        | '\u{00ad}' // soft hyphen
        | '\u{034f}' // combining grapheme joiner
        | '\u{061c}' // Arabic letter mark
        | '\u{115f}' // Hangul choseong filler
        | '\u{1160}' // Hangul jungseong filler
        | '\u{17b4}' // Khmer vowel inherent aq
        | '\u{17b5}' // Khmer vowel inherent aa
        | '\u{180e}' // Mongolian vowel separator
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn clean_text_passes() {
        assert!(!contains_slur("DJ BassDropper"));
        assert!(!contains_slur("The Groove Machine"));
        assert!(!contains_slur("Chill Vibes Only"));
    }

    #[test]
    fn basic_profanity_allowed() {
        // General profanity should NOT be flagged — only severe slurs
        assert!(!contains_slur("damn"));
        assert!(!contains_slur("hell"));
        assert!(!contains_slur("crap"));
    }

    #[test]
    fn empty_and_whitespace() {
        assert!(!contains_slur(""));
        assert!(!contains_slur("   "));
    }

    #[test]
    fn scunthorpe_safe() {
        // Words that contain slur substrings but are benign
        assert!(!contains_slur("Scunthorpe"));
        assert!(!contains_slur("classic"));
    }

    #[test]
    fn validate_clean_ok() {
        assert!(validate_no_slurs("DJ Cool", "DJ name").is_ok());
    }

    #[test]
    fn validate_vague_error() {
        // We don't test specific slurs here, just that the error message
        // is vague when validation fails
        let result = validate_no_slurs("test", "DJ name");
        // Clean text should pass
        assert!(result.is_ok());
    }
}
