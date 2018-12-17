
package com.test.dt.utils

import java.util.Properties

import scala.collection.JavaConverters._

import org.apache.spark.streaming.kafka010.OffsetRange
import org.apache.spark.rdd.RDD
import org.apache.spark.SparkContext
import org.apache.spark.streaming.kafka010.KafkaUtils
import org.apache.spark.streaming.kafka010.OffsetRange
import org.apache.spark.streaming.kafka010.LocationStrategies.PreferConsistent

import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.PartitionInfo
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.consumer.ConsumerConfig

import com.test.dt.launcher.JobConfigProperties
import com.test.dt.IDCDTException

class IDCDTKafkaUtil {

  def getLatestOffSetofTopic(brokerList: String, topic: String, dataRange: Long): 
  collection.mutable.Map[TopicPartition, Long] = {
    val clientId = "DT"
    val partitionIdsRequested: Set[Int] = Set(0)
    val listOffsetsTimestamp = -1L

    val config = new Properties
    config.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokerList)
    config.setProperty(ConsumerConfig.CLIENT_ID_CONFIG, clientId)
    val consumer = new KafkaConsumer(config, new ByteArrayDeserializer, new ByteArrayDeserializer)

    val partitionInfos = listPartitionInfos(consumer, topic, partitionIdsRequested) match {
      case None =>
        System.err.println(s"Topic $topic does not exist")
        throw new IDCDTException(s"Topic $topic does not exist")
      case Some(p) if p.isEmpty =>
        if (partitionIdsRequested.isEmpty) {
          System.err.println(s"Topic $topic has 0 partitions")
          throw new IDCDTException(s"Topic $topic has 0 partitions")
        } else {
          System.err.println(
            s"Topic $topic does not have any of the requested partitions ${partitionIdsRequested.mkString(",")}")
          throw new IDCDTException(
            s"Topic $topic does not have any of the requested partitions ${partitionIdsRequested.mkString(",")}")
        }
      case Some(p) => p
    }

    val partitions = new java.util.ArrayList[TopicPartition]
    val fromOffsets = collection.mutable.Map[TopicPartition, Long]()
    val partitionInfo = consumer.partitionsFor(topic).iterator().next()
    
    val idcDataTopicPartition = new TopicPartition(partitionInfo.topic(), partitionInfo.partition())

    partitions.add(idcDataTopicPartition)
    consumer.assign(partitions)
    consumer.seekToEnd(partitions)
    val current = consumer.position(idcDataTopicPartition) - dataRange
    consumer.close()

    fromOffsets += (idcDataTopicPartition -> current)
    fromOffsets

  }

  /**
   * Return the partition infos for `topic`. If the topic does not exist, `None` is returned.
   */
  private def listPartitionInfos(consumer: KafkaConsumer[_, _], 
      topic: String, 
      partitionIds: Set[Int]): Option[Seq[PartitionInfo]] = {
    val partitionInfos = consumer.listTopics.asScala.filterKeys(_ == topic).values
      .flatMap(_.asScala).toBuffer
    if (partitionInfos.isEmpty) {
      None
    } else if (partitionIds.isEmpty) {
      Some(partitionInfos)
    } else {
      Some(partitionInfos.filter(p => partitionIds.contains(p.partition)))
    }
  }

  /**
   *
   */
  def setKafkaParams(brokerAddress: String): Map[String, Object] = {
    val kafkaParams = Map[String, Object](
      "bootstrap.servers" -> brokerAddress,
      "key.deserializer" -> classOf[StringDeserializer],
      "value.deserializer" -> classOf[StringDeserializer],
      "group.id" -> "localhost",
      "auto.offset.reset" -> "latest",
      "enable.auto.commit" -> (false: java.lang.Boolean))
    kafkaParams
  }
}
