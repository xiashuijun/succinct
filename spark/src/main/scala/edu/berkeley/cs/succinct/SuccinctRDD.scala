package edu.berkeley.cs.succinct

import java.io.ByteArrayOutputStream

import edu.berkeley.cs.succinct.buffers.SuccinctIndexedFileBuffer
import edu.berkeley.cs.succinct.impl.SuccinctRDDImpl
import edu.berkeley.cs.succinct.regex.RegExMatch
import edu.berkeley.cs.succinct.util.SuccinctConstants
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, InvalidPathException, Path, PathFilter}
import org.apache.spark._
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import org.apache.spark.storage.StorageLevel
import org.apache.spark.succinct.SuccinctPartition

import scala.collection.mutable.ArrayBuffer

/**
  * Extends `RDD[Array[Byte]]` to a SuccinctRDD, which stores encodes each partition of the parent
  * RDD using Succinct. SuccinctRDD supports count, search and extract operations, which treat the
  * RDD as a flat file. SuccinctRDD additionally supports search and extract operations on a
  * record granularity.
  */

abstract class SuccinctRDD(@transient private val sc: SparkContext,
                           @transient private val deps: Seq[Dependency[_]])
  extends RDD[Array[Byte]](sc, deps) {

  /** Overrides the compute function to return iterator over Succinct records. */
  override def compute(split: Partition, context: TaskContext): Iterator[Array[Byte]] = {
    val succinctIterator = firstParent[SuccinctPartition].iterator(split, context)
    if (succinctIterator.hasNext) {
      succinctIterator.next().iterator
    } else {
      Iterator[Array[Byte]]()
    }
  }

  /**
    * Search for all occurrences of a query string within the RDD.
    *
    * @param query The search query.
    * @return The RDD of recordIds.
    */
  def search(query: String): RDD[Long] = {
    search(query.getBytes("utf-8"))
  }

  /**
    * Search for all occurrences of a query within the RDD.
    *
    * @param query The search query.
    * @return The RDD of iterables over recordIds into each partition.
    */
  def search(query: Array[Byte]): RDD[Long] = {
    partitionsRDD.flatMap(_.search(query))
  }

  /**
    * Counts for all occurrences of a query in the RDD.
    *
    * @param query The count query.
    * @return The count of the number of occurrences of the query.
    */
  def count(query: String): Long = {
    count(query.getBytes("utf-8"))
  }

  /**
    * Counts for all occurrences of a query in the RDD.
    *
    * @param query The count query.
    * @return The count of the number of occurrences of the query.
    */
  def count(query: Array[Byte]): Long = {
    partitionsRDD.map(_.count(query)).aggregate(0L)(_ + _, _ + _)
  }

  /**
    * Provides random access into the RDD; extracts specified number of bytes starting at specified
    * offset into the original RDD.
    *
    * @param offset Offset into original RDD.
    * @param length Number of bytes to be fetched.
    * @return The extracted data.
    */
  def extract(offset: Long, length: Int): Array[Byte]

  /**
    * Searches for the input regular expression within each RDD and
    * returns results as (offset, length) pairs.
    *
    * The query must be UTF-8 encoded.
    *
    * @param query The regular expression search query.
    * @return RDD of matched pattern occurrences.
    */
  def regexSearch(query: String): RDD[RegExMatch] = {
    partitionsRDD.flatMap(_.regexSearch(query))
  }

  /**
    * Bulk append data to SuccinctJsonRDD; returns a new SuccinctJsonRDD, with the newly appended
    * data encoded as Succinct data structures. The original RDD is removed from memory after this
    * operation.
    *
    * @param data                 The data to be appended.
    * @param preservePartitioning Preserves the partitioning for the appended data if true;
    *                             repartitions the data otherwise.
    * @return A new SuccinctJsonRDD containing the newly appended data.
    */
  def bulkAppend(data: RDD[Array[Byte]], preservePartitioning: Boolean = false): SuccinctRDD = {
    val countPerPartition: Double = count().toDouble / partitionsRDD.partitions.length.toDouble
    val nNewPartitions: Int = Math.ceil(data.count() / countPerPartition).toInt

    def partition(data: RDD[Array[Byte]]): RDD[Array[Byte]] = {
      if (preservePartitioning) data
      else data.repartition(nNewPartitions)
    }

    val partitionSizes = data.mapPartitionsWithIndex((idx, partition) => {
      val partitionSize = partition.aggregate(0L)((sum, record) => sum + (record.length + 1), _ + _)
      Iterator((idx, partitionSize))
    }
    ).collect().sorted.map(_._2)

    val partitionRecordCounts = data.mapPartitionsWithIndex((idx, partition) => {
      val partitionRecordCount = partition.size
      Iterator((idx, partitionRecordCount))
    }).collect().sorted.map(_._2)

    val originalSize = partitionsRDD.map(_.sizeInBytes).aggregate(0L)(_ + _, _ + _)
    val originalCount = count()

    val partitionOffsets = partitionSizes.map(_ + originalSize).scanLeft(0L)(_ + _)
    val partitionFirstRecordIds = partitionRecordCounts.map(_ + originalCount).scanLeft(0L)(_ + _)
    val newPartitions = partition(data)
      .mapPartitionsWithIndex((i, p) =>
        SuccinctRDD.createSuccinctPartition(partitionOffsets(i), partitionFirstRecordIds(i), p))
    val newSuccinctRDDPartitions = partitionsRDD.union(newPartitions).cache()
    partitionsRDD.unpersist()
    new SuccinctRDDImpl(newSuccinctRDDPartitions)
  }

  /**
    * Count the number of records in the SuccinctRDD.
    *
    * @return The number of records in the SuccinctRDD.
    */
  override def count(): Long = {
    partitionsRDD.map(_.count).aggregate(0L)(_ + _, _ + _)
  }

  /**
    * Saves the SuccinctRDD at the specified path.
    *
    * @param location The path where the SuccinctRDD should be stored.
    */
  def save(location: String, conf: Configuration = new Configuration()): Unit = {
    val path = new Path(location)
    val fs = FileSystem.get(path.toUri, conf)
    if (!fs.exists(path)) {
      fs.mkdirs(path)
    }

    val serializableConf = new SerializableWritable(conf)

    partitionsRDD.zipWithIndex().foreach(entry => {
      val i = entry._2
      val partition = entry._1
      val partitionLocation = location.stripSuffix("/") + "/part-" + "%05d".format(i)
      val path = new Path(partitionLocation)
      val fs = FileSystem.get(path.toUri, serializableConf.value)
      val os = fs.create(path)
      partition.writeToStream(os)
      os.close()
    })

    val successPath = new Path(location.stripSuffix("/") + "/_SUCCESS")
    fs.create(successPath).close()
  }

  /**
    * Returns first parent of the RDD.
    *
    * @return The first parent of the RDD.
    */
  protected[succinct] def getFirstParent: RDD[SuccinctPartition] = {
    firstParent[SuccinctPartition]
  }

  /**
    * Returns the array of partitions.
    *
    * @return The array of partitions.
    */
  override protected def getPartitions: Array[Partition] = partitionsRDD.partitions

  /**
    * Returns the RDD of partitions.
    *
    * @return The RDD of partitions.
    */
  private[succinct] def partitionsRDD: RDD[SuccinctPartition]

}

/** Factory for [[SuccinctRDD]] instances */
object SuccinctRDD {

  /**
    * Converts an input RDD to SuccinctRDD.
    *
    * @param inputRDD The input RDD.
    * @return The SuccinctRDD.
    */
  def apply(inputRDD: RDD[Array[Byte]]): SuccinctRDD = {
    val partitionSizes = inputRDD.mapPartitionsWithIndex((idx, partition) => {
      val partitionSize = partition.aggregate(0L)((sum, record) => sum + (record.length + 1), _ + _)
      Iterator((idx, partitionSize))
    }
    ).collect().sorted.map(_._2)

    val partitionRecordCounts = inputRDD.mapPartitionsWithIndex((idx, partition) => {
      val partitionRecordCount = partition.size
      Iterator((idx, partitionRecordCount))
    }).collect().sorted.map(_._2)

    val partitionOffsets = partitionSizes.scanLeft(0L)(_ + _)
    val partitionFirstRecordIds = partitionRecordCounts.scanLeft(0L)(_ + _)

    val succinctPartitions = inputRDD.mapPartitionsWithIndex((i, p) =>
      createSuccinctPartition(partitionOffsets(i), partitionFirstRecordIds(i), p)).cache()
    new SuccinctRDDImpl(succinctPartitions)
  }

  /**
    * Creates a SuccinctPartition from a partition of the input RDD.
    *
    * @param partitionOffset        The offset of the partition.
    * @param partitionFirstRecordId The first recordId in the partition.
    * @param dataIter               The iterator over the input partition data.
    * @return An Iterator over the SuccinctPartition.
    */
  private[succinct] def createSuccinctPartition(partitionOffset: Long,
                                                partitionFirstRecordId: Long,
                                                dataIter: Iterator[Array[Byte]]):
  Iterator[SuccinctPartition] = {
    var offsets = new ArrayBuffer[Int]()
    var buffers = new ArrayBuffer[Array[Byte]]()
    var offset = 0
    var partitionSize = 0
    while (dataIter.hasNext) {
      val curRecord = dataIter.next()
      buffers += curRecord
      partitionSize += (curRecord.length + 1)
      offsets += offset
      offset += (curRecord.length + 1)
    }

    val rawBufferOS = new ByteArrayOutputStream(partitionSize)
    for (i <- buffers.indices) {
      val curRecord = buffers(i)
      rawBufferOS.write(curRecord)
      rawBufferOS.write(SuccinctConstants.EOL)
    }

    val succinctBuf = new SuccinctIndexedFileBuffer(rawBufferOS.toByteArray, offsets.toArray)
    Iterator(new SuccinctPartition(succinctBuf, partitionOffset, partitionFirstRecordId))
  }

  /**
    * Reads a SuccinctRDD from disk.
    *
    * @param sc           The spark context
    * @param location     The path to read the SuccinctRDD from.
    * @param storageLevel The storage level for the SuccinctRDD.
    * @return The SuccinctRDD.
    */
  def apply(sc: SparkContext, location: String, storageLevel: StorageLevel): SuccinctRDD = {
    val locationPath = new Path(location)
    val fs = FileSystem.get(locationPath.toUri, sc.hadoopConfiguration)
    if (fs.isDirectory(locationPath)) {
      val status = fs.listStatus(locationPath, new PathFilter {
        override def accept(path: Path): Boolean = {
          path.getName.startsWith("part-")
        }
      })
      val numPartitions = status.length
      val succinctPartitions = sc.parallelize(0 until numPartitions, numPartitions)
        .mapPartitionsWithIndex[SuccinctPartition]((i, _) => {
        val partitionLocation = location.stripSuffix("/") + "/part-" + "%05d".format(i)
        Iterator(SuccinctPartition(partitionLocation, storageLevel))
      }).cache()
      new SuccinctRDDImpl(succinctPartitions, storageLevel)
    } else if (fs.isFile(locationPath)) {
      val succinctPartitions = sc.parallelize(0 until 1, 1).mapPartitions[SuccinctPartition](_ => {
        Iterator(SuccinctPartition(location, storageLevel))
      }).cache()
      new SuccinctRDDImpl(succinctPartitions, storageLevel)
    } else {
      throw new InvalidPathException("Path is not a file or directory")
    }
  }

  /**
    * Reads a SuccinctRDD from disk.
    *
    * @param spark        The spark session.
    * @param location     The path to read the SuccinctRDD from.
    * @param storageLevel The storage level for the SuccinctRDD.
    * @return The SuccinctRDD.
    */
  def apply(spark: SparkSession, location: String, storageLevel: StorageLevel): SuccinctRDD = {
    apply(spark.sparkContext, location, storageLevel)
  }

}
