/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.billing

import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetailsResult
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.queryProductDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.signal.core.util.logging.Log

/**
 * BillingApi serves as the core location for interacting with the Google Billing API. Use of this API is required
 * for remote backups paid tier, and will only be available in play store builds.
 *
 * Care should be taken here to ensure only one instance of this exists at a time.
 */
class BillingApi private constructor(
  context: Context
) {
  companion object {
    private val TAG = Log.tag(BillingApi::class)

    private var instance: BillingApi? = null

    @Synchronized
    fun getOrCreate(context: Context): BillingApi {
      return instance ?: BillingApi(context).let {
        instance = it
        it
      }
    }
  }

  private val connectionState = MutableStateFlow<State>(State.Init)
  private val coroutineScope = CoroutineScope(Dispatchers.Default)

  private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
    Log.d(TAG, "purchasesUpdatedListener: ${billingResult.responseCode}")
    Log.d(TAG, "purchasesUpdatedListener: Detected ${purchases?.size ?: 0} purchases.")
  }

  private val billingClient: BillingClient = BillingClient.newBuilder(context)
    .setListener(purchasesUpdatedListener)
    .enablePendingPurchases(
      PendingPurchasesParams.newBuilder()
        .enableOneTimeProducts()
        .build()
    )
    .build()

  init {
    coroutineScope.launch {
      createConnectionFlow()
        .retry { it is RetryException } // TODO [message-backups] - consider a delay here
        .collect { newState ->
          Log.d(TAG, "Updating Google Play Billing connection state: $newState")
          connectionState.update {
            newState
          }
        }
    }
  }

  suspend fun queryProducts(): ProductDetailsResult {
    val productList = listOf(
      QueryProductDetailsParams.Product.newBuilder()
        .setProductId("") // TODO [message-backups] where does the product id come from?
        .setProductType(BillingClient.ProductType.SUBS)
        .build()
    )

    val params = QueryProductDetailsParams.newBuilder()
      .setProductList(productList)
      .build()

    return withContext(Dispatchers.IO) {
      doOnConnectionReady {
        billingClient.queryProductDetails(params)
      }
    }
  }

  /**
   * Returns whether or not subscriptions are supported by a user's device. Lack of subscription support is generally due
   * to out-of-date Google Play API
   */
  fun areSubscriptionsSupported(): Boolean {
    return billingClient.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS).responseCode == BillingResponseCode.OK
  }

  private suspend fun <T> doOnConnectionReady(block: suspend () -> T): T {
    val state = connectionState
      .filter { it == State.Connected || it is State.Failure }
      .first()

    return when (state) {
      State.Connected -> block()
      is State.Failure -> throw state.billingError
      else -> error("Unexpected state: $state")
    }
  }

  private fun createConnectionFlow(): Flow<State> {
    return callbackFlow {
      Log.d(TAG, "Starting Google Play Billing connection...", true)
      trySend(State.Connecting)

      billingClient.startConnection(object : BillingClientStateListener {
        override fun onBillingServiceDisconnected() {
          Log.d(TAG, "Google Play Billing became disconnected.", true)
          trySend(State.Disconnected)
          cancel(CancellationException("Google Play Billing became disconnected.", RetryException()))
        }

        override fun onBillingSetupFinished(billingResult: BillingResult) {
          Log.d(TAG, "onBillingSetupFinished: ${billingResult.responseCode}")
          if (billingResult.responseCode == BillingResponseCode.OK) {
            Log.d(TAG, "Google Play Billing is ready.", true)
            trySend(State.Connected)
          } else {
            Log.d(TAG, "Google Play Billing failed to connect.", true)
            val billingError = BillingError(
              billingResponseCode = billingResult.responseCode
            )
            trySend(State.Failure(billingError))
            cancel(CancellationException("Failed to connect to Google Play Billing", billingError))
          }
        }
      })

      awaitClose {
        billingClient.endConnection()
      }
    }
  }

  private sealed interface State {
    data object Init : State
    data object Connecting : State
    data object Connected : State
    data object Disconnected : State
    data class Failure(val billingError: BillingError) : State
  }

  private class RetryException : Exception()
}
