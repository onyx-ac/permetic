package ac.onyx.permetic.capability

/**
 * Hand-maintained mirror of the method names declared per capability in
 * `permetic-web/src/index.d.ts`. This is the Kotlin side of the contract-freeze
 * parity check: [ac.onyx.permetic.capability.ContractParityTest] (JVM) and
 * `contract-parity.test.ts` (TS) both assert their side against the single shared
 * `packages/permetic-web/contract/manifest.json`, so drift on either side without
 * updating the manifest fails that side's test.
 */
public object Contract {
    public val AUTH: List<String> =
        listOf(
            "getToken",
            "refresh",
            "signOut",
            "currentAccount",
            "onAccountChange",
        )

    public val PUSH: List<String> =
        listOf(
            "requestPermission",
            "permissionState",
            "getToken",
            "onToken",
            "onMessage",
            "initialMessage",
        )

    public val BILLING: List<String> =
        listOf(
            "queryProducts",
            "purchase",
            "queryPurchases",
            "acknowledge",
            "consume",
            "onPurchaseUpdate",
        )

    public val BACKGROUND: List<String> =
        listOf(
            "schedule",
            "cancel",
            "status",
            "onStatusChange",
        )

    public val STORAGE: List<String> =
        listOf(
            "info", "getDoc", "getRevTrees", "bulkWrite", "allDocs", "changes", "subscribeChanges",
            "revsDiff", "bulkGet", "compact", "getLocal", "putLocal", "removeLocal",
            "getAttachment", "putAttachment", "destroy", "close",
        )

    public val SYSTEM: List<String> =
        listOf(
            "info",
            "log",
            "share",
            "openUrl",
            "onLifecycle",
        )

    public val byCapability: Map<CapabilityName, List<String>> =
        mapOf(
            CapabilityName.AUTH to AUTH,
            CapabilityName.PUSH to PUSH,
            CapabilityName.BILLING to BILLING,
            CapabilityName.BACKGROUND to BACKGROUND,
            CapabilityName.STORAGE to STORAGE,
            CapabilityName.SYSTEM to SYSTEM,
        )
}
