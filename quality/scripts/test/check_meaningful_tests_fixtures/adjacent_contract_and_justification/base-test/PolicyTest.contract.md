Behavior Contract:
Capability: preserve the policy token during test maintenance.
Scenarios: Given a policy, when its token is read, then the documented value is returned.
Observable outcomes: returned token and checker exit status.
TDD proof: the targeted token assertion failed before the policy fix and passed afterwards.
Excludes: platform integration.
