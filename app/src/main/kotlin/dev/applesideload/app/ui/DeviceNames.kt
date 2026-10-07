package dev.applesideload.app.ui

/**
 * The name Apple sells an iPhone under, from the model identifier it
 * reports (ProductType), or null for one not listed here. Newer models are
 * shown by identifier until they are added.
 */
fun marketingName(productType: String): String? = NAMES[productType]

/** "iPhone 14 Pro (iPhone15,2)", or just the identifier when the name is not known. */
fun modelLabel(productType: String): String =
    marketingName(productType)?.let { "$it ($productType)" } ?: productType

private val NAMES = mapOf(
    "iPhone10,1" to "iPhone 8",
    "iPhone10,4" to "iPhone 8",
    "iPhone10,2" to "iPhone 8 Plus",
    "iPhone10,5" to "iPhone 8 Plus",
    "iPhone10,3" to "iPhone X",
    "iPhone10,6" to "iPhone X",
    "iPhone11,2" to "iPhone XS",
    "iPhone11,4" to "iPhone XS Max",
    "iPhone11,6" to "iPhone XS Max",
    "iPhone11,8" to "iPhone XR",
    "iPhone12,1" to "iPhone 11",
    "iPhone12,3" to "iPhone 11 Pro",
    "iPhone12,5" to "iPhone 11 Pro Max",
    "iPhone12,8" to "iPhone SE (2nd generation)",
    "iPhone13,1" to "iPhone 12 mini",
    "iPhone13,2" to "iPhone 12",
    "iPhone13,3" to "iPhone 12 Pro",
    "iPhone13,4" to "iPhone 12 Pro Max",
    "iPhone14,4" to "iPhone 13 mini",
    "iPhone14,5" to "iPhone 13",
    "iPhone14,2" to "iPhone 13 Pro",
    "iPhone14,3" to "iPhone 13 Pro Max",
    "iPhone14,6" to "iPhone SE (3rd generation)",
    "iPhone14,7" to "iPhone 14",
    "iPhone14,8" to "iPhone 14 Plus",
    "iPhone15,2" to "iPhone 14 Pro",
    "iPhone15,3" to "iPhone 14 Pro Max",
    "iPhone15,4" to "iPhone 15",
    "iPhone15,5" to "iPhone 15 Plus",
    "iPhone16,1" to "iPhone 15 Pro",
    "iPhone16,2" to "iPhone 15 Pro Max",
    "iPhone17,3" to "iPhone 16",
    "iPhone17,4" to "iPhone 16 Plus",
    "iPhone17,1" to "iPhone 16 Pro",
    "iPhone17,2" to "iPhone 16 Pro Max",
    "iPhone17,5" to "iPhone 16e"
)