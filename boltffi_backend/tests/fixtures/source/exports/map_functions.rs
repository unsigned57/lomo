use std::collections::{BTreeMap, HashMap};

#[export]
pub fn keep_scores(scores: HashMap<String, i32>) -> HashMap<String, i32> {
    scores
}

#[export]
pub fn keep_score_history(history: BTreeMap<String, Vec<i32>>) -> BTreeMap<String, Vec<i32>> {
    history
}
