package com.brudcast.sample.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brudcast.sample.Catalog
import com.brudcast.sample.ui.DetailRow
import com.brudcast.sample.ui.ListRow
import com.brudcast.sample.ui.Notice
import com.brudcast.sample.ui.OutlineButton
import com.brudcast.sample.ui.PrimaryButton
import com.brudcast.sample.ui.Rule
import com.brudcast.sample.ui.Section
import com.brudcast.sample.ui.TrackScreen
import com.brudcast.sample.ui.tokens
import com.brudcast.sdk.Brudcast

/**
 * The products for sale.
 *
 * @param onOpen Opens a product.
 */
@Composable
fun ShopScreen(onOpen: (Catalog.Product) -> Unit) {
    TrackScreen("Shop")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 24.dp)) {
            Text("Shop", style = MaterialTheme.typography.headlineSmall)
            Text("Pick a product to see it and check out.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Rule(strong = true)
        Catalog.products.forEach { product ->
            ListRow(Icons.Outlined.ShoppingCart, product.name, product.description, onClick = { onOpen(product) }) {
                Text(product.priceLabel, style = MaterialTheme.typography.titleSmall)
            }
            Rule()
        }
    }
}

/**
 * A product page. Viewing it tracks `product_viewed`.
 *
 * @param product The product.
 * @param onBuy Continues to checkout.
 */
@Composable
fun ProductScreen(product: Catalog.Product, onBuy: () -> Unit) {
    TrackScreen("Product", mapOf("product" to product.id))
    LaunchedEffect(product.id) { Brudcast.track("product_viewed", mapOf("product" to product.id, "price" to product.price)) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 24.dp)) {
            Text(product.name, style = MaterialTheme.typography.headlineSmall)
            Text(product.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(product.priceLabel, Modifier.padding(top = 16.dp), style = MaterialTheme.typography.displaySmall)
        }
        Rule(strong = true)
        Section("Delivery and returns") {
            listOf("Free shipping", "Ships in 1 to 2 business days", "Free returns within 30 days").forEach {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Rule()
        Column(Modifier.padding(20.dp)) {
            PrimaryButton("Buy now", onClick = onBuy, modifier = Modifier.fillMaxWidth(), tag = "buyButton")
        }
    }
}

/**
 * Checkout. Appearing tracks `checkout_started`, the event the README's example campaign is
 * triggered by, and placing the order tracks `order_completed`.
 *
 * @param product The product being bought.
 * @param onDone Leaves checkout once the order is placed.
 */
@Composable
fun CheckoutScreen(product: Catalog.Product, onDone: () -> Unit) {
    val properties = mapOf("product" to product.id, "total" to product.price)
    TrackScreen("Checkout", mapOf("product" to product.id))
    LaunchedEffect(product.id) { Brudcast.track("checkout_started", properties) }
    var placed by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 24.dp)) {
            Text("Checkout", style = MaterialTheme.typography.headlineSmall)
            Text("This is a demo store. Nothing is charged or shipped.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Rule(strong = true)
        Section("Order summary") {
            DetailRow("Item", product.name)
            DetailRow("Quantity", "1")
            DetailRow("Shipping", "Free")
            DetailRow("Total", product.priceLabel)
        }
        Rule()
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (placed) {
                Notice("Order placed. order_completed was tracked.", MaterialTheme.tokens.success, MaterialTheme.tokens.successForeground)
                OutlineButton("Continue shopping", onClick = onDone, modifier = Modifier.fillMaxWidth())
            } else {
                PrimaryButton(
                    "Place order",
                    onClick = {
                        Brudcast.track("order_completed", properties)
                        placed = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    tag = "placeOrderButton",
                )
            }
        }
    }
}
