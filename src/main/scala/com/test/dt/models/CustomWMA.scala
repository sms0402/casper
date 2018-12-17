
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

class CustomWMA(sparkSession: SparkSession, rangeSize: Integer, weightStr: String) extends Serializable {

  //  private val weightDataArray = Array(0.1,0.2,0.3,0.4)
  private val weightDataArray = Array(0.1, 0.2, 0.3, 0.4)
  private val weightValue = {
    val weightData = weightStr.split(",")
    weightData.map(f => f.toDouble)
  }

  private val schemaNames =
    "create_date,cpu_total_pct,cpu_user_pct," +
      "memory_actual_used_pct,memory_swap_total," +
      "diskio_read_time,diskio_write_time,filesystem_used_pct"

  private val weightRange = {
    val range = collection.mutable.Map[Integer, String]()
    val divisionSize = rangeSize / 4
    val remainder = rangeSize % 4
    var first = 0
    var last = (first + divisionSize) - 1
    for (i <- 0 until 4) {
      val rangeValue = s"${first},${last}"
      range.put(i, rangeValue)
      first = last + 1
      if (i != 2) {
        last = first + divisionSize - 1
      } else {
        last = first + divisionSize + remainder - 1
      }
    }
    range
  }

  private val weightSum = {
    var wSum: Double = 0.0
    val d = rangeSize / 4
    weightDataArray.foreach(f => {
      wSum = wSum. +(f * d)
    })
    wSum
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

  private val dtDateSchema = {
    val fields = Array(StructField("create_date", StringType, nullable = true))
    StructType(fields)
  }

  val bWeightRange = sparkSession.sparkContext.broadcast(weightRange)
  val bWeight = sparkSession.sparkContext.broadcast(weightDataArray)
  val bWeightSum = sparkSession.sparkContext.broadcast(weightSum)
  val bDTDataSchema = sparkSession.sparkContext.broadcast(dtDataSchema)

  def getWeightIndex(
    indexValue: Integer,
    bWeightRangeMap: collection.mutable.Map[Integer, String]): Integer = {

    bWeightRangeMap.foreach(f => {
      val rangeValue = f._2.split(",")
      val firstValue = rangeValue(0).toInt
      if (rangeValue(0).toInt <= indexValue && indexValue <= rangeValue(1).toInt) {
        f._1
      }
    })
    0
  }

  /**
   * input : DStream[(String,String,Long)] => (time stamp, value, kafka offset)
   */
  def runMA(dataSet: DStream[(String, String, Long)], dataRange: Long): Unit = {
    val dtDataRange = sparkSession.sparkContext.broadcast(dataRange)

    val orderedDataSet = dataSet.transform(data => {
      data.sortBy(_._3, false, 4).zipWithIndex().filter(_._2 < dtDataRange.value)
    })

    val calcForDataSetRDD = orderedDataSet.map(value => {
      val columns = value._1._2.split(",").map(value => value.toDouble)
      (columns, value._1._1)
    })

    calcForDataSetRDD.foreachRDD((data: RDD[(Array[Double], String)], time: Time) => {
      val spark = SparkSessionSingleton1.getInstance(data.sparkContext.getConf)
      import spark.implicits._

      val dataSetRow = data.map(f => {
        val dataRow = Row.fromSeq(f._1)
        val dataSeq = dataRow.toSeq.+:(f._2.toString())
        Row.fromSeq(dataSeq)
      })

      import sparkSession.sqlContext.implicits._
      val dataSetDataFrame = sparkSession.createDataFrame(dataSetRow, bDTDataSchema.value).toDF()
      val result = dataSetDataFrame.agg(
        avg("cpu_total_pct"), avg("cpu_user_pct"),
        avg("memory_actual_used_pct"), avg("memory_swap_total"),
        avg("diskio_read_time"), avg("diskio_write_time"),
        avg("filesystem_used_pct"), max("create_date"))
        .toDF("dt_cpu_total_pct", "dt_cpu_user_pct",
          "dt_mem_actual_used_pct", "dt_mem_swap_used_pct",
          "dt_diskio_read_time", "dt_diskio_write_time", "dt_fs_used_pct", "create_date")
        .withColumn("@timestamp", from_unixtime(unix_timestamp(), "y-MM-dd'T'HH:mm:ssZ"))

      result.show()
      val dateFormat = new SimpleDateFormat("yyyy_MM_dd")
      val now = Calendar.getInstance().getTime
      val today = dateFormat.format(now)
      result.saveToEs(s"poc_os_metric_dt_${today}/metric_dt")

    })
  }

  /**
   * input : DStream[(String,String,Long)] => (time stamp, value, kafka offset)
   */
  def runWMA(dataSet: DStream[(String, String, Long)], dataRange: Long): Unit = {

    val dtDataRange = sparkSession.sparkContext.broadcast(dataRange)

    /**
     * sort by offset of kafka.
     */
    val orderedDataSet = dataSet.transform(data => {
      data.sortBy(_._3, false, 4).zipWithIndex().filter(_._2 < dtDataRange.value)
    })

    orderedDataSet.foreachRDD(data => {
      data.foreach(println)
    })
    orderedDataSet.count()

    val vectorRDD = orderedDataSet.map(value => {
      val columns = value._1._2.split(",").map(value => value.toDouble)
      (new DenseVector(columns), value._2, value._1._1)
    }).mapPartitions(partition => {
      partition.map(data => {
        val rangeIndex = getWeightIndex(data._2.toInt, bWeightRange.value)
        val rangeValue = bWeight.value(rangeIndex)
        (data._1 :* rangeValue, data._3)
      })

    }, true)

    vectorRDD.foreachRDD((data: RDD[(DenseVector[Double], String)], time: Time) => {
      val spark = SparkSessionSingleton.getInstance(data.sparkContext.getConf)
      import spark.implicits._

      val vectorRow = data.map(f => {
        val dataRow = Row.fromSeq(f._1.data)
        val dataSeq = dataRow.toSeq. +:(f._2.toString())
        Row.fromSeq(dataSeq)
      })

      import sparkSession.sqlContext.implicits._
      val vectorDataFrame = sparkSession.createDataFrame(vectorRow, bDTDataSchema.value).toDF()

      //vectorDataFrame.show()
      //vectorDataFrame.schema.printTreeString()
      val lastDataSet = vectorDataFrame.withColumn("create_date", col("create_date").cast("timestamp"))

      val result = lastDataSet.agg(
        sum("cpu_total_pct") / bWeightSum.value, sum("cpu_user_pct") / bWeightSum.value,
        sum("memory_actual_used_pct") / bWeightSum.value, sum("memory_swap_total") / bWeightSum.value,
        sum("diskio_read_time") / bWeightSum.value, sum("diskio_write_time") / bWeightSum.value,
        sum("filesystem_used_pct") / bWeightSum.value, max("create_date"))
        .toDF("dt_cpu_total_pct", "dt_cpu_user_pct",
          "dt_mem_actual_used_pct", "dt_mem_swap_used_pct",
          "dt_diskio_read_time", "dt_diskio_write_time", "dt_fs_used_pct", "create_date")
        .withColumn("@timestamp", from_unixtime(unix_timestamp(), "y-MM-dd'T'HH:mm:ssZ"))
      result.show()
      val dateFormat = new SimpleDateFormat("yyyy_MM_dd")
      val now = Calendar.getInstance().getTime
      val today = dateFormat.format(now)
      result.saveToEs(s"poc_os_metric_dt_${today}/metric_dt")

    })

  }
}

object SparkSessionSingleton1 {

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
