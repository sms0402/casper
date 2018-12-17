
package com.hanwha.idc.dt.calc

import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession
import org.apache.spark.streaming.Seconds
import org.apache.spark.streaming.StreamingContext
import org.apache.spark.streaming.kafka010.KafkaUtils
import org.apache.spark.streaming.kafka010.LocationStrategies.PreferConsistent
//import org.apache.spark.streaming.kafka010.PreferConsistent
import org.apache.spark.streaming.kafka010.ConsumerStrategies.Assign
import org.apache.spark.streaming.kafka010.ConsumerStrategies.Subscribe

import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.spark.streaming.kafka010.ConsumerStrategies.Subscribe
import org.apache.spark.storage.StorageLevel.MEMORY_AND_DISK
import org.apache.spark.sql.SQLContext
import org.apache.spark.rdd.RDD
import org.apache.spark.SparkContext
import org.apache.commons.codec.StringDecoder
import org.apache.spark.streaming.kafka010.Assign
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.spark.streaming.Minutes
import org.apache.spark.storage.StorageLevel

import com.hanwha.idc.dt.launcher.JobConfigProperties
import com.hanwha.idc.dt.models.WMA
import com.hanwha.idc.dt.utils.IDCDTKafkaUtil
import com.hanwha.idc.dt.IDCDTException

import scala.collection.JavaConverters._
import java.io.FileInputStream
import java.io.File
import org.apache.log4j.LogManager
import org.apache.log4j.Level

object DTMain {
  def main(args: Array[String]): Unit = {

    val log = LogManager.getRootLogger
    log.setLevel(Level.WARN)
    
    val (confFilePath: String, master: String) = if (args != null && args.size != 0) {
      val filePath = args.apply(0)
      val masterConf = args.apply(1)
      (filePath, masterConf)
    } else {
      // config file path for local test
      val filePath = ""
      val masterConf = "local[4]"
      (filePath, masterConf)
    }

    if (confFilePath == null || master == null) {
      throw new IDCDTException(s"Configuration file or Master info does not exist")
    }

    val dtProperties = getConfig(confFilePath)
    log.info(dtProperties.size)
    dtProperties.foreach(f => println(f._1 + "  " + f._2))
    val sparkConf = getSparkConf(
      master,
      dtProperties.getOrElse("idc.es.url", "localhost"),
      dtProperties.getOrElse("idc.es.port", "9200"))

    val ss = new SparkContext()      
      
    val sparkSession = SparkSession.builder()
      .config(sparkConf)
      .appName("DT-Calc")
      .getOrCreate()

    val idcDtKafkaUtil = new com.hanwha.idc.dt.utils.IDCDTKafkaUtil()
    val topic = dtProperties.getOrElse("idc.kafka.topic", "localhost")
    val brokerAddress = dtProperties.getOrElse("idc.kafka.address", "localhost:9092")
    val dataRange = dtProperties.getOrElse("idc.kafka.data.range", "100")
    val slidingSize = dtProperties.getOrElse("idc.spark.sliding.size", "1")
    val weightStr = dtProperties.getOrElse("idc.data.weight.value", "0.1,0.2,0.3,0.4")
    val tsType = dtProperties.getOrElse("idc.data.ts.type", "MA")
    val wmaModel = new WMA(sparkSession, dataRange.toInt, weightStr)
    val latestOffSet = idcDtKafkaUtil.getLatestOffSetofTopic(brokerAddress, topic, dataRange.toLong)
    val streamingContext = new StreamingContext(sparkSession.sparkContext, Seconds(60))
    val stream = KafkaUtils.createDirectStream(streamingContext, PreferConsistent,
      Assign[String, String](latestOffSet.keys, idcDtKafkaUtil.setKafkaParams(brokerAddress), latestOffSet))

    val extractedDStream = stream.map(streamData => {
      (streamData.key(), streamData.value(), streamData.offset())
    })

    val analyzeDataSetDStream = extractedDStream.window(Minutes(dataRange.toLong), Minutes(slidingSize.toLong))
    analyzeDataSetDStream.count().foreachRDD(data => data.foreach(f => println("windowData  count : " + f)))

    // DStream[(String,String,Long)] => (timestamp, value, kafkaoffset)
    tsType.trim() match {
      case "WMA" => wmaModel.runWMA(analyzeDataSetDStream, dataRange.toLong)
      case _ => wmaModel.runMA(analyzeDataSetDStream, dataRange.toLong)
    }

    streamingContext.start()
    streamingContext.awaitTermination()
  }

  def getConfig(configFile: String): Map[String, String] = {
    val config = new java.util.Properties()
    val fileStream = new FileInputStream(new File(configFile))
    config.load(fileStream)
    fileStream.close()
    config.asScala.toMap

  }

  def getSparkConf(master: String, esUrl: String, esPort: String): SparkConf = {
    val conf = new SparkConf().setAppName("DT").setMaster(master)
    conf.set("spark.serializer", "org.apache.spark.serializer.KryoSerializer");
    conf.set("spark.executor.memory", "2g")
    conf.set("spark.kryoserializer.buffer.mb", "256")
    conf.set("es.nodes", esUrl)
    conf.set("es.port", esPort)
    conf
  }
}
