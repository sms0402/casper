package com.test.idc.ts

import java.time._

//import org.scalatest.{FunSuite, ShouldMatchers} 
import org.scalatest.FunSuite
import org.scalatest.Matchers

class DateTimeIndexSuite extends FunSuite with Matchers {
  
  val UTC = ZoneId.of("Z")
  
  test("UniformDateTimeIndex test") {
    val uniformIndex = "smsjjang"
    val uniformStr = "sonjjang"
//    val uniformIndex = uniform(
//      ZonedDateTime.of(1990, 4, 10, 0, 0, 0, 0, UTC),
//      5,
//      new BusinessDayFrequency(2))
//    val uniformStr = uniformIndex.toString
    uniformStr should be (uniformIndex)
  }
  
}