Behavior Contract:
Capability: image and voice directory selections commit after verified indexing; owner: Android storage lifecycle; priority: P0.

Scenarios:
- Given a mounted notes workspace, when image, voice and image directories are selected in sequence, then each committed selection appears and survives process restart.
- Given the selected directories, when new media is attached, then it renders through the selected provider location.

Observable outcomes: committed directory labels, restored labels, rendered attachments and successful navigation.
TDD proof: `target/lomo/reports/manual/directory-selection/media-directory-commit-wait-red.junit.xml` records the 5-second label failure while the captured UI still shows “Switching workspace…”. The GREEN command is `just uitest media_directory_switch --skip-build --no-install`; its executed-flow report is `target/lomo/reports/android-uitest/android-uitest.v1.json`.

Test Change Justification:
Reason category: directory commit contract changed.
Old behavior/assertion being replaced: a selected directory label had to appear within five seconds, including before candidate indexing finished.
Why old assertion is no longer correct: the label now identifies a verified, committed binding; an existing workspace may need a complete reconciliation before commit.
Coverage preserved by: every directory identity, restart and attachment assertion is retained, with a bounded 30-second wait for each actual commit.
Why this is not fitting the test to the implementation: publishing the selection before indexing succeeds is precisely the failure mode being removed. This interaction test does not establish a physical-device latency target.

Excludes: physical-device performance, provider outages and release-build timing.
