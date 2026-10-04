# MegaCityDonations v1.0.3

- Added configurable donation prerequisites using `requires` lists.
- Added OP/console commands `/donation adddependency ID REQUIRED_ID` and `/donation removedependency ID REQUIRED_ID`.
- Donation info now lists prerequisites; dependency commands include tab completion.
- All listed prerequisites must be assigned before a normal donation assignment.
- Rejects missing prerequisite IDs, self-dependencies, and circular dependencies.
- Added `/adddonation PLAYER DONATIONID --override` for migration of historical donors.
- The override bypasses dependencies only; OP restrictions and repeatability rules remain enforced. Successful overrides are logged.
- Activation now requires the target donation ID to be assigned to the player. Activation has no override flag.
- Existing purchases and totals remain unchanged; deactivation remains available after records are removed.
- Validation: 50 tests passed.
