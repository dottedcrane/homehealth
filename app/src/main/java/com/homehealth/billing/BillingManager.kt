package com.homerenderer.billing

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.android.billingclient.api.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages Google Play Billing for the Premium unlock that gates backup/restore.
 * that gates backup/restore. The entitlement is cached locally so Premium works offline,
 * but Play's purchase records stay the source of truth: the cache is re-synced on every
 * successful connect and on each app resume (see MainActivity.onResume), which also picks
 * up refunds, purchases made on another device, and pending payments that completed.
 */
class BillingManager(private val context: Context) {

    private lateinit var billingClient: BillingClient
    private val mainHandler = Handler(Looper.getMainLooper())
    private var reconnectDelayMs = 1_000L
    private var reconnectScheduled = false

    // KeyStore-backed prefs are corrupted on a small share of devices, and throwing here
    // (singleton init) would brick app launch entirely — fall back to plain prefs. The
    // local flag is convenience caching, not security: the next successful Play query
    // restores the entitlement, and Play's server state is what actually grants it.
    private val sharedPrefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "secure_billing_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.e("Billing", "Encrypted prefs unavailable — falling back to plain prefs.", e)
        context.getSharedPreferences("billing_prefs", Context.MODE_PRIVATE)
    }

    private val _isAdFree = MutableStateFlow(sharedPrefs.getBoolean("is_ad_free", false))
    val isAdFree: StateFlow<Boolean> = _isAdFree.asStateFlow()

    private val _formattedPrice = MutableStateFlow<String?>(null)
    /**
     * The localized, formatted price for the Premium product (e.g., "$1.99" or "1,99 €").
     */
    val formattedPrice: StateFlow<String?> = _formattedPrice.asStateFlow()

    init {
        val pendingPurchasesParams = PendingPurchasesParams.newBuilder()
            .enableOneTimeProducts()
            .build()

        billingClient = BillingClient.newBuilder(context)
            .setListener { billingResult, purchases ->
                handlePurchasesUpdated(billingResult, purchases)
            }
            .enablePendingPurchases(pendingPurchasesParams)
            .build()
        connect()
    }

    private fun connect() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.d("Billing", "Billing service connected.")
                    reconnectDelayMs = 1_000L
                    queryOwnedPurchases()
                    queryProductDetails()
                } else {
                    Log.e("Billing", "Billing setup failed: ${billingResult.debugMessage}")
                    scheduleReconnect()
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w("Billing", "Billing service disconnected — scheduling reconnect.")
                scheduleReconnect()
            }
        })
    }

    // Exponential backoff (1 s doubling to a 15-min cap) so a flaky Play service can't
    // leave the app permanently deaf to purchases until the next process restart.
    private fun scheduleReconnect() {
        if (reconnectScheduled) return
        reconnectScheduled = true
        val delay = reconnectDelayMs
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(15 * 60_000L)
        mainHandler.postDelayed({
            reconnectScheduled = false
            connectIfNeeded()
        }, delay)
    }

    private fun connectIfNeeded() {
        val state = billingClient.connectionState
        if (state == BillingClient.ConnectionState.DISCONNECTED ||
            state == BillingClient.ConnectionState.CLOSED) connect()
    }

    /**
     * Purchase-flow updates from Google Play. This path only GRANTS (and surfaces pending
     * payments) — refund revocation happens solely on [queryOwnedPurchases]'s full
     * snapshot, so a PENDING-only update can never strip an existing owner's Premium.
     */
    private fun handlePurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                if (purchases != null) {
                    grantPurchases(purchases, notifyPending = true)
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Log.d("Billing", "User canceled the purchase process.")
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                Log.d("Billing", "User already owns this item. Syncing state...")
                queryOwnedPurchases()
                Toast.makeText(context, "Restoring your purchase...", Toast.LENGTH_SHORT).show()
            }
            BillingClient.BillingResponseCode.NETWORK_ERROR,
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE -> {
                Log.e("Billing", "Network/Service error: ${billingResult.debugMessage}")
                Toast.makeText(context, "Billing service unavailable. Please check your connection.", Toast.LENGTH_LONG).show()
            }
            else -> {
                Log.e("Billing", "Billing error ${billingResult.responseCode}: ${billingResult.debugMessage}")
            }
        }
    }

    /**
     * Query Play's full owned-purchases snapshot: restores purchases (including ones made
     * on another device or completed while the app was closed), finishes interrupted
     * acknowledgments, and — being the one complete view — retracts Premium after a
     * refund. Safe to call any time; a no-op while the client is reconnecting (the
     * connect callback re-runs it).
     */
    fun queryOwnedPurchases() {
        if (!billingClient.isReady) {
            connectIfNeeded()
            return
        }
        val queryPurchasesParams = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        billingClient.queryPurchasesAsync(queryPurchasesParams) { billingResult, purchasesList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                val owned = grantPurchases(purchasesList, notifyPending = false)
                if (!owned && _isAdFree.value) {
                    Log.d("Billing", "Premium purchase no longer present. Reverting.")
                    updateAdFreeState(false)
                }
            } else {
                Log.e("Billing", "Error querying purchases: ${billingResult.debugMessage}")
            }
        }
    }

    /**
     * Grants Premium for PURCHASED copies of the product (acknowledging when needed) and
     * optionally surfaces PENDING ones. Returns whether a PURCHASED copy was seen.
     */
    private fun grantPurchases(purchases: List<Purchase>, notifyPending: Boolean): Boolean {
        var owned = false
        for (purchase in purchases) {
            if (purchase.products.contains(PRODUCT_ID)) {
                when (purchase.purchaseState) {
                    Purchase.PurchaseState.PURCHASED -> {
                        owned = true
                        updateAdFreeState(true)
                        if (!purchase.isAcknowledged) {
                            Log.d("Billing", "Found unacknowledged purchase. Acknowledging now...")
                            acknowledgePurchase(purchase)
                        }
                    }
                    // Slow payment methods (cash, some cards) park here — tell the user, and
                    // let the resume-time query flip Premium on once payment completes.
                    Purchase.PurchaseState.PENDING -> if (notifyPending) {
                        Toast.makeText(context, "Purchase pending — Premium unlocks once payment completes.", Toast.LENGTH_LONG).show()
                    }
                    else -> {}
                }
            }
        }
        return owned
    }

    /**
     * Fetches localized product details (like price) from Google Play.
     */
    fun queryProductDetails() {
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_ID)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        )

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        billingClient.queryProductDetailsAsync(params) { billingResult, result ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                val productDetails = result.productDetailsList?.firstOrNull()
                if (productDetails != null) {
                    _formattedPrice.value = productDetails.oneTimePurchaseOfferDetails?.formattedPrice
                    Log.d("Billing", "Fetched price: ${_formattedPrice.value}")
                }
            } else {
                Log.e("Billing", "Failed to query product details: ${billingResult.debugMessage}")
            }
        }
    }

    /**
     * Initiates the Premium purchase flow.
     */
    fun purchaseRemoveAds(activity: Activity) {
        if (!billingClient.isReady) {
            Toast.makeText(context, "Billing service unavailable. Please check your connection and try again.", Toast.LENGTH_LONG).show()
            connectIfNeeded()
            return
        }
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_ID)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        )

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        billingClient.queryProductDetailsAsync(params) { billingResult, result ->
            val productDetails = result.productDetailsList?.firstOrNull()
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && productDetails != null) {
                launchBillingFlow(activity, productDetails)
            } else {
                Log.e("Billing", "Failed to query product details: ${billingResult.debugMessage}")
                Toast.makeText(context, "Couldn't load the Premium product. Please try again later.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun launchBillingFlow(activity: Activity, productDetails: ProductDetails) {
        val productDetailsParamsList = listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(productDetails)
                .build()
        )

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(productDetailsParamsList)
            .build()

        val billingResult = billingClient.launchBillingFlow(activity, billingFlowParams)
        if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.e("Billing", "Error launching billing flow: ${billingResult.debugMessage}")
        }
    }

    // Non-consumables must be acknowledged within 3 days or Play auto-refunds them. If
    // this attempt fails, the purchase still reads !isAcknowledged on the next owned
    // query, which retries — no state is written here beyond what PURCHASED granted.
    private fun acknowledgePurchase(purchase: Purchase) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        billingClient.acknowledgePurchase(params) { billingResult ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                Log.d("Billing", "Purchase acknowledged successfully.")
            } else {
                Log.e("Billing", "Failed to acknowledge purchase: ${billingResult.debugMessage}")
            }
        }
    }

    private fun updateAdFreeState(adFree: Boolean) {
        sharedPrefs.edit().putBoolean("is_ad_free", adFree).apply()
        _isAdFree.value = adFree
    }

    companion object {
        private const val PRODUCT_ID = "backupandrestore"

        @Volatile
        private var instance: BillingManager? = null

        fun getInstance(context: Context): BillingManager =
            instance ?: synchronized(this) {
                instance ?: BillingManager(context.applicationContext).also { instance = it }
            }
    }
}
