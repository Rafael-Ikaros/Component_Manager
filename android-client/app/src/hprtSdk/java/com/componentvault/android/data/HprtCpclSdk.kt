package com.componentvault.android.data

import android.content.Context
import android.graphics.Bitmap
import cpcl.PrinterHelper
import cpcl.operator.BTOperator

internal fun createHprtCpclPort(context: Context): HprtCpclPort = object : HprtCpclPort {
    override val isOpen: Boolean
        get() = PrinterHelper.isOpened() && BTOperator.getSocket()?.isConnected == true

    override fun open(address: String): Int = PrinterHelper.portOpenBT(context, address)

    override fun status(): Int = PrinterHelper.getPrinterStatus()

    override fun prepare(bitmap: Bitmap, continuous: Boolean) {
        check(PrinterHelper.printAreaSize("0", "200", "200", bitmap.height.toString(), "1") > 0)
        check(PrinterHelper.printBitmapCPCL(bitmap, 0, 0, 0, 0, 0) > 0)
        if (!continuous) check(PrinterHelper.form() > 0)
    }

    override fun submit(): Int = PrinterHelper.print()

    override fun abort() {
        BTOperator.getSocket()?.close()
    }

    override fun close() {
        PrinterHelper.portClose()
    }
}
