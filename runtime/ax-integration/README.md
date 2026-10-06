# Task network instance identity overlay

The overlay applies only to the pinned `google/ax` checkout
`d0bc38bcf90bb2ad9c012ff1be9d68ff05347ba9` and the deployed PoC's existing
EAP guest-template changes. `instance-identity.patch` is relative to that exact
local baseline, not a clean arbitrary upstream checkout. Existing-file baseline
hashes are recorded in the operation receipt; `overlay-sha256.json` records the
formatted, remotely tested overlay files. Do not apply it to a different base.

The wire protocol retains the official Task/Condition protobuf schema. The
controller publishes one `EAPActorIdentity` / `CreateReceiptBound` condition
containing the native CreateActor response UID and frozen template reference.
TaskStatus.id is a task identifier, never an actor UID. CreateTask discards
caller-supplied status. Only the controller's native create receipt can populate
the irreversible store journal. A durable create intent without a receipt is
unknown and must not retry CreateActor or adopt an actor by name.

New tasks use `PER_INSTANCE_V1`, a pinned runner image, a platform public key,
and a read-only actor identity directory. The task runner environment uses
`EAP_IDENTITY_DIR=/run/eap-identity`; the general agent runner's existing
`EAP_IDENTITY_DIRECTORY` contract remains separate. Native templates carry no
shared machine credential, provider key or control API service account.

The controller journal uses persistent Redis keys with no TTL, retained after
ordinary task status changes and deletion. These tombstones prevent task-name
reuse from recreating an ambiguous actor. Redis durability is inherited from
the PoC PVC; isolated store-instance restart tests are not a full Redis disaster
recovery test. Never flush or migrate these keys without a reviewed recovery
plan.

The native API remains a trusted internal PoC boundary. UID inspection around
lifecycle calls does not provide an atomic native allocation fence or production
mTLS. Unknown business intents, existing snapshots and legacy task templates
must be preserved. This overlay alone does not migrate every old credential or
complete EAP's T5/T6/T7/T8 mainline.
