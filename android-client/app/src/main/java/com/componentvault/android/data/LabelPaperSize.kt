package com.componentvault.android.data

/** Common canvas sizes. Only 40 × 60 mm gap stock has been verified on a physical M1. */
internal enum class LabelPaperSize(val widthMm: Float, val heightMm: Float) {
    Standard(40f, 60f),
    Medium(40f, 30f),
    Small(40f, 20f),
    Narrow(40f, 10f),
    Square(40f, 40f),
    Compact(30f, 20f),
    TallCompact(30f, 60f),
    Wide(50f, 30f),
    WideRoll(58f, 40f),
    WideSquare(60f, 60f),
}
