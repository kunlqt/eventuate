/*
 * Copyright (C) 2015 Red Bull Media House GmbH <http://www.redbullmediahouse.com> - all rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.rbmhtechnology.eventuate.log.cassandra

import java.lang.{Long => JLong}
import java.util.concurrent.atomic.AtomicReference
import java.util.function.BinaryOperator

import com.datastax.driver.core.{PreparedStatement, Row}
import com.rbmhtechnology.eventuate._

import scala.collection.JavaConverters._
import scala.collection.immutable.{VectorBuilder, Seq}
import scala.concurrent.Future

private[eventuate] class CassandraEventReader(cassandra: Cassandra, logId: String) extends CassandraEventIteratorLeasing {
  import CassandraEventReader._

  val statement: PreparedStatement =
    cassandra.prepareReadEventBatches(logId)

  def replayAsync(fromSequenceNr: Long)(f: DurableEvent => Unit): Future[Unit] =
    Future(replay(fromSequenceNr)(f))(cassandra.readDispatcher)

  def replay(fromSequenceNr: Long)(f: DurableEvent => Unit): Unit =
    eventIterator(fromSequenceNr, Long.MaxValue).foreach(f)

  def readAsync(fromSequenceNr: Long, max: Int, filter: ReplicationFilter, targetLogId: String): Future[ReadResult] = {
    Future(read(fromSequenceNr, max, filter, targetLogId))(cassandra.readDispatcher)
  }

  def read(fromSequenceNr: Long, max: Int, filter: ReplicationFilter, targetLogId: String): ReadResult = {
    val builder = new VectorBuilder[DurableEvent]
    val iterator = leaseEventIterator(targetLogId, fromSequenceNr)
    var num = 0

    while (iterator.hasNext && num < max) {
      val event = iterator.next()
      if (filter.apply(event)) {
        builder += event
        num += 1
      }
    }
    releaseEventIterator(iterator)
    ReadResult(builder.result(), iterator.lastSequenceNrRead)
  }

  def eventIterator(fromSequenceNr: Long, toSequenceNr: Long): Iterator[DurableEvent] = for {
    batch <- eventBatchIterator(fromSequenceNr, toSequenceNr)
    event <- batch.events if event.sequenceNr >= fromSequenceNr && event.sequenceNr <= toSequenceNr
  } yield event

  def eventBatchIterator(fromSequenceNr: Long, toSequenceNr: Long): Iterator[DurableEventBatch] =
    new EventBatchIterator(fromSequenceNr, toSequenceNr)

  private class EventBatchIterator(fromSequenceNr: Long, toSequenceNr: Long) extends Iterator[DurableEventBatch] {
    var currentSnr = fromSequenceNr
    var currentIter = newIter()
    var rowCount = 0

    def newIter(): Iterator[Row] =
      if (currentSnr > toSequenceNr) Iterator.empty else cassandra.session.execute(statement.bind(0L: JLong, currentSnr: JLong)).iterator.asScala

    @annotation.tailrec
    final def hasNext: Boolean = {
      if (currentIter.hasNext) {
        true
      } else if (rowCount < cassandra.config.maxResultSetSize) {
        // all batches consumed
        false
      } else {
        // max result set size reached, fetch again
        currentSnr += 1L
        currentIter = newIter()
        rowCount = 0
        hasNext
      }
    }

    def next(): DurableEventBatch = {
      val row = currentIter.next()
      currentSnr = row.getLong("sequence_nr")
      rowCount += 1
      cassandra.eventBatchFromByteBuffer(row.getBytes("eventBatch"))
    }
  }
}

private[eventuate] object CassandraEventReader {
  case class ReadResult(events: Seq[DurableEvent], to: Long)
}

private[eventuate] trait CassandraEventIteratorLeasing {
  import CassandraEventIteratorLeasing._

  private val eventLogIterators: AtomicReference[Map[String, LeasableEventIterator]] =
    new AtomicReference(Map.empty)

  def eventIterator(fromSequenceNr: Long, toSequenceNr: Long): Iterator[DurableEvent]

  def leaseEventIterator(leaserId: String, fromSequenceNr: Long): LeasableEventIterator = {
    eventLogIterators.get.get(leaserId) match {
      case Some(iter) if iter.lastSequenceNrRead == (fromSequenceNr - 1L) && iter.hasNext =>
        iter
      case _ =>
        new LeasableEventIterator(leaserId, fromSequenceNr, eventIterator(fromSequenceNr, Long.MaxValue))
    }
  }

  def releaseEventIterator(iterator: LeasableEventIterator): Unit = {
    eventLogIterators.getAndAccumulate(Map(iterator.leaserId -> iterator), new BinaryOperator[Map[String, LeasableEventIterator]] {
      override def apply(t: Map[String, LeasableEventIterator], u: Map[String, LeasableEventIterator]): Map[String, LeasableEventIterator] = t ++ u
    })
  }
}

private[eventuate] object CassandraEventIteratorLeasing {
  class LeasableEventIterator(val leaserId: String, fromSequenceNr: Long, iterator: Iterator[DurableEvent]) extends Iterator[DurableEvent] {
    private var _lastSequenceNrRead = fromSequenceNr - 1L

    def lastSequenceNrRead: Long =
      _lastSequenceNrRead

    override def hasNext: Boolean =
      iterator.hasNext

    override def next(): DurableEvent = {
      val event = iterator.next()
      _lastSequenceNrRead = event.sequenceNr
      event
    }
  }
} 