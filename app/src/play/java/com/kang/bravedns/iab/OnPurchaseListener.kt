package com.kang.bravedns.iab

interface OnPurchaseListener {

    fun onPurchaseResult(isPurchaseSuccess: Boolean, message: String)
}
