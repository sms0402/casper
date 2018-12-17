package com.test.dt.models

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.types.DoubleType
import org.apache.spark.sql.types.DateType
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.types.StringType
import org.apache.spark.sql.types.StructField
import org.apache.spark.sql.Row
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.SparkSession
import org.apache.spark.streaming.dstream.DStream
import org.apache.spark.streaming.Time
import org.apache.spark.SparkConf
import org.apache.spark.sql.functions._
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.elasticsearch.spark.sql._

import breeze.linalg.DenseVector

import java.text.SimpleDateFormat
import java.sql.Date
import java.util.Calendar
import scala.math.sqrt
import breeze.util.Encoder

class EWMAProcessor(sparkSession: SparkSession, rangeSize: Integer, weightStr: String) extends Serializable {
  private val schemaNames =
    "create_date,cpu_total_pct,cpu_user_pct," +
      "memory_actual_used_pct,memory_swap_total," +
      "diskio_read_time,diskio_write_time,filesystem_used_pct"

  private val weightValue = {
    val weightData = weightStr.split(",")
    weightData.map(f => f.toDouble)
  }

  private val dtDateSchema = {
    val fields = Array(StructField("create_date", StringType, nullable = true))
    StructType(fields)
  }

  private val dtDataSchema = {
    val fields = schemaNames.split(",")
      .map(fieldName => {
        val field = fieldName.trim() match {
          case "@timestamp" => StructField(fieldName.trim(), StringType, nullable = true)
          case "create_date" => StructField(fieldName.trim(), StringType, nullable = true)
          case _ => StructField(fieldName.trim(), DoubleType, nullable = true)
        }
        field
      })

    StructType(fields)
  }

  val bWeight = sparkSession.sparkContext.broadcast(weightValue)
  val bDTDataSchema = sparkSession.sparkContext.broadcast(dtDataSchema)

  def runEWMA(dataSet: DStream[(String, String, Long)], dataRange: Long): Unit = {

    val dtDataRange = sparkSession.sparkContext.broadcast(dataRange)

    val orderedDataSet = dataSet.transform(data => {
      data.sortBy(_._3, false, 4).zipWithIndex().filter(_._2 < dtDataRange.value)
    })

    val calcForDataSetRDD = orderedDataSet.map(value => {
      val columns = value._1._2.split(",").map(value => value.toDouble)
      (columns, value._1._1)
    })

    calcForDataSetRDD.foreachRDD((data: RDD[(Array[Double], String)], time: Time) => {
      val spark = SparkSessionSingleton.getInstance(data.sparkContext.getConf)
      import spark.implicits._

      val dataSetRow = data.map(f => {
        val dataRow = Row.fromSeq(f._1)
        val dataSeq = dataRow.toSeq.+:(f._2.toString())
        Row.fromSeq(dataSeq)
      })
      import spark.implicits._
      val dataSetDataFrame = sparkSession.createDataFrame(dataSetRow, bDTDataSchema.value).toDF()

      //dataSetDataFrame.describe("cpu_total_pct", "cpu_user_pct", "memory_actual_used_pct", "memory_swap_total", "diskio_read_time", "diskio_write_time", "filesystem_used_pct").show()
      val summaryData = dataSetDataFrame.describe(
    		  "cpu_total_pct",
    		  "cpu_user_pct",
    		  "memory_actual_used_pct",
    		  "memory_swap_total",
    		  "diskio_read_time",
    		  "diskio_write_time",
    		  "filesystem_used_pct")
      val schema = summaryData.schema
      val summaryDataSet = summaryData.flatMap(row => {
        val metric = row.getString(0)
        (1 until row.size).map(i => {
          (metric, schema(i).name, row.getString(i).toDouble)
        })
      }).toDF("metric", "field", "value")
      val filterdDataSet = summaryDataSet.filter($"metric" === "mean" || $"metric" === "stddev")

      val cpuTotalPctSummary = filterdDataSet.filter($"field" === "cpu_total_pct").collect()
      val cpuTotalPctSummary1 = filterdDataSet.filter($"field" === "cpu_total_pct")
      val cpuUserPctSummary = filterdDataSet.filter($"field" === "cpu_user_pct").collect()
      val memActualUsedPctSummary = filterdDataSet.filter($"field" === "memory_actual_used_pct").collect()
      val memSwapTotalSummary = filterdDataSet.filter($"field" === "memory_swap_total").collect()
      val diskioReadTimeSummary = filterdDataSet.filter($"field" === "diskio_read_time").collect()
      val diskioWriteTimeSummary = filterdDataSet.filter($"field" === "diskio_write_time").collect()
      val fsUsedPctSummary = filterdDataSet.filter($"field" === "filesystem_used_pct").collect()

      calcEWMA(cpuTotalPctSummary, 0.3)
      calcEWMA(cpuUserPctSummary, 0.3)
      calcEWMA(memActualUsedPctSummary, 0.3)
      calcEWMA(memSwapTotalSummary, 0.3)
      calcEWMA(diskioReadTimeSummary, 0.3)
      calcEWMA(diskioWriteTimeSummary, 0.3)
      calcEWMA(fsUsedPctSummary, 0.3)

      //      val dateFormat = new SimpleDateFormat("yyyy_MM_dd")
      //      val now = Calendar.getInstance().getTime
      //      val today = dateFormat.format(now)
      //      result.saveToEs(s"poc_os_metric_dt_${today}/metric_dt")

    })
  }
  
  def calcEWMA(collectedSummaryData:Array[Row], weightedValue:Double):(Double, Double) = {
    
    val mean = collectedSummaryData.apply(0).getDouble(2)
    val stddev = collectedSummaryData.apply(1).getDouble(2)
    
    //k = sqrt(w/(2-w))
    val kValue = sqrt(weightedValue/(2-weightedValue))
    val uclResult = mean + 3 * kValue * stddev
    val lclResult = mean - 3 * kValue * stddev
    println("result : " + uclResult + "   " + lclResult)
    
    (uclResult, lclResult)
    
  }

}

object SparkSessionSingleton {

  @transient private var instance: SparkSession = _

  def getInstance(sparkConf: SparkConf): SparkSession = {
    if (instance == null) {
      instance = SparkSession.builder
        .config(sparkConf)
        .getOrCreate()
    }
    instance
  }
}