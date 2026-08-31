package com.caniko.cenix

data class PhoneGrid(
    val name: String,
    val rows: Int,
    val cols: Int,
    val minWidthDp: Float,
    val minHeightDp: Float,
) {
    val cells: Int get() = rows * cols

    fun inBounds(cellX: Int, cellY: Int): Boolean = cellX in 0 until cols && cellY in 0 until rows

    companion object {
        // GrapheneOS Launcher3 e5fde8f4 device_profiles.xml phone grids.
        // Threshold = smallest non-Stubby display-option, or the only option.
        // Prefer established phone profiles over Stubby fallbacks where both exist.
        val PHONE = listOf(
            PhoneGrid("2_by_2", 2, 2, 200f, 200f),
            PhoneGrid("3_by_3", 3, 3, 255f, 300f),
            PhoneGrid("4_by_4", 4, 4, 296f, 491.33f),
            PhoneGrid("4_by_5", 5, 4, 367f, 838f),
            PhoneGrid("5_by_5", 5, 5, 406f, 694f),
        )
        val DEFAULT = PHONE.first { it.name == "4_by_5" }

        fun named(name: String): PhoneGrid? = PHONE.firstOrNull { it.name == name }

        fun compatible(widthDp: Float, heightDp: Float): List<PhoneGrid> =
            PHONE.filter { widthDp >= it.minWidthDp && heightDp >= it.minHeightDp }

        fun pick(widthDp: Float, heightDp: Float): PhoneGrid =
            compatible(widthDp, heightDp)
                .maxWithOrNull(compareBy<PhoneGrid> { it.cells }.thenBy { it.cols })
                ?: PHONE.first()
    }
}
