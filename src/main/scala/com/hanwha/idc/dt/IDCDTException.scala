package com.hanwha.idc.dt

class IDCDTException(message: String, cause: Throwable)
  extends Exception(message, cause) {

  def this(message: String) = this(message, null)
}

private[idc] class IDCDTConfigException(cause: Throwable)
  extends IDCDTException("CPU data processing error", cause)
