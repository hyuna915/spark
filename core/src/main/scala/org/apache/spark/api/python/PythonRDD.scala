/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.api.python

import java.io._
import java.net._
import java.util.{List => JList, ArrayList => JArrayList, Map => JMap, Collections}

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.language.existentials

import com.google.common.base.Charsets.UTF_8
import net.razorvine.pickle.{Pickler, Unpickler}

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.io.compress.CompressionCodec
import org.apache.hadoop.mapred.{InputFormat, OutputFormat, JobConf}
import org.apache.hadoop.mapreduce.{InputFormat => NewInputFormat, OutputFormat => NewOutputFormat}
import org.apache.spark._
import org.apache.spark.SparkContext._
import org.apache.spark.api.java.{JavaSparkContext, JavaPairRDD, JavaRDD}
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd.RDD
import org.apache.spark.util.Utils

private[spark] class PythonRDD(
    @transient parent: RDD[_],
    command: Array[Byte],
    envVars: JMap[String, String],
    pythonIncludes: JList[String],
    preservePartitoning: Boolean,
    pythonExec: String,
    broadcastVars: JList[Broadcast[Array[Byte]]],
    accumulator: Accumulator[JList[Array[Byte]]])
  extends RDD[Array[Byte]](parent) {

  val bufferSize = conf.getInt("spark.buffer.size", 65536)
  val reuse_worker = conf.getBoolean("spark.python.worker.reuse", true)

  override def getPartitions = firstParent.partitions

  override val partitioner = if (preservePartitoning) firstParent.partitioner else None

  override def compute(split: Partition, context: TaskContext): Iterator[Array[Byte]] = {
    val startTime = System.currentTimeMillis
    val env = SparkEnv.get
    val localdir = env.blockManager.diskBlockManager.localDirs.map(
      f => f.getPath()).mkString(",")
    envVars += ("SPARK_LOCAL_DIRS" -> localdir) // it's also used in monitor thread
    if (reuse_worker) {
      envVars += ("SPARK_REUSE_WORKER" -> "1")
    }
    val worker: Socket = env.createPythonWorker(pythonExec, envVars.toMap)

    // Start a thread to feed the process input from our parent's iterator
    val writerThread = new WriterThread(env, worker, split, context)

    var complete_cleanly = false
    context.addTaskCompletionListener { context =>
      writerThread.shutdownOnTaskCompletion()
      writerThread.join()
      if (reuse_worker && complete_cleanly) {
        env.releasePythonWorker(pythonExec, envVars.toMap, worker)
      } else {
        try {
          worker.close()
        } catch {
          case e: Exception =>
            logWarning("Failed to close worker socket", e)
        }
      }
    }

    writerThread.start()
    new MonitorThread(env, worker, context).start()

    // Return an iterator that read lines from the process's stdout
    val stream = new DataInputStream(new BufferedInputStream(worker.getInputStream, bufferSize))
    val stdoutIterator = new Iterator[Array[Byte]] {
      def next(): Array[Byte] = {
        val obj = _nextObj
        if (hasNext) {
          _nextObj = read()
        }
        obj
      }

      private def read(): Array[Byte] = {
        if (writerThread.exception.isDefined) {
          throw writerThread.exception.get
        }
        try {
          stream.readInt() match {
            case length if length > 0 =>
              val obj = new Array[Byte](length)
              stream.readFully(obj)
              obj
            case 0 => Array.empty[Byte]
            case SpecialLengths.TIMING_DATA =>
              // Timing data from worker
              val bootTime = stream.readLong()
              val initTime = stream.readLong()
              val finishTime = stream.readLong()
              val boot = bootTime - startTime
              val init = initTime - bootTime
              val finish = finishTime - initTime
              val total = finishTime - startTime
              logInfo("Times: total = %s, boot = %s, init = %s, finish = %s".format(total, boot,
                init, finish))
              val memoryBytesSpilled = stream.readLong()
              val diskBytesSpilled = stream.readLong()
              context.taskMetrics.memoryBytesSpilled += memoryBytesSpilled
              context.taskMetrics.diskBytesSpilled += diskBytesSpilled
              read()
            case SpecialLengths.PYTHON_EXCEPTION_THROWN =>
              // Signals that an exception has been thrown in python
              val exLength = stream.readInt()
              val obj = new Array[Byte](exLength)
              stream.readFully(obj)
              throw new PythonException(new String(obj, UTF_8),
                writerThread.exception.getOrElse(null))
            case SpecialLengths.END_OF_DATA_SECTION =>
              // We've finished the data section of the output, but we can still
              // read some accumulator updates:
              val numAccumulatorUpdates = stream.readInt()
              (1 to numAccumulatorUpdates).foreach { _ =>
                val updateLen = stream.readInt()
                val update = new Array[Byte](updateLen)
                stream.readFully(update)
                accumulator += Collections.singletonList(update)
              }
              if (stream.readInt() == SpecialLengths.END_OF_STREAM) {
                complete_cleanly = true
              }
              null
          }
        } catch {

          case e: Exception if context.isInterrupted =>
            logDebug("Exception thrown after task interruption", e)
            throw new TaskKilledException

          case e: Exception if env.isStopped =>
            logDebug("Exception thrown after context is stopped", e)
            null  // exit silently

          case e: Exception if writerThread.exception.isDefined =>
            logError("Python worker exited unexpectedly (crashed)", e)
            logError("This may have been caused by a prior exception:", writerThread.exception.get)
            throw writerThread.exception.get

          case eof: EOFException =>
            throw new SparkException("Python worker exited unexpectedly (crashed)", eof)
        }
      }

      var _nextObj = read()

      def hasNext = _nextObj != null
    }
    new InterruptibleIterator(context, stdoutIterator)
  }

  val asJavaRDD : JavaRDD[Array[Byte]] = JavaRDD.fromRDD(this)

  /**
   * The thread responsible for writing the data from the PythonRDD's parent iterator to the
   * Python process.
   */
  class WriterThread(env: SparkEnv, worker: Socket, split: Partition, context: TaskContext)
    extends Thread(s"stdout writer for $pythonExec") {

    @volatile private var _exception: Exception = null

    setDaemon(true)

    /** Contains the exception thrown while writing the parent iterator to the Python process. */
    def exception: Option[Exception] = Option(_exception)

    /** Terminates the writer thread, ignoring any exceptions that may occur due to cleanup. */
    def shutdownOnTaskCompletion() {
      assert(context.isCompleted)
      this.interrupt()
    }

    override def run(): Unit = Utils.logUncaughtExceptions {
      try {
        val stream = new BufferedOutputStream(worker.getOutputStream, bufferSize)
        val dataOut = new DataOutputStream(stream)
        // Partition index
        dataOut.writeInt(split.index)
        // sparkFilesDir
        PythonRDD.writeUTF(SparkFiles.getRootDirectory, dataOut)
        // Python includes (*.zip and *.egg files)
        dataOut.writeInt(pythonIncludes.length)
        for (include <- pythonIncludes) {
          PythonRDD.writeUTF(include, dataOut)
        }
        // Broadcast variables
        val oldBids = PythonRDD.getWorkerBroadcasts(worker)
        val newBids = broadcastVars.map(_.id).toSet
        // number of different broadcasts
        val cnt = oldBids.diff(newBids).size + newBids.diff(oldBids).size
        dataOut.writeInt(cnt)
        for (bid <- oldBids) {
          if (!newBids.contains(bid)) {
            // remove the broadcast from worker
            dataOut.writeLong(- bid - 1)  // bid >= 0
            oldBids.remove(bid)
          }
        }
        for (broadcast <- broadcastVars) {
          if (!oldBids.contains(broadcast.id)) {
            // send new broadcast
            dataOut.writeLong(broadcast.id)
            dataOut.writeInt(broadcast.value.length)
            dataOut.write(broadcast.value)
            oldBids.add(broadcast.id)
          }
        }
        dataOut.flush()
        // Serialized command:
        dataOut.writeInt(command.length)
        dataOut.write(command)
        // Data values
        PythonRDD.writeIteratorToStream(firstParent.iterator(split, context), dataOut)
        dataOut.writeInt(SpecialLengths.END_OF_DATA_SECTION)
        dataOut.writeInt(SpecialLengths.END_OF_STREAM)
        dataOut.flush()
      } catch {
        case e: Exception if context.isCompleted || context.isInterrupted =>
          logDebug("Exception thrown after task completion (likely due to cleanup)", e)
          worker.shutdownOutput()

        case e: Exception =>
          // We must avoid throwing exceptions here, because the thread uncaught exception handler
          // will kill the whole executor (see org.apache.spark.executor.Executor).
          _exception = e
          worker.shutdownOutput()
      } finally {
        // Release memory used by this thread for shuffles
        env.shuffleMemoryManager.releaseMemoryForThisThread()
        // Release memory used by this thread for unrolling blocks
        env.blockManager.memoryStore.releaseUnrollMemoryForThisThread()
      }
    }
  }

  /**
   * It is necessary to have a monitor thread for python workers if the user cancels with
   * interrupts disabled. In that case we will need to explicitly kill the worker, otherwise the
   * threads can block indefinitely.
   */
  class MonitorThread(env: SparkEnv, worker: Socket, context: TaskContext)
    extends Thread(s"Worker Monitor for $pythonExec") {

    setDaemon(true)

    override def run() {
      // Kill the worker if it is interrupted, checking until task completion.
      // TODO: This has a race condition if interruption occurs, as completed may still become true.
      while (!context.isInterrupted && !context.isCompleted) {
        Thread.sleep(2000)
      }
      if (!context.isCompleted) {
        try {
          logWarning("Incomplete task interrupted: Attempting to kill Python Worker")
          env.destroyPythonWorker(pythonExec, envVars.toMap, worker)
        } catch {
          case e: Exception =>
            logError("Exception when trying to kill worker", e)
        }
      }
    }
  }
}

/** Thrown for exceptions in user Python code. */
private class PythonException(msg: String, cause: Exception) extends RuntimeException(msg, cause)

/**
 * Form an RDD[(Array[Byte], Array[Byte])] from key-value pairs returned from Python.
 * This is used by PySpark's shuffle operations.
 */
private class PairwiseRDD(prev: RDD[Array[Byte]]) extends
  RDD[(Long, Array[Byte])](prev) {
  override def getPartitions = prev.partitions
  override def compute(split: Partition, context: TaskContext) =
    prev.iterator(split, context).grouped(2).map {
      case Seq(a, b) => (Utils.deserializeLongValue(a), b)
      case x => throw new SparkException("PairwiseRDD: unexpected value: " + x)
    }
  val asJavaPairRDD : JavaPairRDD[Long, Array[Byte]] = JavaPairRDD.fromRDD(this)
}

private object SpecialLengths {
  val END_OF_DATA_SECTION = -1
  val PYTHON_EXCEPTION_THROWN = -2
  val TIMING_DATA = -3
  val END_OF_STREAM = -4
}

private[spark] object PythonRDD extends Logging {

  // remember the broadcasts sent to each worker
  private val workerBroadcasts = new mutable.WeakHashMap[Socket, mutable.Set[Long]]()
  private def getWorkerBroadcasts(worker: Socket) = {
    synchronized {
      workerBroadcasts.getOrElseUpdate(worker, new mutable.HashSet[Long]())
    }
  }

  /**
   * Adapter for calling SparkContext#runJob from Python.
   *
   * This method will return an iterator of an array that contains all elements in the RDD
   * (effectively a collect()), but allows you to run on a certain subset of partitions,
   * or to enable local execution.
   */
  def runJob(
      sc: SparkContext,
      rdd: JavaRDD[Array[Byte]],
      partitions: JArrayList[Int],
      allowLocal: Boolean): Iterator[Array[Byte]] = {
    type ByteArray = Array[Byte]
    type UnrolledPartition = Array[ByteArray]
    val allPartitions: Array[UnrolledPartition] =
      sc.runJob(rdd, (x: Iterator[ByteArray]) => x.toArray, partitions, allowLocal)
    val flattenedPartition: UnrolledPartition = Array.concat(allPartitions: _*)
    flattenedPartition.iterator
  }

  def readRDDFromFile(sc: JavaSparkContext, filename: String, parallelism: Int):
  JavaRDD[Array[Byte]] = {
    val file = new DataInputStream(new FileInputStream(filename))
    try {
      val objs = new collection.mutable.ArrayBuffer[Array[Byte]]
      try {
        while (true) {
          val length = file.readInt()
          val obj = new Array[Byte](length)
          file.readFully(obj)
          objs.append(obj)
        }
      } catch {
        case eof: EOFException => {}
      }
      JavaRDD.fromRDD(sc.sc.parallelize(objs, parallelism))
    } finally {
      file.close()
    }
  }

  def readBroadcastFromFile(sc: JavaSparkContext, filename: String): Broadcast[Array[Byte]] = {
    val file = new DataInputStream(new FileInputStream(filename))
    try {
      val length = file.readInt()
      val obj = new Array[Byte](length)
      file.readFully(obj)
      sc.broadcast(obj)
    } finally {
      file.close()
    }
  }

  def writeIteratorToStream[T](iter: Iterator[T], dataOut: DataOutputStream) {
    // The right way to implement this would be to use TypeTags to get the full
    // type of T.  Since I don't want to introduce breaking changes throughout the
    // entire Spark API, I have to use this hacky approach:
    if (iter.hasNext) {
      val first = iter.next()
      val newIter = Seq(first).iterator ++ iter
      first match {
        case arr: Array[Byte] =>
          newIter.asInstanceOf[Iterator[Array[Byte]]].foreach { bytes =>
            dataOut.writeInt(bytes.length)
            dataOut.write(bytes)
          }
        case string: String =>
          newIter.asInstanceOf[Iterator[String]].foreach { str =>
            writeUTF(str, dataOut)
          }
        case pair: Tuple2[_, _] =>
          pair._1 match {
            case bytePair: Array[Byte] =>
              newIter.asInstanceOf[Iterator[Tuple2[Array[Byte], Array[Byte]]]].foreach { pair =>
                dataOut.writeInt(pair._1.length)
                dataOut.write(pair._1)
                dataOut.writeInt(pair._2.length)
                dataOut.write(pair._2)
              }
            case stringPair: String =>
              newIter.asInstanceOf[Iterator[Tuple2[String, String]]].foreach { pair =>
                writeUTF(pair._1, dataOut)
                writeUTF(pair._2, dataOut)
              }
            case other =>
              throw new SparkException("Unexpected Tuple2 element type " + pair._1.getClass)
          }
        case other =>
          throw new SparkException("Unexpected element type " + first.getClass)
      }
    }
  }

  /**
   * Create an RDD from a path using [[org.apache.hadoop.mapred.SequenceFileInputFormat]],
   * key and value class.
   * A key and/or value converter class can optionally be passed in
   * (see [[org.apache.spark.api.python.Converter]])
   */
  def sequenceFile[K, V](
      sc: JavaSparkContext,
      path: String,
      keyClassMaybeNull: String,
      valueClassMaybeNull: String,
      keyConverterClass: String,
      valueConverterClass: String,
      minSplits: Int,
      batchSize: Int) = {
    val keyClass = Option(keyClassMaybeNull).getOrElse("org.apache.hadoop.io.Text")
    val valueClass = Option(valueClassMaybeNull).getOrElse("org.apache.hadoop.io.Text")
    val kc = Utils.classForName(keyClass).asInstanceOf[Class[K]]
    val vc = Utils.classForName(valueClass).asInstanceOf[Class[V]]
    val rdd = sc.sc.sequenceFile[K, V](path, kc, vc, minSplits)
    val confBroadcasted = sc.sc.broadcast(new SerializableWritable(sc.hadoopConfiguration()))
    val converted = convertRDD(rdd, keyConverterClass, valueConverterClass,
      new WritableToJavaConverter(confBroadcasted, batchSize))
    JavaRDD.fromRDD(SerDeUtil.pairRDDToPython(converted, batchSize))
  }

  /**
   * Create an RDD from a file path, using an arbitrary [[org.apache.hadoop.mapreduce.InputFormat]],
   * key and value class.
   * A key and/or value converter class can optionally be passed in
   * (see [[org.apache.spark.api.python.Converter]])
   */
  def newAPIHadoopFile[K, V, F <: NewInputFormat[K, V]](
      sc: JavaSparkContext,
      path: String,
      inputFormatClass: String,
      keyClass: String,
      valueClass: String,
      keyConverterClass: String,
      valueConverterClass: String,
      confAsMap: java.util.HashMap[String, String],
      batchSize: Int) = {
    val mergedConf = getMergedConf(confAsMap, sc.hadoopConfiguration())
    val rdd =
      newAPIHadoopRDDFromClassNames[K, V, F](sc,
        Some(path), inputFormatClass, keyClass, valueClass, mergedConf)
    val confBroadcasted = sc.sc.broadcast(new SerializableWritable(mergedConf))
    val converted = convertRDD(rdd, keyConverterClass, valueConverterClass,
      new WritableToJavaConverter(confBroadcasted, batchSize))
    JavaRDD.fromRDD(SerDeUtil.pairRDDToPython(converted, batchSize))
  }

  /**
   * Create an RDD from a [[org.apache.hadoop.conf.Configuration]] converted from a map that is
   * passed in from Python, using an arbitrary [[org.apache.hadoop.mapreduce.InputFormat]],
   * key and value class.
   * A key and/or value converter class can optionally be passed in
   * (see [[org.apache.spark.api.python.Converter]])
   */
  def newAPIHadoopRDD[K, V, F <: NewInputFormat[K, V]](
      sc: JavaSparkContext,
      inputFormatClass: String,
      keyClass: String,
      valueClass: String,
      keyConverterClass: String,
      valueConverterClass: String,
      confAsMap: java.util.HashMap[String, String],
      batchSize: Int) = {
    val conf = PythonHadoopUtil.mapToConf(confAsMap)
    val rdd =
      newAPIHadoopRDDFromClassNames[K, V, F](sc,
        None, inputFormatClass, keyClass, valueClass, conf)
    val confBroadcasted = sc.sc.broadcast(new SerializableWritable(conf))
    val converted = convertRDD(rdd, keyConverterClass, valueConverterClass,
      new WritableToJavaConverter(confBroadcasted, batchSize))
    JavaRDD.fromRDD(SerDeUtil.pairRDDToPython(converted, batchSize))
  }

  private def newAPIHadoopRDDFromClassNames[K, V, F <: NewInputFormat[K, V]](
      sc: JavaSparkContext,
      path: Option[String] = None,
      inputFormatClass: String,
      keyClass: String,
      valueClass: String,
      conf: Configuration) = {
    val kc = Utils.classForName(keyClass).asInstanceOf[Class[K]]
    val vc = Utils.classForName(valueClass).asInstanceOf[Class[V]]
    val fc = Utils.classForName(inputFormatClass).asInstanceOf[Class[F]]
    if (path.isDefined) {
      sc.sc.newAPIHadoopFile[K, V, F](path.get, fc, kc, vc, conf)
    } else {
      sc.sc.newAPIHadoopRDD[K, V, F](conf, fc, kc, vc)
    }
  }

  /**
   * Create an RDD from a file path, using an arbitrary [[org.apache.hadoop.mapred.InputFormat]],
   * key and value class.
   * A key and/or value converter class can optionally be passed in
   * (see [[org.apache.spark.api.python.Converter]])
   */
  def hadoopFile[K, V, F <: InputFormat[K, V]](
      sc: JavaSparkContext,
      path: String,
      inputFormatClass: String,
      keyClass: String,
      valueClass: String,
      keyConverterClass: String,
      valueConverterClass: String,
      confAsMap: java.util.HashMap[String, String],
      batchSize: Int) = {
    val mergedConf = getMergedConf(confAsMap, sc.hadoopConfiguration())
    val rdd =
      hadoopRDDFromClassNames[K, V, F](sc,
        Some(path), inputFormatClass, keyClass, valueClass, mergedConf)
    val confBroadcasted = sc.sc.broadcast(new SerializableWritable(mergedConf))
    val converted = convertRDD(rdd, keyConverterClass, valueConverterClass,
      new WritableToJavaConverter(confBroadcasted, batchSize))
    JavaRDD.fromRDD(SerDeUtil.pairRDDToPython(converted, batchSize))
  }

  /**
   * Create an RDD from a [[org.apache.hadoop.conf.Configuration]] converted from a map
   * that is passed in from Python, using an arbitrary [[org.apache.hadoop.mapred.InputFormat]],
   * key and value class
   * A key and/or value converter class can optionally be passed in
   * (see [[org.apache.spark.api.python.Converter]])
   */
  def hadoopRDD[K, V, F <: InputFormat[K, V]](
      sc: JavaSparkContext,
      inputFormatClass: String,
      keyClass: String,
      valueClass: String,
      keyConverterClass: String,
      valueConverterClass: String,
      confAsMap: java.util.HashMap[String, String],
      batchSize: Int) = {
    val conf = PythonHadoopUtil.mapToConf(confAsMap)
    val rdd =
      hadoopRDDFromClassNames[K, V, F](sc,
        None, inputFormatClass, keyClass, valueClass, conf)
    val confBroadcasted = sc.sc.broadcast(new SerializableWritable(conf))
    val converted = convertRDD(rdd, keyConverterClass, valueConverterClass,
      new WritableToJavaConverter(confBroadcasted, batchSize))
    JavaRDD.fromRDD(SerDeUtil.pairRDDToPython(converted, batchSize))
  }

  private def hadoopRDDFromClassNames[K, V, F <: InputFormat[K, V]](
      sc: JavaSparkContext,
      path: Option[String] = None,
      inputFormatClass: String,
      keyClass: String,
      valueClass: String,
      conf: Configuration) = {
    val kc = Utils.classForName(keyClass).asInstanceOf[Class[K]]
    val vc = Utils.classForName(valueClass).asInstanceOf[Class[V]]
    val fc = Utils.classForName(inputFormatClass).asInstanceOf[Class[F]]
    if (path.isDefined) {
      sc.sc.hadoopFile(path.get, fc, kc, vc)
    } else {
      sc.sc.hadoopRDD(new JobConf(conf), fc, kc, vc)
    }
  }

  def writeUTF(str: String, dataOut: DataOutputStream) {
    val bytes = str.getBytes(UTF_8)
    dataOut.writeInt(bytes.length)
    dataOut.write(bytes)
  }

  def writeToFile[T](items: java.util.Iterator[T], filename: String) {
    import scala.collection.JavaConverters._
    writeToFile(items.asScala, filename)
  }

  def writeToFile[T](items: Iterator[T], filename: String) {
    val file = new DataOutputStream(new FileOutputStream(filename))
    writeIteratorToStream(items, file)
    file.close()
  }

  private def getMergedConf(confAsMap: java.util.HashMap[String, String],
      baseConf: Configuration): Configuration = {
    val conf = PythonHadoopUtil.mapToConf(confAsMap)
    PythonHadoopUtil.mergeConfs(baseConf, conf)
  }

  private def inferKeyValueTypes[K, V](rdd: RDD[(K, V)], keyConverterClass: String = null,
      valueConverterClass: String = null): (Class[_], Class[_]) = {
    // Peek at an element to figure out key/value types. Since Writables are not serializable,
    // we cannot call first() on the converted RDD. Instead, we call first() on the original RDD
    // and then convert locally.
    val (key, value) = rdd.first()
    val (kc, vc) = getKeyValueConverters(keyConverterClass, valueConverterClass,
      new JavaToWritableConverter)
    (kc.convert(key).getClass, vc.convert(value).getClass)
  }

  private def getKeyValueTypes(keyClass: String, valueClass: String):
      Option[(Class[_], Class[_])] = {
    for {
      k <- Option(keyClass)
      v <- Option(valueClass)
    } yield (Utils.classForName(k), Utils.classForName(v))
  }

  private def getKeyValueConverters(keyConverterClass: String, valueConverterClass: String,
      defaultConverter: Converter[Any, Any]): (Converter[Any, Any], Converter[Any, Any]) = {
    val keyConverter = Converter.getInstance(Option(keyConverterClass), defaultConverter)
    val valueConverter = Converter.getInstance(Option(valueConverterClass), defaultConverter)
    (keyConverter, valueConverter)
  }

  /**
   * Convert an RDD of key-value pairs from internal types to serializable types suitable for
   * output, or vice versa.
   */
  private def convertRDD[K, V](rdd: RDD[(K, V)],
                               keyConverterClass: String,
                               valueConverterClass: String,
                               defaultConverter: Converter[Any, Any]): RDD[(Any, Any)] = {
    val (kc, vc) = getKeyValueConverters(keyConverterClass, valueConverterClass,
      defaultConverter)
    PythonHadoopUtil.convertRDD(rdd, kc, vc)
  }

  /**
   * Output a Python RDD of key-value pairs as a Hadoop SequenceFile using the Writable types
   * we convert from the RDD's key and value types. Note that keys and values can't be
   * [[org.apache.hadoop.io.Writable]] types already, since Writables are not Java
   * `Serializable` and we can't peek at them. The `path` can be on any Hadoop file system.
   */
  def saveAsSequenceFile[K, V, C <: CompressionCodec](
      pyRDD: JavaRDD[Array[Byte]],
      batchSerialized: Boolean,
      path: String,
      compressionCodecClass: String) = {
    saveAsHadoopFile(
      pyRDD, batchSerialized, path, "org.apache.hadoop.mapred.SequenceFileOutputFormat",
      null, null, null, null, new java.util.HashMap(), compressionCodecClass)
  }

  /**
   * Output a Python RDD of key-value pairs to any Hadoop file system, using old Hadoop
   * `OutputFormat` in mapred package. Keys and values are converted to suitable output
   * types using either user specified converters or, if not specified,
   * [[org.apache.spark.api.python.JavaToWritableConverter]]. Post-conversion types
   * `keyClass` and `valueClass` are automatically inferred if not specified. The passed-in
   * `confAsMap` is merged with the default Hadoop conf associated with the SparkContext of
   * this RDD.
   */
  def saveAsHadoopFile[K, V, F <: OutputFormat[_, _], C <: CompressionCodec](
      pyRDD: JavaRDD[Array[Byte]],
      batchSerialized: Boolean,
      path: String,
      outputFormatClass: String,
      keyClass: String,
      valueClass: String,
      keyConverterClass: String,
      valueConverterClass: String,
      confAsMap: java.util.HashMap[String, String],
      compressionCodecClass: String) = {
    val rdd = SerDeUtil.pythonToPairRDD(pyRDD, batchSerialized)
    val (kc, vc) = getKeyValueTypes(keyClass, valueClass).getOrElse(
      inferKeyValueTypes(rdd, keyConverterClass, valueConverterClass))
    val mergedConf = getMergedConf(confAsMap, pyRDD.context.hadoopConfiguration)
    val codec = Option(compressionCodecClass).map(Utils.classForName(_).asInstanceOf[Class[C]])
    val converted = convertRDD(rdd, keyConverterClass, valueConverterClass,
      new JavaToWritableConverter)
    val fc = Utils.classForName(outputFormatClass).asInstanceOf[Class[F]]
    converted.saveAsHadoopFile(path, kc, vc, fc, new JobConf(mergedConf), codec=codec)
  }

  /**
   * Output a Python RDD of key-value pairs to any Hadoop file system, using new Hadoop
   * `OutputFormat` in mapreduce package. Keys and values are converted to suitable output
   * types using either user specified converters or, if not specified,
   * [[org.apache.spark.api.python.JavaToWritableConverter]]. Post-conversion types
   * `keyClass` and `valueClass` are automatically inferred if not specified. The passed-in
   * `confAsMap` is merged with the default Hadoop conf associated with the SparkContext of
   * this RDD.
   */
  def saveAsNewAPIHadoopFile[K, V, F <: NewOutputFormat[_, _]](
      pyRDD: JavaRDD[Array[Byte]],
      batchSerialized: Boolean,
      path: String,
      outputFormatClass: String,
      keyClass: String,
      valueClass: String,
      keyConverterClass: String,
      valueConverterClass: String,
      confAsMap: java.util.HashMap[String, String]) = {
    val rdd = SerDeUtil.pythonToPairRDD(pyRDD, batchSerialized)
    val (kc, vc) = getKeyValueTypes(keyClass, valueClass).getOrElse(
      inferKeyValueTypes(rdd, keyConverterClass, valueConverterClass))
    val mergedConf = getMergedConf(confAsMap, pyRDD.context.hadoopConfiguration)
    val converted = convertRDD(rdd, keyConverterClass, valueConverterClass,
      new JavaToWritableConverter)
    val fc = Utils.classForName(outputFormatClass).asInstanceOf[Class[F]]
    converted.saveAsNewAPIHadoopFile(path, kc, vc, fc, mergedConf)
  }

  /**
   * Output a Python RDD of key-value pairs to any Hadoop file system, using a Hadoop conf
   * converted from the passed-in `confAsMap`. The conf should set relevant output params (
   * e.g., output path, output format, etc), in the same way as it would be configured for
   * a Hadoop MapReduce job. Both old and new Hadoop OutputFormat APIs are supported
   * (mapred vs. mapreduce). Keys/values are converted for output using either user specified
   * converters or, by default, [[org.apache.spark.api.python.JavaToWritableConverter]].
   */
  def saveAsHadoopDataset[K, V](
      pyRDD: JavaRDD[Array[Byte]],
      batchSerialized: Boolean,
      confAsMap: java.util.HashMap[String, String],
      keyConverterClass: String,
      valueConverterClass: String,
      useNewAPI: Boolean) = {
    val conf = PythonHadoopUtil.mapToConf(confAsMap)
    val converted = convertRDD(SerDeUtil.pythonToPairRDD(pyRDD, batchSerialized),
      keyConverterClass, valueConverterClass, new JavaToWritableConverter)
    if (useNewAPI) {
      converted.saveAsNewAPIHadoopDataset(conf)
    } else {
      converted.saveAsHadoopDataset(new JobConf(conf))
    }
  }


  /**
   * Convert an RDD of serialized Python dictionaries to Scala Maps (no recursive conversions).
   */
  @deprecated("PySpark does not use it anymore", "1.1")
  def pythonToJavaMap(pyRDD: JavaRDD[Array[Byte]]): JavaRDD[Map[String, _]] = {
    pyRDD.rdd.mapPartitions { iter =>
      val unpickle = new Unpickler
      SerDeUtil.initialize()
      iter.flatMap { row =>
        unpickle.loads(row) match {
          // in case of objects are pickled in batch mode
          case objs: JArrayList[JMap[String, _] @unchecked] => objs.map(_.toMap)
          // not in batch mode
          case obj: JMap[String @unchecked, _] => Seq(obj.toMap)
        }
      }
    }
  }

  /**
   * Convert an RDD of serialized Python tuple to Array (no recursive conversions).
   * It is only used by pyspark.sql.
   */
  def pythonToJavaArray(pyRDD: JavaRDD[Array[Byte]], batched: Boolean): JavaRDD[Array[_]] = {

    def toArray(obj: Any): Array[_] = {
      obj match {
        case objs: JArrayList[_] =>
          objs.toArray
        case obj if obj.getClass.isArray =>
          obj.asInstanceOf[Array[_]].toArray
      }
    }

    pyRDD.rdd.mapPartitions { iter =>
      val unpickle = new Unpickler
      iter.flatMap { row =>
        val obj = unpickle.loads(row)
        if (batched) {
          obj.asInstanceOf[JArrayList[_]].map(toArray)
        } else {
          Seq(toArray(obj))
        }
      }
    }.toJavaRDD()
  }

  private[spark] class AutoBatchedPickler(iter: Iterator[Any]) extends Iterator[Array[Byte]] {
    private val pickle = new Pickler()
    private var batch = 1
    private val buffer = new mutable.ArrayBuffer[Any]

    override def hasNext(): Boolean = iter.hasNext

    override def next(): Array[Byte] = {
      while (iter.hasNext && buffer.length < batch) {
        buffer += iter.next()
      }
      val bytes = pickle.dumps(buffer.toArray)
      val size = bytes.length
      // let  1M < size < 10M
      if (size < 1024 * 1024) {
        batch *= 2
      } else if (size > 1024 * 1024 * 10 && batch > 1) {
        batch /= 2
      }
      buffer.clear()
      bytes
    }
  }

  /**
   * Convert an RDD of Java objects to an RDD of serialized Python objects, that is usable by
   * PySpark.
   */
  def javaToPython(jRDD: JavaRDD[Any]): JavaRDD[Array[Byte]] = {
    jRDD.rdd.mapPartitions { iter => new AutoBatchedPickler(iter) }
  }

  /**
    * Convert an RDD of serialized Python objects to RDD of objects, that is usable by PySpark.
    */
  def pythonToJava(pyRDD: JavaRDD[Array[Byte]], batched: Boolean): JavaRDD[Any] = {
    pyRDD.rdd.mapPartitions { iter =>
      SerDeUtil.initialize()
      val unpickle = new Unpickler
      iter.flatMap { row =>
        val obj = unpickle.loads(row)
        if (batched) {
          obj.asInstanceOf[JArrayList[_]].asScala
        } else {
          Seq(obj)
        }
      }
    }.toJavaRDD()
  }
}

private
class BytesToString extends org.apache.spark.api.java.function.Function[Array[Byte], String] {
  override def call(arr: Array[Byte]) : String = new String(arr, UTF_8)
}

/**
 * Internal class that acts as an `AccumulatorParam` for Python accumulators. Inside, it
 * collects a list of pickled strings that we pass to Python through a socket.
 */
private class PythonAccumulatorParam(@transient serverHost: String, serverPort: Int)
  extends AccumulatorParam[JList[Array[Byte]]] {

  Utils.checkHost(serverHost, "Expected hostname")

  val bufferSize = SparkEnv.get.conf.getInt("spark.buffer.size", 65536)

  /** 
   * We try to reuse a single Socket to transfer accumulator updates, as they are all added
   * by the DAGScheduler's single-threaded actor anyway.
   */ 
  @transient var socket: Socket = _

  def openSocket(): Socket = synchronized {
    if (socket == null || socket.isClosed) {
      socket = new Socket(serverHost, serverPort)
    }
    socket
  }

  override def zero(value: JList[Array[Byte]]): JList[Array[Byte]] = new JArrayList

  override def addInPlace(val1: JList[Array[Byte]], val2: JList[Array[Byte]])
      : JList[Array[Byte]] = synchronized {
    if (serverHost == null) {
      // This happens on the worker node, where we just want to remember all the updates
      val1.addAll(val2)
      val1
    } else {
      // This happens on the master, where we pass the updates to Python through a socket
      val socket = openSocket()
      val in = socket.getInputStream
      val out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream, bufferSize))
      out.writeInt(val2.size)
      for (array <- val2) {
        out.writeInt(array.length)
        out.write(array)
      }
      out.flush()
      // Wait for a byte from the Python side as an acknowledgement
      val byteRead = in.read()
      if (byteRead == -1) {
        throw new SparkException("EOF reached before Python server acknowledged")
      }
      null
    }
  }
}
