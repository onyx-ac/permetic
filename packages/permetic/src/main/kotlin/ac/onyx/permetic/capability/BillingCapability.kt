package ac.onyx.permetic.capability

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public enum class ProductType {
    @SerialName("inapp")
    INAPP,

    @SerialName("subs")
    SUBS,
}

@Serializable
public enum class PurchaseState {
    @SerialName("purchased")
    PURCHASED,

    @SerialName("pending")
    PENDING,
}

/** Mirrors `Product` in `index.d.ts`. [formattedPrice] is display only, never arithmetic. */
@Serializable
public data class Product(
    val id: String,
    val type: ProductType,
    val title: String,
    val description: String,
    val formattedPrice: String,
    val priceMicros: Long,
    val currencyCode: String,
    val offerToken: String? = null,
)

/** Mirrors `Purchase` in `index.d.ts`. [purchasedAt] is epoch millis. */
@Serializable
public data class Purchase(
    val purchaseToken: String,
    val productIds: List<String>,
    val state: PurchaseState,
    val acknowledged: Boolean,
    val purchasedAt: Long,
)

/** Mirrors `BillingCapability` in `index.d.ts`. Webview only. */
public interface BillingCapability : PermeticCapability {
    public override val name: CapabilityName get() = CapabilityName.BILLING

    public suspend fun queryProducts(
        ids: List<String>,
        type: ProductType,
    ): List<Product>

    /** Launches the Play purchase sheet. Rejects CANCELLED if dismissed. */
    public suspend fun purchase(
        productId: String,
        offerToken: String? = null,
    ): Purchase

    /** Entitlements owned now. Verify server-side before granting anything. */
    public suspend fun queryPurchases(): List<Purchase>

    public suspend fun acknowledge(purchaseToken: String)

    public suspend fun consume(purchaseToken: String)

    public fun onPurchaseUpdate(): Flow<List<Purchase>>
}
