//! Search ranking and presentation share source-addressed evidence.
use std::{collections::BTreeMap, ops::Range};

use lomo_core::LomoError;
use lomo_store::{QueryTerm, Tokenizer, UnicodeTokenizer};
use pinyin::ToPinyinMulti;

use crate::error::validation;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum MatchSource {
    Body,
    Path,
    Pinyin,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SearchExcerpt {
    pub text: String,
    pub highlights: Vec<Range<usize>>,
    pub source: MatchSource,
    pub body_start: Option<usize>,
}

#[derive(Clone, Debug)]
struct Evidence {
    score: i64,
    ranges: Vec<Range<usize>>,
}

/// # Errors
/// Query validation or a projection hit that cannot be located in its source snapshot.
pub fn fulltext_excerpt(path: &str, body: &str, query: &str) -> Result<SearchExcerpt, LomoError> {
    let plan = UnicodeTokenizer.query_plan(query)?;
    let terms: Vec<_> = plan
        .terms
        .into_iter()
        .flat_map(|term| match term {
            QueryTerm::Word { token }
            | QueryTerm::Emoji { token }
            | QueryTerm::CjkUnigram { token } => vec![token],
            QueryTerm::CjkAdjacentBigrams { bigrams } => bigrams,
        })
        .collect();
    if terms.is_empty() {
        return make_excerpt(body, &[], MatchSource::Body);
    }
    let ranges = literal_ranges(body, &terms);
    if !ranges.is_empty() {
        return make_excerpt(body, &ranges, MatchSource::Body);
    }
    let ranges = literal_ranges(path, &terms);
    if !ranges.is_empty() {
        return make_excerpt(path, &ranges, MatchSource::Path);
    }
    Err(validation(
        "search_evidence_missing",
        "indexed match no longer has a matching source snapshot",
    ))
}

/// # Errors
/// Invalid UTF-8 evidence boundaries (never silently converted to an empty excerpt).
pub fn fuzzy_excerpt(
    path: &str,
    body: &str,
    query: &str,
) -> Result<Option<(i64, SearchExcerpt)>, LomoError> {
    if query.trim().is_empty() {
        return Ok(Some((0, make_excerpt(body, &[], MatchSource::Body)?)));
    }
    let needle: Vec<_> = query.trim().chars().flat_map(char::to_lowercase).collect();
    let body_evidence = direct_evidence(body, &needle);
    if let Some(evidence) = &body_evidence
        && evidence.score == 1000
    {
        return Ok(Some((
            evidence.score,
            make_excerpt(body, &evidence.ranges, MatchSource::Body)?,
        )));
    }
    let candidates = [
        body_evidence.map(|evidence| (evidence, MatchSource::Body, body)),
        direct_evidence(path, &needle).map(|evidence| (evidence, MatchSource::Path, path)),
        pinyin_evidence(body, &needle).map(|evidence| (evidence, MatchSource::Pinyin, body)),
    ];
    let best = candidates
        .into_iter()
        .flatten()
        .max_by_key(|(evidence, source, _)| (evidence.score, *source == MatchSource::Body));
    best.map(|(evidence, source, text)| {
        Ok((
            evidence.score,
            make_excerpt(text, &evidence.ranges, source)?,
        ))
    })
    .transpose()
}

fn mapped_chars(text: &str) -> Vec<(char, Range<usize>)> {
    text.char_indices()
        .flat_map(|(byte, ch)| {
            ch.to_lowercase()
                .map(move |lower| (lower, byte..byte + ch.len_utf8()))
        })
        .collect()
}

fn literal_ranges(text: &str, terms: &[String]) -> Vec<Range<usize>> {
    let folded = mapped_chars(text);
    let mut ranges = Vec::new();
    for term in terms {
        let chars: Vec<_> = term.chars().flat_map(char::to_lowercase).collect();
        if chars.is_empty() {
            continue;
        }
        for window in folded.windows(chars.len()) {
            if window.iter().map(|(ch, _)| ch).eq(chars.iter())
                && let (Some((_, first)), Some((_, last))) = (window.first(), window.last())
            {
                ranges.push(first.start..last.end);
            }
        }
    }
    ranges.sort_by_key(|range| range.start);
    ranges.dedup();
    ranges
}

fn direct_evidence(text: &str, needle: &[char]) -> Option<Evidence> {
    let folded = mapped_chars(text);
    if let Some(window) = folded
        .windows(needle.len())
        .find(|window| window.iter().map(|(ch, _)| ch).eq(needle.iter()))
    {
        return Some(Evidence {
            score: 1000,
            ranges: window.iter().map(|(_, range)| range.clone()).collect(),
        });
    }
    let mut rest = folded.iter();
    let mut ranges = Vec::new();
    for ch in needle {
        ranges.push(rest.find(|(candidate, _)| candidate == ch)?.1.clone());
    }
    Some(Evidence {
        score: proximity(&ranges),
        ranges,
    })
}

fn proximity(ranges: &[Range<usize>]) -> i64 {
    let extent = ranges
        .first()
        .zip(ranges.last())
        .map_or(0, |(first, last)| last.end.saturating_sub(first.start));
    500_i64
        .saturating_sub(i64::try_from(extent).unwrap_or(i64::MAX))
        .max(10)
}

/// Each source character contributes exactly one pronunciation. Dynamic states avoid
/// concatenating full spellings and initials into a false match spanning two indexes.
fn pinyin_evidence(text: &str, needle: &[char]) -> Option<Evidence> {
    let full = pinyin_match(text, needle, false);
    let initials = pinyin_match(text, needle, true);
    full.into_iter()
        .chain(initials)
        .max_by_key(|evidence| evidence.score)
}

fn pinyin_match(text: &str, needle: &[char], initials: bool) -> Option<Evidence> {
    let mut states = BTreeMap::from([(0, Vec::<Range<usize>>::new())]);
    for (byte, ch) in text.char_indices() {
        let range = byte..byte + ch.len_utf8();
        let Some(pronunciations) = ch.to_pinyin_multi() else {
            continue;
        };
        let before = states.clone();
        for pronunciation in pronunciations {
            let syllable = if initials {
                pronunciation.first_letter()
            } else {
                pronunciation.plain()
            };
            let mut branch = before.clone();
            for letter in syllable.chars() {
                let current = branch.clone();
                for (matched, mut ranges) in current {
                    if needle.get(matched) != Some(&letter) {
                        continue;
                    }
                    ranges.push(range.clone());
                    retain_closest(&mut branch, matched + 1, ranges);
                }
            }
            for (matched, ranges) in branch {
                retain_closest(&mut states, matched, ranges);
            }
        }
    }
    states.remove(&needle.len()).map(|ranges| Evidence {
        score: proximity(&ranges),
        ranges,
    })
}

fn retain_closest(
    states: &mut BTreeMap<usize, Vec<Range<usize>>>,
    matched: usize,
    ranges: Vec<Range<usize>>,
) {
    if states
        .get(&matched)
        .is_none_or(|old| proximity(&ranges) > proximity(old))
    {
        states.insert(matched, ranges);
    }
}

fn make_excerpt(
    text: &str,
    ranges: &[Range<usize>],
    source: MatchSource,
) -> Result<SearchExcerpt, LomoError> {
    let first = ranges.first().map_or(0, |range| range.start);
    let positions: Vec<_> = text
        .char_indices()
        .map(|(byte, _)| byte)
        .chain(std::iter::once(text.len()))
        .collect();
    let center = positions.partition_point(|byte| *byte < first);
    let start = positions
        .get(center.saturating_sub(60))
        .copied()
        .ok_or_else(|| validation("invalid_search_evidence", "missing excerpt start"))?;
    let end = positions
        .get(center.saturating_add(260))
        .copied()
        .unwrap_or(text.len());
    let excerpt = text.get(start..end).ok_or_else(|| {
        validation(
            "invalid_search_evidence",
            "excerpt is not on UTF-8 boundaries",
        )
    })?;
    let mut highlights: Vec<_> = ranges
        .iter()
        .filter(|range| range.start >= start && range.end <= end)
        .map(|range| range.start - start..range.end - start)
        .collect();
    highlights.sort_by_key(|range| range.start);
    highlights.dedup();
    Ok(SearchExcerpt {
        text: excerpt.to_owned(),
        highlights,
        source,
        body_start: (source != MatchSource::Path).then_some(start),
    })
}
