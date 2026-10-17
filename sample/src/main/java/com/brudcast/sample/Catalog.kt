package com.brudcast.sample

/** The products the sample's shop sells, so there is something to view and check out. */
object Catalog {
    /**
     * A product.
     *
     * @property id The id events carry as `product`.
     * @property name The display name.
     * @property description One line about it.
     * @property price The price in US dollars.
     */
    class Product(val id: String, val name: String, val description: String, val price: Int) {
        val priceLabel: String get() = "$$price.00"
    }

    val products: List<Product> = listOf(
        Product("sneakers", "Canvas sneakers", "Lightweight trainers for every day", 79),
        Product("backpack", "Leather backpack", "Fits a 16-inch laptop", 129),
        Product("beanie", "Wool beanie", "Merino wool, one size", 24),
    )

    /** The product a `brudcast-sample://checkout` link checks out. */
    val featured: Product = products.first()

    /**
     * Finds a product.
     *
     * @param id The product's id.
     * @return The product, or the featured one when the id is unknown.
     */
    fun product(id: String?): Product = products.firstOrNull { it.id == id } ?: featured
}
